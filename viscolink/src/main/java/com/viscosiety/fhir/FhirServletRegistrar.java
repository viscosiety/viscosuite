package com.viscosiety.fhir;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.frankframework.lifecycle.servlets.AbstractServletAuthenticator;
import org.frankframework.lifecycle.servlets.AuthenticatorUtils;
import org.frankframework.lifecycle.servlets.IAuthenticator;
import org.frankframework.lifecycle.servlets.ServletConfiguration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamically registers per-facade HAPI FHIR servlets with Tomcat's {@link ServletContext} and
 * applies the same authentication to {@code /fhir/**} as F!F's console applies to {@code /iaf/api/**}.
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
 *
 * <h3>Security for {@code /fhir/**}</h3>
 * <p>F!F's {@code SecurityChainConfigurer} runs in the parent context and builds a
 * {@link FilterChainProxy} that protects {@code /iaf/api/**} and {@code /iaf/gui/**}.  Because our
 * beans are in the child {@code IbisApplicationContext}, we cannot contribute additional
 * {@code SecurityFilterChain} beans to that proxy at configuration time.</p>
 *
 * <p>The workaround: in {@link #afterPropertiesSet()} we call {@link AuthenticatorUtils} with the
 * same property prefix ({@code application.security.console.authentication.}) that F!F's console
 * chain uses, creating an identical {@link IAuthenticator}.  We then build two
 * {@code SecurityFilterChain}s and splice them into the parent context's existing
 * {@link FilterChainProxy} at positions 0 and 1:</p>
 * <ol>
 *   <li><b>Position 0 — metadata chain</b>: permit-all for any path matched by
 *       {@link #isPublicFhirMetadataPath(jakarta.servlet.http.HttpServletRequest)}, so that FHIR
 *       {@code /metadata} endpoints are publicly accessible per the FHIR specification.</li>
 *   <li><b>Position 1 — auth chain</b>: enforces the same authentication as F!F's console for all
 *       other {@code /fhir/**} requests.  Built via {@link IAuthenticator#build()}, which registers
 *       the chain in the parent {@code BeanFactory} under the name
 *       {@code HttpSecurityChain-{class}-{hash}} (an internal F!F convention verified against
 *       10.1.0 source).  Both chains use {@code STATELESS} sessions, appropriate for FHIR REST
 *       clients that authenticate per-request via Basic Auth or Bearer tokens.</li>
 * </ol>
 *
 * <p>This approach reads the exact same configuration properties as F!F and delegates all
 * authentication logic — Basic Auth, session management, authority mapping — to the same
 * {@link IAuthenticator} implementation, without duplicating a single line of auth code.</p>
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
            return;
        }
        registerFacadesApiServlet();
        registerFhirSecurityChain();
    }

    private void registerFacadesApiServlet() {
        try {
            ServletRegistration.Dynamic reg = servletContext.addServlet("fhirFacadesApi", new FhirFacadesApiServlet());
            if (reg != null) {
                reg.setLoadOnStartup(-1);
                reg.addMapping("/iaf/api/fhir-facades");
                log.info("FhirServletRegistrar: registered FHIR facades API servlet at /iaf/api/fhir-facades");
            } else {
                log.debug("FhirServletRegistrar: fhirFacadesApi servlet already registered");
            }
        } catch (IllegalStateException e) {
            log.warn("FhirServletRegistrar: cannot register fhirFacadesApi — context already initialised ({})", e.getMessage());
        }
    }

    /**
     * Applies two {@link SecurityFilterChain}s to the {@code /fhir/} URL space and splices them
     * into the parent context's existing {@link FilterChainProxy}:
     *
     * <ol>
     *   <li><b>Metadata chain (position 0)</b> — permits {@code /metadata} without authentication.
     *       FHIR CapabilityStatements must be publicly accessible per the FHIR specification.</li>
     *   <li><b>Auth chain (position 1)</b> — applies the same authentication as F!F's console
     *       ({@code /iaf/api/**}) to all other {@code /fhir/**} requests, reading the same
     *       {@code application.security.console.authentication.*} properties via
     *       {@link AuthenticatorUtils}.</li>
     * </ol>
     *
     * <p>URL pattern note: {@code ServletConfiguration.setUrlMapping("/fhir/*")} is intentional.
     * {@code URLRequestMatcher} strips the trailing {@code *}, storing {@code /fhir/} as the prefix,
     * which correctly matches all real FHIR request paths via {@code path.startsWith("/fhir/")}.
     * Using {@code /fhir/**} would strip to {@code /fhir/*} (with a literal asterisk), which never
     * matches any real path.</p>
     *
     * <p>The bean name {@code HttpSecurityChain-{simpleClassName}-{hashCode}} is an internal F!F
     * convention from {@code AbstractServletAuthenticator.build()} (verified against 10.1.0
     * bytecode).  If a future F!F version changes this naming scheme, startup will log an error
     * and {@code /fhir/**} will remain unprotected rather than failing hard.</p>
     *
     * <p>Spring Security 7 removed {@code FilterChainProxy.setFilterChains()}.  Furthermore,
     * Spring Security 7 wraps the real proxy in a
     * {@code WebSecurityConfiguration$CompositeFilterChainProxy} (a subclass that stores its chains
     * in a private {@code springSecurityFilterChain} field rather than the inherited
     * {@code FilterChainProxy.filterChains} field).  We reach through to the inner instance
     * and write its {@code filterChains} field via reflection.  All JARs run in the unnamed module
     * inside Tomcat, so {@code setAccessible(true)} succeeds without {@code --add-opens}.</p>
     */
    private void registerFhirSecurityChain() {
        ApplicationContext parentCtx = applicationContext.getParent();
        if (parentCtx == null) {
            log.warn("FhirServletRegistrar: no parent context — /fhir/** endpoints will be unprotected");
            return;
        }
        try {
            // ── 1. Auth chain for /fhir/* ────────────────────────────────────────────

            // SpringUtils.createBean (used internally by AuthenticatorUtils) runs Spring's full
            // bean lifecycle on the new instance, including ApplicationContextAwareProcessor,
            // so the authenticator receives parentCtx as its applicationContext automatically.
            IAuthenticator authenticator = AuthenticatorUtils.createAuthenticator(
                    parentCtx, "application.security.console.authentication.");

            // AbstractServletAuthenticator.addEndpoints() puts a URL into publicEndpoints
            // (no auth required) when config.getSecurityRoles().isEmpty(), and build() returns
            // early when privateEndpoints is empty.  We must supply non-empty roles so that
            // /fhir/* is treated as a private (auth-required) endpoint.
            // The actual authorization check uses AuthenticatedAuthorizationManager.authenticated()
            // regardless of which roles are listed here — they only flip the public/private
            // classification inside addEndpoints().
            String[] defaultRoles = AbstractServletAuthenticator.DEFAULT_IBIS_ROLES.toArray(new String[0]);

            // /fhir/* — URLRequestMatcher strips the trailing *, storing prefix /fhir/ which
            // correctly matches all FHIR paths via path.startsWith("/fhir/").
            // Using /fhir/** would leave a literal asterisk in the prefix, matching nothing.
            ServletConfiguration config = new ServletConfiguration();
            config.setEnvironment(parentCtx.getEnvironment());
            config.setName("ViscoLinkFhirFacades");
            config.afterPropertiesSet();
            config.setSecurityRoles(defaultRoles);
            config.setUrlMapping("/fhir/*");
            authenticator.registerServlet(config);

            // build() registers the SecurityFilterChain in the parent BeanFactory under the name below.
            String chainBeanName = "HttpSecurityChain-"
                    + authenticator.getClass().getSimpleName() + "-" + authenticator.hashCode();
            authenticator.build();

            SecurityFilterChain authChain = parentCtx.getBean(chainBeanName, SecurityFilterChain.class);

            // ── 2. Permit-all chain for /fhir/*/metadata ─────────────────────────────

            // Each getBean() call on the HttpSecurity prototype returns a fresh instance, so
            // we can build an independent chain without interfering with the auth chain above.
            // Path matching is delegated to isPublicFhirMetadataPath() — see its Javadoc for
            // the two URL layouts that must be handled.
            HttpSecurity metaHttp = parentCtx.getBean(
                    "org.springframework.security.config.annotation.web.configuration.HttpSecurityConfiguration.httpSecurity",
                    HttpSecurity.class);
            SecurityFilterChain metadataChain = metaHttp
                    .securityMatcher(FhirServletRegistrar::isPublicFhirMetadataPath)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .build();

            // ── 3. Splice both chains into the FilterChainProxy ───────────────────────

            // In Spring Security 7, parentCtx.getBean(FilterChainProxy.class) returns a
            // WebSecurityConfiguration$CompositeFilterChainProxy — a subclass that stores its
            // real chains in a private "springSecurityFilterChain" inner FilterChainProxy, not
            // in FilterChainProxy's own "filterChains" field.  We must reach through to the
            // inner instance to update the list that doFilter() and getFilterChains() actually use.
            FilterChainProxy fcp = parentCtx.getBean(FilterChainProxy.class);

            FilterChainProxy realFcp;
            try {
                java.lang.reflect.Field innerField = fcp.getClass().getDeclaredField("springSecurityFilterChain");
                innerField.setAccessible(true);
                realFcp = (FilterChainProxy) innerField.get(fcp);
            } catch (NoSuchFieldException ignored) {
                // Not a CompositeFilterChainProxy — work with fcp directly.
                realFcp = fcp;
            }

            // metadata chain at 0 (checked first — open), auth chain at 1 (everything else).
            java.lang.reflect.Field filterChainsField = FilterChainProxy.class.getDeclaredField("filterChains");
            filterChainsField.setAccessible(true);
            List<SecurityFilterChain> updated = new ArrayList<>(realFcp.getFilterChains());
            updated.add(0, authChain);
            updated.add(0, metadataChain);
            filterChainsField.set(realFcp, updated);

            log.info("FhirServletRegistrar: /fhir/* secured using console authentication ({}); /metadata is public",
                    authenticator.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("FhirServletRegistrar: failed to apply console security to /fhir/** — endpoints will be unprotected", e);
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

    /**
     * Returns {@code true} when the request targets a FHIR {@code /metadata} endpoint.
     *
     * <p>Two URL layouts arise depending on whether a {@link FhirFacadeServlet} is already
     * registered for the facade:</p>
     * <ul>
     *   <li><b>Servlet registered</b>: Tomcat splits the URL — {@code servletPath} holds the
     *       servlet prefix and {@code pathInfo} is {@code /metadata}.</li>
     *   <li><b>No servlet yet</b>: Tomcat's DefaultServlet handles the URL — the full path is
     *       in {@code servletPath} and {@code pathInfo} is {@code null}.</li>
     * </ul>
     * <p>Both cases are handled by concatenating the two parts and checking whether the
     * combined path starts with {@code /fhir/} and ends with {@code /metadata}.</p>
     */
    static boolean isPublicFhirMetadataPath(HttpServletRequest req) {
        String path = req.getServletPath() + (req.getPathInfo() != null ? req.getPathInfo() : "");
        return path.startsWith("/fhir/") && path.endsWith("/metadata");
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
