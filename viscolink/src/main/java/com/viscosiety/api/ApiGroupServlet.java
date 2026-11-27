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

package com.viscosiety.api;

import java.io.IOException;
import java.io.Serial;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import com.viscosiety.tunnel.TunnelOnlyApiFilter;

import org.frankframework.http.rest.ApiDispatchConfig;
import org.frankframework.http.rest.ApiListener;
import org.frankframework.http.rest.ApiListenerServlet;
import org.frankframework.http.rest.ApiServiceDispatcher;

/**
 * The REST endpoints of one group ({@code /runtime/api/<group>/...}, rewritten in place by the image's routing to
 * this servlet's mapping {@code /api-group/<group>/*}) under the group's own login
 * ({@code servlet.api-<group>.authenticator}, {@code .securityRoles}). It serves the same listeners as {@code /api/*}:
 * the dispatcher sees the full path {@code /<group>/...}, so a listener keeps its own uriPattern, and only patterns
 * whose first segment is the group's name are served (see {@link #check}). Not an
 * {@code @IbisInitializer}: {@link org.frankframework.visco.security.ApiGroupServletRegistrar} creates one per listed
 * group. It lives outside {@code org.frankframework} because {@code @IbisInitializer} is inherited from
 * {@code ApiListenerServlet} and a scanned subclass with a constructor argument stops the whole runtime from starting.
 */
public class ApiGroupServlet extends ApiListenerServlet {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String group;

    public ApiGroupServlet(String group) {
        this.group = group;
    }

    @Override
    public String getName() {
        return "api-" + group;
    }

    @Override
    public String getUrlMapping() {
        return "/api-group/" + group + "/*";
    }

    static String pathFor(String group, String pathInfo) {
        if (pathInfo == null || pathInfo.equals("/")) {
            return "/" + group;
        }
        return "/" + group + pathInfo;
    }

    /** What the group servlet does with a request before (or instead of) the parent's own handling. */
    enum Action { PASS, NOT_FOUND, OPENAPI_NOT_FOUND, PATTERN_OPENAPI }

    /** The action plus, for PASS, the tunnel-only methods to keep out of the headers and, for PATTERN_OPENAPI, the pattern. */
    record Check(Action action, Set<String> hiddenMethods, ApiDispatchConfig config) {
        static Check of(Action action) {
            return new Check(action, Set.of(), null);
        }
    }

    /**
     * The lookup the parent will do, made first, so the group's login only ever reaches the group's own endpoints.
     * The dispatcher matches {@code {param}} as a one-segment wildcard and {@code **} as a catch-all, so without this
     * check a default listener {@code {tenant}/orders} or {@code **} would also be found for {@code /<group>/...} and
     * run under this servlet's (possibly weaker) login. A pattern found here whose first segment is not literally the
     * group's name answers 404 with an empty body, as for a path no listener has; its per-pattern OpenAPI form answers
     * the servlet's own "not found". Tunnel-only endpoints are refused the same way (this servlet only serves the
     * public port; {@code TunnelOnlyApiFilter} is mapped to the default servlet only), and their methods are left out
     * of {@code Allow} / {@code Access-Control-Allow-Methods}.
     *
     * <p>The method is parsed and one trailing slash removed as the parent does; an unknown method or no pattern at
     * all passes (the parent answers 405 or 404 without running anything). When the pattern found has no listener for
     * the method, the parent answers 405 listing that pattern's methods only, so nothing of another pattern shows. A
     * configuration reload between this lookup and the parent's is the same race {@code TunnelOnlyApiFilter} accepts;
     * the listener's own checks stay the boundary for tunnel-only endpoints.
     */
    static Check check(String group, String method, String fullPath) {
        ApiListener.HttpMethod httpMethod = TunnelOnlyApiFilter.parseMethod(method);
        String uri = TunnelOnlyApiFilter.cleanUri(fullPath);
        if (httpMethod == null || uri == null) {
            return Check.of(Action.PASS);
        }
        ApiServiceDispatcher dispatcher = ApiServiceDispatcher.getInstance();
        if (uri.endsWith("openapi.json")) {
            ApiDispatchConfig config = dispatcher.findExactMatchingConfigForUri(uri.substring(0, uri.lastIndexOf('/')));
            if (config == null || !isMine(group, config) || TunnelOnlyApiFilter.hasTunnelListener(config)) {
                return Check.of(Action.OPENAPI_NOT_FOUND);
            }
            return new Check(Action.PATTERN_OPENAPI, Set.of(), config);
        }
        ApiDispatchConfig config = dispatcher.findConfigForRequest(httpMethod, uri);
        if (config == null) {
            return Check.of(Action.PASS);
        }
        if (!isMine(group, config)
                || TunnelOnlyApiFilter.decideForConfig(config, httpMethod) == TunnelOnlyApiFilter.Decision.NOT_FOUND) {
            return Check.of(Action.NOT_FOUND);
        }
        return new Check(Action.PASS, TunnelOnlyApiFilter.tunnelMethodsOf(config), null);
    }

    private static boolean isMine(String group, ApiDispatchConfig config) {
        return group.equals(ApiGroupOpenApi.firstSegment(config.getUriPattern()));
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String pathInfo = request.getPathInfo();
        if ("/openapi.json".equalsIgnoreCase(TunnelOnlyApiFilter.cleanUri(pathInfo))) {
            ApiGroupOpenApi.write(response, group, request);
            return;
        }
        String full = pathFor(group, pathInfo);
        Check check = check(group, request.getMethod(), full);
        switch (check.action()) {
            case NOT_FOUND -> response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            case OPENAPI_NOT_FOUND -> response.sendError(HttpServletResponse.SC_NOT_FOUND, TunnelOnlyApiFilter.OPENAPI_NOT_FOUND_MESSAGE);
            case PATTERN_OPENAPI -> ApiGroupOpenApi.writePattern(response, check.config(), request);
            default -> {
                HttpServletResponse out = check.hiddenMethods().isEmpty()
                        ? response : new TunnelOnlyApiFilter.TunnelMethodHidingResponse(response, check.hiddenMethods());
                super.service(new HttpServletRequestWrapper(request) {
                    @Override
                    public String getPathInfo() {
                        return full;
                    }
                }, out);
            }
        }
    }

    /** The parent clears the listener table it shares with the public servlet; that one clears it at shutdown. */
    @Override
    public void destroy() {
        // nothing of its own to release
    }
}
