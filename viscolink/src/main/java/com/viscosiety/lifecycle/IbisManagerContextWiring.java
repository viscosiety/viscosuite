package com.viscosiety.lifecycle;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextAttributeEvent;
import jakarta.servlet.ServletContextAttributeListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.context.WebApplicationContext;

import org.frankframework.configuration.IbisContext;
import org.frankframework.configuration.IbisManager;
import org.frankframework.lifecycle.FrankApplicationInitializer;

/**
 * TEMPORARY workaround for a Frank!Framework 10.4 master regression: since frankframework/frankframework#12031
 * ("Create ClassLoaders Like Spring Beans", 2e911348d, 24 November 2026) nothing calls
 * {@link IbisManager#setIbisContext(IbisContext)} any more (that PR removed the only call, in
 * {@code IbisContext.init}). {@code IbisManager.handleAction} then fails every RELOAD and FULL_RELOAD with
 * {@code NullPointerException: Cannot invoke "IbisContext.fullReload()" because "this.ibisContext" is null}:
 * the console's reload buttons, the configurations API and ViscoFoundry's draft switch
 * ({@code /api-service/configurations/ref}) all stop working. Found by {@code DraftRefSwitchIT}.
 *
 * <p>This bean lives in the IbisApplicationContext (registered via
 * {@code ViscoLinkModule.getSpringConfigurationFiles()}), next to the {@code ibisManager} bean. The
 * {@link IbisContext} itself is not a bean; {@link FrankApplicationInitializer} stores it as the servlet
 * context attribute {@link FrankApplicationInitializer#CONTEXT_KEY} once {@code init()} returns. So:
 * <ul>
 *   <li>normal start: the attribute is not there yet when this bean initialises, so it registers a
 *       {@link ServletContextAttributeListener} (allowed: this still runs inside the container's
 *       {@code onStartup}) and wires the manager the moment the attribute is set;</li>
 *   <li>a start that is retried ({@code IbisContextRunnable}, after a failed first {@code init()}): the
 *       attribute is already there, so the new context's manager is wired at once.</li>
 * </ul>
 * It only sets the context when the manager has none, so it does nothing on a Frank!Framework that wires
 * it itself. Remove this class and {@code springIbisManagerContextWiring.xml} once the consumed
 * Frank!Framework build calls {@code setIbisContext} again and {@code DraftRefSwitchIT} is green without it.
 */
public class IbisManagerContextWiring implements ApplicationContextAware, SmartInitializingSingleton {

	private static final Logger LOG = LogManager.getLogger(IbisManagerContextWiring.class);

	private ApplicationContext applicationContext;

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	@Override
	public void afterSingletonsInstantiated() {
		ServletContext servletContext = servletContextOf(applicationContext);
		if (servletContext == null) {
			LOG.debug("no ServletContext above this context; IbisManager wiring not needed");
			return;
		}
		Object existing = servletContext.getAttribute(FrankApplicationInitializer.CONTEXT_KEY);
		if (existing instanceof IbisContext ibisContext) {
			// Retried start: the IbisContext object is already published, and this is its new Spring context.
			wire(applicationContext.getBean(IbisManager.class), ibisContext);
			return;
		}
		try {
			servletContext.addListener(new Listener());
		} catch (IllegalStateException | UnsupportedOperationException e) {
			LOG.warn("could not register the IbisManager wiring listener; configuration reloads may fail", e);
		}
	}

	/** Gives the manager its context when it has none. Package-private for the tests. */
	static void wire(IbisManager manager, IbisContext ibisContext) {
		if (manager == null || ibisContext == null || manager.getIbisContext() != null) {
			return;
		}
		manager.setIbisContext(ibisContext);
		LOG.info("IbisManager had no IbisContext (frankframework#12031 regression); set it so configuration reloads work");
	}

	private static ServletContext servletContextOf(ApplicationContext context) {
		for (ApplicationContext c = context; c != null; c = c.getParent()) {
			if (c instanceof WebApplicationContext web && web.getServletContext() != null) {
				return web.getServletContext();
			}
		}
		return null;
	}

	/** Wires the IbisContext's own manager when the initializer publishes the context. */
	static final class Listener implements ServletContextAttributeListener {
		@Override
		public void attributeAdded(ServletContextAttributeEvent event) {
			handle(event);
		}

		@Override
		public void attributeReplaced(ServletContextAttributeEvent event) {
			handle(event);
		}

		private static void handle(ServletContextAttributeEvent event) {
			if (!FrankApplicationInitializer.CONTEXT_KEY.equals(event.getName())) {
				return;
			}
			// attributeReplaced reports the OLD value; read the current one.
			Object value = event.getServletContext().getAttribute(FrankApplicationInitializer.CONTEXT_KEY);
			if (value instanceof IbisContext ibisContext) {
				wire(ibisContext.getIbisManager(), ibisContext);
			}
		}
	}
}
