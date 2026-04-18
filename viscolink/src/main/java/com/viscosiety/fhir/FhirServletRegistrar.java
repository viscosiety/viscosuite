package com.viscosiety.fhir;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.context.WebApplicationContext;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamically registers per-facade HAPI FHIR servlets with Tomcat's {@link ServletContext}.
 *
 * <p>F!F's {@code ServletRegisteringPostProcessor} lives in the parent "Frank EnvironmentContext"
 * and only processes beans in that context. Beans declared in module Spring files
 * (via {@code ViscoLinkModule.getSpringConfigurationFiles()}) end up in the child
 * {@code IbisApplicationContext}, so SRPP never sees them.</p>
 *
 * <p>This registrar works around that by implementing {@link InitializingBean}: during the
 * child-context refresh it resolves and stores the {@link ServletContext}. Actual servlet
 * registration happens later, driven by {@link FhirListener#configure()} via the static
 * {@link #notifyFacadeDeclared(String, String)} method — one {@link FhirFacadeServlet} per
 * unique {@code (fhirVersion, facadeName)} pair.</p>
 */
public class FhirServletRegistrar implements InitializingBean, ApplicationContextAware {

    private static final Logger log = LogManager.getLogger(FhirServletRegistrar.class);

    /** Singleton instance set at construction time — available before F!F loads configurations. */
    private static volatile FhirServletRegistrar INSTANCE;

    /**
     * Tracks facades already registered with Tomcat. Static so it survives Spring context
     * recreation on F!F configuration reload — Tomcat's ServletContext is only writable once
     * during startup, so facades must never be registered twice.
     */
    private static final Set<String> registeredFacades = ConcurrentHashMap.newKeySet();

    private final FhirFfBridge bridge;
    private ApplicationContext applicationContext;
    private ServletContext servletContext;

    public FhirServletRegistrar(FhirFfBridge bridge) {
        this.bridge = bridge;
        INSTANCE = this;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        this.servletContext = findServletContext(applicationContext);
        if (servletContext == null) {
            log.warn("FhirServletRegistrar: no ServletContext found in application context hierarchy — FHIR servlets cannot be registered");
        }
    }

    /**
     * Called by {@link FhirListener#configure()} for every {@code (fhirVersion, facadeName)} pair
     * it declares.  Creates and registers a new {@link FhirFacadeServlet} the first time a given
     * facade is seen; subsequent calls for the same facade are no-ops.
     */
    static void notifyFacadeDeclared(String fhirVersion, String facadeName) {
        FhirServletRegistrar inst = INSTANCE;
        if (inst == null) {
            log.warn("FhirServletRegistrar not yet initialized when facade [{}:{}] was declared", fhirVersion, facadeName);
            return;
        }
        inst.ensureFacadeRegistered(fhirVersion, facadeName);
    }

    private void ensureFacadeRegistered(String fhirVersion, String facadeName) {
        String key = fhirVersion + ":" + facadeName;
        if (!registeredFacades.add(key)) {
            return;
        }
        if (servletContext == null) {
            log.warn("FhirServletRegistrar: ServletContext is null — cannot register facade [{}]", key);
            return;
        }
        FhirFacadeServlet servlet = new FhirFacadeServlet(fhirVersion, facadeName, bridge);
        register(servletContext, servlet);
    }

    private void register(ServletContext servletContext, AbstractFhirServlet fhirServlet) {
        String name = fhirServlet.getName();
        String urlPattern = "/" + fhirServlet.getUrlMapping();
        ServletRegistration.Dynamic reg;
        try {
            reg = servletContext.addServlet(name, fhirServlet);
        } catch (IllegalStateException e) {
            log.warn("FhirServletRegistrar: cannot register [{}] — context already initialised ({})", name, e.getMessage());
            return;
        }
        if (reg == null) {
            log.warn("FhirServletRegistrar: addServlet returned null for [{}] — servlet name already taken", name);
            return;
        }
        reg.setLoadOnStartup(-1);
        reg.addMapping(urlPattern);
        log.info("FhirServletRegistrar: registered FHIR facade servlet [{}] at [{}]", name, urlPattern);
    }

    /** Walks up the context hierarchy to find the first {@link WebApplicationContext}. */
    private static ServletContext findServletContext(ApplicationContext ctx) {
        if (ctx instanceof WebApplicationContext wac) {
            return wac.getServletContext();
        }
        ApplicationContext parent = ctx.getParent();
        return parent != null ? findServletContext(parent) : null;
    }
}
