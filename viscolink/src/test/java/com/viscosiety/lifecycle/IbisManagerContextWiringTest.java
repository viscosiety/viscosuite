package com.viscosiety.lifecycle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletContextAttributeEvent;

import org.frankframework.configuration.IbisContext;
import org.frankframework.configuration.IbisManager;
import org.frankframework.lifecycle.FrankApplicationInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.StaticWebApplicationContext;

/**
 * The wiring that works around frankframework#12031 (IbisManager left without its IbisContext, so every
 * configuration reload fails). The real proof is {@code DraftRefSwitchIT}, which reloads on the runtime.
 */
class IbisManagerContextWiringTest {

	@Test
	void theListenerGivesTheManagerItsContextWhenTheInitializerPublishesIt() {
		MockServletContext servletContext = new MockServletContext();
		IbisManager manager = mock(IbisManager.class);
		IbisContext ibisContext = mock(IbisContext.class);
		when(ibisContext.getIbisManager()).thenReturn(manager);
		servletContext.setAttribute(FrankApplicationInitializer.CONTEXT_KEY, ibisContext);

		new IbisManagerContextWiring.Listener().attributeAdded(
				new ServletContextAttributeEvent(servletContext, FrankApplicationInitializer.CONTEXT_KEY, ibisContext));

		verify(manager).setIbisContext(ibisContext);
	}

	@Test
	void theListenerIgnoresOtherAttributes() {
		MockServletContext servletContext = new MockServletContext();
		IbisManager manager = mock(IbisManager.class);
		IbisContext ibisContext = mock(IbisContext.class);
		when(ibisContext.getIbisManager()).thenReturn(manager);
		servletContext.setAttribute(FrankApplicationInitializer.CONTEXT_KEY, ibisContext);

		new IbisManagerContextWiring.Listener().attributeAdded(
				new ServletContextAttributeEvent(servletContext, "somethingElse", "x"));

		verify(manager, never()).setIbisContext(ibisContext);
	}

	@Test
	void aRetriedStartWiresTheNewContextsManagerAtOnce() {
		// A retried init(): the IbisContext object is already in the servlet context when the new Spring
		// context (and this bean) comes up.
		MockServletContext servletContext = new MockServletContext();
		IbisContext ibisContext = mock(IbisContext.class);
		servletContext.setAttribute(FrankApplicationInitializer.CONTEXT_KEY, ibisContext);
		IbisManager manager = mock(IbisManager.class);

		StaticWebApplicationContext web = new StaticWebApplicationContext();
		web.setServletContext(servletContext);
		web.refresh();
		GenericApplicationContext ibisApplicationContext = new GenericApplicationContext(web);
		ibisApplicationContext.registerBean("ibisManager", IbisManager.class, () -> manager);
		ibisApplicationContext.registerBean("ibisManagerContextWiring", IbisManagerContextWiring.class);
		ibisApplicationContext.refresh();
		try {
			verify(manager).setIbisContext(ibisContext);
		} finally {
			ibisApplicationContext.close();
			web.close();
		}
	}

	@Test
	void aManagerThatAlreadyHasItsContextIsLeftAlone() {
		// A Frank!Framework that wires the manager itself again: the workaround does nothing.
		IbisManager manager = mock(IbisManager.class);
		IbisContext own = mock(IbisContext.class);
		when(manager.getIbisContext()).thenReturn(own);

		IbisManagerContextWiring.wire(manager, mock(IbisContext.class));

		verify(manager, never()).setIbisContext(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void outsideAServletContainerNothingHappens() {
		GenericApplicationContext plain = new GenericApplicationContext();
		IbisManager manager = mock(IbisManager.class);
		plain.registerBean("ibisManager", IbisManager.class, () -> manager);
		plain.registerBean("ibisManagerContextWiring", IbisManagerContextWiring.class);
		plain.refresh();
		try {
			verify(manager, never()).setIbisContext(org.mockito.ArgumentMatchers.any());
		} finally {
			plain.close();
		}
	}
}
