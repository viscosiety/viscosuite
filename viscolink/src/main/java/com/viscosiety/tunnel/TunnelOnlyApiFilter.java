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

package com.viscosiety.tunnel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonWriter;
import jakarta.json.stream.JsonGenerator;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frankframework.http.openapi.OpenApiGenerator;
import org.frankframework.http.rest.ApiDispatchConfig;
import org.frankframework.http.rest.ApiListener;
import org.frankframework.http.rest.ApiServiceDispatcher;
import org.frankframework.util.EnumUtils;
import org.frankframework.util.HttpUtils;

/**
 * Hides tunnel-only endpoints on the public {@code /api/*}: a request whose listener is a {@link TunnelApiListener}
 * gets 404 with an empty body, the answer Frank!Framework gives for a path no listener has. The OpenAPI document
 * ({@code /api/openapi.json}) is answered here without the patterns that carry a tunnel listener, and the per-pattern
 * forms of that document answer 404 for such a pattern.
 *
 * <p>Mapped for the REQUEST dispatch only: the platform's own gateway forwards to {@code /api} and still reaches
 * tunnel-only endpoints. The lookup is the one {@code ApiListenerServlet.service} does (same method parsing, same path
 * info with one trailing slash removed, same dispatcher), so both always agree on which listener a request reaches.
 */
public class TunnelOnlyApiFilter implements Filter {

    /** What the filter does with a request. */
    public enum Decision { PASS, NOT_FOUND, PUBLIC_OPENAPI }

    private static final String OPENAPI = "/openapi.json";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request) || !(servletResponse instanceof HttpServletResponse response)) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }
        // getParameter can read a form body, so it is only asked for on the OpenAPI path, as ApiListenerServlet does.
        String pathInfo = request.getPathInfo();
        String uriParameter = isOpenApiPath(pathInfo) ? request.getParameter("uri") : null;
        Decision decision = decide(request.getMethod(), pathInfo, uriParameter);
        switch (decision) {
            case NOT_FOUND -> response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            case PUBLIC_OPENAPI -> writeJson(response, publicOpenApi(endpointUrl(request)));
            default -> chain.doFilter(request, response);
        }
    }

    /**
     * The decision for a request to {@code /api/*} with the given method, path info (relative to {@code /api}) and
     * {@code uri} query parameter (null when absent).
     */
    public static Decision decide(String method, String pathInfo, String uriParameter) {
        if (pathInfo == null || pathInfo.isEmpty()) {
            return Decision.PASS;
        }
        String uri = pathInfo.endsWith("/") ? pathInfo.substring(0, pathInfo.length() - 1) : pathInfo;
        if (uri.isEmpty()) {
            return Decision.PASS;
        }
        ApiServiceDispatcher dispatcher = ApiServiceDispatcher.getInstance();

        if (OPENAPI.equalsIgnoreCase(uri)) {
            if (uriParameter == null) {
                return anyTunnelListener(dispatcher.getPatternClients().values()) ? Decision.PUBLIC_OPENAPI : Decision.PASS;
            }
            return hasTunnelListener(dispatcher.findExactMatchingConfigForUri(uriParameter)) ? Decision.NOT_FOUND : Decision.PASS;
        }
        if (uri.endsWith("openapi.json")) {
            String pattern = uri.substring(0, uri.lastIndexOf('/'));
            return hasTunnelListener(dispatcher.findExactMatchingConfigForUri(pattern)) ? Decision.NOT_FOUND : Decision.PASS;
        }

        ApiListener.HttpMethod httpMethod;
        try {
            httpMethod = EnumUtils.parse(ApiListener.HttpMethod.class, method);
        } catch (IllegalArgumentException e) {
            return Decision.PASS;
        }
        ApiDispatchConfig config = dispatcher.findConfigForRequest(httpMethod, uri);
        if (config == null) {
            return Decision.PASS;
        }
        ApiListener listener = config.getApiListener(httpMethod);
        if (listener != null) {
            return listener instanceof TunnelApiListener ? Decision.NOT_FOUND : Decision.PASS;
        }
        return onlyTunnelListeners(config) ? Decision.NOT_FOUND : Decision.PASS;
    }

    private static boolean isOpenApiPath(String pathInfo) {
        if (pathInfo == null) {
            return false;
        }
        String uri = pathInfo.endsWith("/") ? pathInfo.substring(0, pathInfo.length() - 1) : pathInfo;
        return OPENAPI.equalsIgnoreCase(uri);
    }

    /** The OpenAPI document of every pattern that has no tunnel listener at all. */
    public static JsonObject publicOpenApi(String endpoint) {
        List<ApiDispatchConfig> publicConfigs = new ArrayList<>();
        for (ApiDispatchConfig config : ApiServiceDispatcher.getInstance().getPatternClients().values()) {
            if (!hasTunnelListener(config)) {
                publicConfigs.add(config);
            }
        }
        return OpenApiGenerator.generateOpenApiJsonSchema(publicConfigs, endpoint);
    }

    private static boolean anyTunnelListener(Collection<ApiDispatchConfig> configs) {
        for (ApiDispatchConfig config : configs) {
            if (hasTunnelListener(config)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTunnelListener(ApiDispatchConfig config) {
        if (config == null) {
            return false;
        }
        for (ApiListener.HttpMethod m : config.getMethods()) {
            if (config.getApiListener(m) instanceof TunnelApiListener) {
                return true;
            }
        }
        return false;
    }

    private static boolean onlyTunnelListeners(ApiDispatchConfig config) {
        boolean any = false;
        for (ApiListener.HttpMethod m : config.getMethods()) {
            ApiListener listener = config.getApiListener(m);
            if (listener == null) {
                continue;
            }
            if (!(listener instanceof TunnelApiListener)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** As {@code ApiListenerServlet.createEndpointUrlFromRequest}: the decoded request URL up to the path info. */
    static String endpointUrl(HttpServletRequest request) {
        String requestUrl = HttpUtils.urlDecode(request.getRequestURL().toString());
        String pathInfo = request.getPathInfo();
        int index = pathInfo == null ? -1 : requestUrl.indexOf(pathInfo);
        return index < 0 ? "" : requestUrl.substring(0, index);
    }

    private static void writeJson(HttpServletResponse response, JsonObject json) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Content-Type", "application/json");
        try (JsonWriter writer = Json.createWriterFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true))
                .createWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(json);
        }
    }
}
