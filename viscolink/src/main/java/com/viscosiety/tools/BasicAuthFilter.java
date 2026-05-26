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

package com.viscosiety.tools;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.frankframework.util.AppConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@WebFilter("/*")
public class BasicAuthFilter implements Filter {

    private static final String REALM = "ViscoLink";

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        // Use getRequestURI() minus context path — getServletPath() returns "" for /*-mapped servlets.
        String ctx  = req.getContextPath();
        String path = req.getRequestURI().substring(ctx.length());

        // F!F owns /iaf/ and /api/; FHIR facade is authenticated by its own layer.
        // /tools/health is a public liveness probe used by the ViscoSuite welcome page.
        if (path.startsWith("/iaf/") || path.startsWith("/api/") || path.startsWith("/fhir/")
                || path.equals("/tools/health")) {
            chain.doFilter(request, response);
            return;
        }

        if (isAuthorized(req)) {
            chain.doFilter(request, response);
        } else {
            resp.setHeader("WWW-Authenticate", "Basic realm=\"" + REALM + "\"");
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    private boolean isAuthorized(HttpServletRequest req) {
        AppConstants props = AppConstants.getInstance();
        String expectedUser = props.getProperty("application.security.console.authentication.username", "");
        // No credentials configured (LOC / CI) — auth disabled, pass through.
        if (expectedUser.isEmpty()) return true;

        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Basic ")) return false;

        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }

        int colon = decoded.indexOf(':');
        if (colon < 0) return false;
        String username = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);

        String expectedPass = props.getProperty("application.security.console.authentication.password", "");
        return expectedUser.equals(username) && expectedPass.equals(password);
    }

    @Override
    public void destroy() {}
}
