/*
 * Copyright 2026 Viscosiety B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.viscosiety.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.context.WebApplicationContext;

/**
 * Secures the ViscoLink tool pages (everything the WAR serves that is not owned by F!F or the FHIR
 * facade) using F!F's <b>own</b> console authentication — so the tools authenticate exactly like the
 * Frank!Console does: a browser Basic prompt when the console is configured {@code IN_MEMORY}, an
 * OIDC redirect when it is {@code OAUTH2}, and open access when it is {@code NONE} (e.g. the LOC
 * stage). It replaces the former hand-rolled {@code BasicAuthFilter}.
 *
 * <h3>Why reuse, not rebuild</h3>
 * <p>Standing up a second authenticator from {@code application.security.console.authentication.*}
 * fails for OAuth: {@code OAuth2Authenticator} registers a singleton bean {@code
 * clientRegistrationRepository}, and F!F's console authenticator already registered it — the second
 * registration throws. Reusing F!F's already-built console {@link SecurityFilterChain} avoids that,
 * shares the same client/registration, and shares the HTTP session — giving true single-sign-on with
 * {@code /iaf}.</p>
 *
 * <h3>How</h3>
 * <p>At the first tool request we locate F!F's console chain (the registered {@link
 * SecurityFilterChain} that matches a synthetic {@code /iaf/gui/} request), keep its
 * <em>authentication</em> filters, drop its {@code /iaf}-scoped {@link AuthorizationFilter}, and
 * append our own {@link ToolAuthorizationFilter} that requires an authenticated principal for tool
 * paths (exempting {@code /tools/health}). The upstream {@code ExceptionTranslationFilter} — kept
 * from F!F's chain — turns our {@link AccessDeniedException} into F!F's own entry point (the Basic
 * challenge or the OIDC redirect). The whole thing runs as a plain Tomcat filter, mapped like the
 * old {@code BasicAuthFilter} to {@code /*} but only enforcing on tool paths — reflection-free, per
 * the FHIR-facade pattern.</p>
 */
public class ConsoleSecurityRegistrar implements InitializingBean, ApplicationContextAware {

    private static final Logger log = LogManager.getLogger(ConsoleSecurityRegistrar.class);

    private static final String CONSOLE_AUTH_PREFIX = "application.security.console.authentication.";
    /** Public liveness endpoint — must stay reachable without authentication. */
    static final String PUBLIC_HEALTH_PATH = "/tools/health";

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        ServletContext servletContext = findServletContext(applicationContext);
        ApplicationContext parentCtx = applicationContext.getParent();
        if (servletContext == null || parentCtx == null) {
            log.warn("ConsoleSecurityRegistrar: no ServletContext/parent context — tool pages will be unprotected");
            return;
        }

        // Mirror F!F: when the console requires no authentication (NONE, or web security disabled as
        // in the LOC stage) the tools stay open too. Only enforce when the console itself does.
        String type = parentCtx.getEnvironment().getProperty(CONSOLE_AUTH_PREFIX + "type");
        if (type == null || type.isBlank() || "NONE".equalsIgnoreCase(type)) {
            log.info("ConsoleSecurityRegistrar: console authentication is [{}] — tool pages left open (mirrors F!F)",
                    type == null ? "unset" : type);
            return;
        }

        // On a Frank!Framework configuration reload only the child IbisApplicationContext is
        // recreated, so this bean runs again while the WAR's ServletContext is already initialised.
        // The filter registered on the first boot persists (and F!F's console chain lives in the
        // surviving parent context), so re-registration is neither possible nor needed — skip it.
        // Not skipping means servletContext.addFilter throws IllegalStateException, which would fail
        // this bean and abort the whole reload.
        if (servletContext.getFilterRegistration("consoleToolSecurity") != null) {
            log.info("ConsoleSecurityRegistrar: consoleToolSecurity filter already registered (config reload) — keeping it");
            return;
        }
        try {
            Filter filter = new ConsoleReuseSecurityFilter(parentCtx);
            FilterRegistration.Dynamic reg = servletContext.addFilter("consoleToolSecurity", filter);
            if (reg != null) {
                reg.setAsyncSupported(true);
                reg.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/*");
                log.info("ConsoleSecurityRegistrar: tool pages secured with F!F console authentication ([{}]); {} is public",
                        type, PUBLIC_HEALTH_PATH);
            } else {
                log.debug("ConsoleSecurityRegistrar: consoleToolSecurity filter already registered");
            }
        } catch (IllegalStateException e) {
            // ServletContext already initialised (e.g. an edge-case reload) — the existing filter
            // stays in place; do not fail the context refresh.
            log.info("ConsoleSecurityRegistrar: ServletContext already initialised — keeping existing console security ({})",
                    e.getMessage());
        }
    }

    /**
     * Path (within the context) is owned by F!F, the FHIR facade, or another endpoint that secures
     * itself independently — never touched by this filter. {@code /api-service/} is
     * {@link org.frankframework.visco.security.ReloadConfigurationServlet}'s Bearer-only reload
     * endpoint: it enforces its own JWT-based auth and must never also be gated by this class's
     * session-based tool-page check.
     */
    static boolean isFrankOwnedPath(String path) {
        return path.startsWith("/iaf/") || path.startsWith("/api/") || path.startsWith("/fhir/")
                || path.startsWith("/api-service/");
    }

    private static ServletContext findServletContext(ApplicationContext ctx) {
        if (ctx instanceof WebApplicationContext wac) {
            return wac.getServletContext();
        }
        ApplicationContext parent = ctx.getParent();
        return parent != null ? findServletContext(parent) : null;
    }

    /**
     * Tomcat filter that, for tool paths only, runs F!F's console authentication filters plus our
     * authorization. F!F-owned paths and the public health endpoint pass straight through.
     */
    static final class ConsoleReuseSecurityFilter implements Filter {

        private final ApplicationContext parentCtx;
        private volatile FilterChainProxy delegate; // built lazily once F!F's console chain is known

        ConsoleReuseSecurityFilter(ApplicationContext parentCtx) {
            this.parentCtx = parentCtx;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            String path = req.getRequestURI().substring(req.getContextPath().length());

            // F!F/FHIR own their own security; the health probe is public — never gate these.
            if (isFrankOwnedPath(path) || PUBLIC_HEALTH_PATH.equals(path)) {
                chain.doFilter(request, response);
                return;
            }

            FilterChainProxy d = delegate;
            if (d == null) {
                d = buildDelegate(req);
            }
            if (d == null) {
                // Could not locate F!F's console chain — fail closed rather than expose tools.
                ((jakarta.servlet.http.HttpServletResponse) response)
                        .sendError(jakarta.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                                "console security not initialised");
                return;
            }
            d.doFilter(request, response, chain);
        }

        private synchronized FilterChainProxy buildDelegate(HttpServletRequest sample) {
            if (delegate != null) {
                return delegate;
            }
            SecurityFilterChain consoleChain = findConsoleChain(sample);
            if (consoleChain == null) {
                log.error("ConsoleSecurityRegistrar: could not locate F!F console SecurityFilterChain — tool pages will be denied");
                return null;
            }
            // Keep F!F's authentication filters; drop its /iaf-scoped AuthorizationFilter; append ours.
            List<Filter> filters = new ArrayList<>();
            for (Filter f : consoleChain.getFilters()) {
                if (!(f instanceof AuthorizationFilter)) {
                    filters.add(f);
                }
            }
            filters.add(new ToolAuthorizationFilter());
            SecurityFilterChain toolChain = new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, filters);
            delegate = new FilterChainProxy(toolChain);
            log.info("ConsoleSecurityRegistrar: reusing F!F console chain [{} filters] for tool pages",
                    consoleChain.getFilters().size());
            return delegate;
        }

        /** Find the registered console chain by matching a synthetic {@code /iaf/gui/} request. */
        private SecurityFilterChain findConsoleChain(HttpServletRequest sample) {
            HttpServletRequest probe = new PathOverrideRequest(sample, "/iaf/gui/");
            for (ApplicationContext c = parentCtx; c != null; c = c.getParent()) {
                for (SecurityFilterChain chain : c.getBeansOfType(SecurityFilterChain.class).values()) {
                    if (chain.matches(probe)) {
                        return chain;
                    }
                }
            }
            return null;
        }
    }

    /**
     * Requires an authenticated (non-anonymous) principal for every request it sees. Positioned after
     * F!F's authentication filters and after F!F's {@code ExceptionTranslationFilter}. On failure it
     * throws an {@link InsufficientAuthenticationException} (an {@code AuthenticationException}), which
     * {@code ExceptionTranslationFilter} always routes to F!F's authentication entry point — the Basic
     * challenge or the OIDC redirect. (An {@code AccessDeniedException} would instead yield a bare 403
     * here, because F!F's console chain has anonymous access disabled, so the principal is null rather
     * than an anonymous token.)
     */
    static final class ToolAuthorizationFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            boolean authenticated = auth != null && auth.isAuthenticated()
                    && !(auth instanceof AnonymousAuthenticationToken);
            if (!authenticated) {
                throw new InsufficientAuthenticationException("authentication required for ViscoLink tools");
            }
            chain.doFilter(request, response);
        }
    }

    /** Presents an overridden path to F!F's request matchers so we can identify the console chain. */
    private static final class PathOverrideRequest extends HttpServletRequestWrapper {
        private final String path;

        PathOverrideRequest(HttpServletRequest request, String path) {
            super(request);
            this.path = path;
        }

        @Override
        public String getServletPath() {
            return path;
        }

        @Override
        public String getPathInfo() {
            return null;
        }

        @Override
        public String getRequestURI() {
            return getContextPath() + path;
        }
    }
}
