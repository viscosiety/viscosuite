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

import java.net.URI;
import java.net.URISyntaxException;

import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

/**
 * Lets a browser that is logged in through the console's OIDC flow call {@code /api/*} without a
 * second login.
 *
 * <p>The tenant-facing API chain ({@code application.security.http.authenticators.tenantApi}, HTTP
 * Basic for external API users) is stateless: it never looks at the HTTP session, so a
 * {@code fetch('/api/...')} from a hosted webcontent page answers {@code 401 WWW-Authenticate: Basic}
 * and the browser pops its native credentials dialog -- even though the same session already holds
 * the user's Keycloak login from the console/webcontent chain. A stateless Frank!Framework chain
 * reads its {@link SecurityContext} from a request attribute
 * ({@link RequestAttributeSecurityContextRepository}); this listener copies the session's OIDC
 * context into that attribute for same-origin {@code /api/*} requests, so the API chain sees an
 * already-authenticated principal and never issues the Basic challenge. External callers are
 * untouched: a request carrying an {@code Authorization} header, or without an OIDC session, is
 * left entirely to the chain's own Basic authentication.</p>
 *
 * <p>A {@link ServletRequestListener} rather than a filter on purpose: Frank!Framework registers
 * Spring Security's filter programmatically with {@code isMatchAfter=false} at container-init
 * time, so no filter this WAR can add runs ahead of it, whereas request-initialized events fire
 * before the filter pipeline starts.</p>
 *
 * <p>Bridging a cookie session onto an API is a CSRF surface, so it is limited to same-origin
 * requests: {@code Sec-Fetch-Site} must be {@code same-origin} (or {@code none}, a user-initiated
 * navigation); browsers without that header are accepted only when their {@code Origin} (or, for
 * header-less GETs, {@code Referer}) matches the request's own origin. A sibling tenant subdomain
 * is {@code same-site}, not {@code same-origin}, and is refused.</p>
 */
public final class ApiSessionAuthListener implements ServletRequestListener {

    private static final Logger log = LogManager.getLogger(ApiSessionAuthListener.class);

    @Override
    public void requestInitialized(ServletRequestEvent event) {
        if (!(event.getServletRequest() instanceof HttpServletRequest request)) {
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!isApiPath(path) || request.getHeader("Authorization") != null) {
            return;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        Object stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(stored instanceof SecurityContext context) || !isOidcLogin(context.getAuthentication())) {
            return;
        }
        if (!isSameOrigin(request)) {
            log.debug("not bridging OIDC session onto [{}]: request is not same-origin", path);
            return;
        }
        request.setAttribute(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME, context);
        log.debug("bridged OIDC session of [{}] onto [{}]", context.getAuthentication().getName(), path);
    }

    /** {@code /api} and everything below it -- the ApiListenerServlet mapping. */
    static boolean isApiPath(String path) {
        return "/api".equals(path) || path.startsWith("/api/");
    }

    /** Only an interactive OIDC login is bridged, never a Basic or bearer principal from another chain. */
    static boolean isOidcLogin(Authentication authentication) {
        return authentication instanceof OAuth2AuthenticationToken && authentication.isAuthenticated();
    }

    static boolean isSameOrigin(HttpServletRequest request) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null) {
            return "same-origin".equalsIgnoreCase(fetchSite) || "none".equalsIgnoreCase(fetchSite);
        }
        String own = originOf(request);
        String origin = request.getHeader("Origin");
        if (origin != null) {
            return origin.equalsIgnoreCase(own);
        }
        String referer = request.getHeader("Referer");
        return referer != null && originOf(referer) != null && originOf(referer).equalsIgnoreCase(own);
    }

    private static String originOf(HttpServletRequest request) {
        StringBuilder origin = new StringBuilder(request.getScheme()).append("://").append(request.getServerName());
        int port = request.getServerPort();
        boolean defaultPort = ("https".equalsIgnoreCase(request.getScheme()) && port == 443)
                || ("http".equalsIgnoreCase(request.getScheme()) && port == 80);
        if (port > 0 && !defaultPort) {
            origin.append(':').append(port);
        }
        return origin.toString();
    }

    private static String originOf(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            boolean defaultPort = uri.getPort() == -1
                    || ("https".equalsIgnoreCase(uri.getScheme()) && uri.getPort() == 443)
                    || ("http".equalsIgnoreCase(uri.getScheme()) && uri.getPort() == 80);
            return uri.getScheme() + "://" + uri.getHost() + (defaultPort ? "" : ":" + uri.getPort());
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
