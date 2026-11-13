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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import jakarta.servlet.http.HttpServletResponseWrapper;

import org.frankframework.http.openapi.OpenApiGenerator;
import org.frankframework.http.rest.ApiDispatchConfig;
import org.frankframework.http.rest.ApiListener;
import org.frankframework.http.rest.ApiServiceDispatcher;
import org.frankframework.util.EnumUtils;
import org.frankframework.util.HttpUtils;

/**
 * The clean-404 and OpenAPI layer for tunnel-only endpoints on the public {@code /api}: a request whose listener is a
 * {@link TunnelApiListener} gets 404 with an empty body, the answer Frank!Framework gives for a path no listener has.
 * The OpenAPI document ({@code /api/openapi.json}) is answered here without the patterns that carry a tunnel listener,
 * the per-pattern forms of that document answer exactly as for a pattern that does not exist, and for a pattern that
 * mixes public and tunnel-only methods the {@code Allow} and {@code Access-Control-Allow-Methods} headers leave the
 * tunnel-only methods out.
 *
 * <p>The boundary itself is the listener's own check ({@link TunnelApiListener#processRequest}): this filter's lookup
 * is separate from the servlet's (for example during a configuration start or reload), so it is not relied on alone.
 * An exception in the lookup propagates: the container answers 500 and the servlet is not reached (fails closed).
 *
 * <p>Mapped to the {@code ApiListenerServlet} by servlet name, for the REQUEST dispatch only: the platform's own
 * gateway forwards to {@code /api} and still reaches tunnel-only endpoints. The lookup is the one
 * {@code ApiListenerServlet.service} does (same method parsing, same path info with one trailing slash removed, same
 * dispatcher).
 */
public class TunnelOnlyApiFilter implements Filter {

    /** What the filter does with a request. */
    public enum Decision { PASS, NOT_FOUND, OPENAPI_NOT_FOUND, PUBLIC_OPENAPI }

    private static final String OPENAPI = "/openapi.json";
    /** The servlet's own answer for an OpenAPI document of a pattern that does not exist. */
    static final String OPENAPI_NOT_FOUND_MESSAGE = "OpenApi specification not found";

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
            case OPENAPI_NOT_FOUND -> response.sendError(HttpServletResponse.SC_NOT_FOUND, OPENAPI_NOT_FOUND_MESSAGE);
            case PUBLIC_OPENAPI -> writeJson(response, publicOpenApi(endpointUrl(request)));
            default -> {
                Set<String> hidden = tunnelMethods(request.getMethod(), pathInfo);
                chain.doFilter(request, hidden.isEmpty() ? response : new TunnelMethodHidingResponse(response, hidden));
            }
        }
    }

    /**
     * The tunnel-only methods of the pattern a request to {@code /api/*} reaches (empty when none, or when the request is
     * an OpenAPI request or has an unknown method). Used to keep them out of the {@code Allow} headers on PASS.
     */
    static Set<String> tunnelMethods(String method, String pathInfo) {
        ApiListener.HttpMethod httpMethod = parseMethod(method);
        String uri = cleanUri(pathInfo);
        if (httpMethod == null || uri == null || uri.endsWith("openapi.json") || OPENAPI.equalsIgnoreCase(uri)) {
            return Set.of();
        }
        ApiDispatchConfig config = ApiServiceDispatcher.getInstance().findConfigForRequest(httpMethod, uri);
        if (config == null) {
            return Set.of();
        }
        Set<String> hidden = new HashSet<>();
        for (ApiListener.HttpMethod m : config.getMethods()) {
            if (config.getApiListener(m) instanceof TunnelApiListener) {
                hidden.add(m.name());
            }
        }
        return hidden;
    }

    /** A comma-separated list of methods without the hidden ones (compared case-insensitively). */
    static String withoutMethods(String value, Set<String> hidden) {
        if (value == null) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String part : value.split(",")) {
            String method = part.trim();
            if (method.isEmpty()) {
                continue;
            }
            boolean drop = false;
            for (String h : hidden) {
                if (h.equalsIgnoreCase(method)) {
                    drop = true;
                    break;
                }
            }
            if (!drop) {
                kept.add(method);
            }
        }
        // Nothing left: null, so no empty header is written (Tomcat ignores a null value).
        return kept.isEmpty() ? null : String.join(", ", kept);
    }

    /** Leaves the tunnel-only methods out of the {@code Allow} and {@code Access-Control-Allow-Methods} headers. */
    static final class TunnelMethodHidingResponse extends HttpServletResponseWrapper {
        private final Set<String> hidden;

        TunnelMethodHidingResponse(HttpServletResponse response, Set<String> hidden) {
            super(response);
            this.hidden = Set.copyOf(hidden);
        }

        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, filtered(name, value));
        }

        @Override
        public void addHeader(String name, String value) {
            super.addHeader(name, filtered(name, value));
        }

        private String filtered(String name, String value) {
            if ("Allow".equalsIgnoreCase(name) || "Access-Control-Allow-Methods".equalsIgnoreCase(name)) {
                return withoutMethods(value, hidden);
            }
            return value;
        }
    }

    /**
     * The decision for a request to {@code /api/*} with the given method, path info (relative to {@code /api}) and
     * {@code uri} query parameter (null when absent).
     */
    public static Decision decide(String method, String pathInfo, String uriParameter) {
        // As the servlet: an unknown method is answered 405 before any path is looked at.
        ApiListener.HttpMethod httpMethod = parseMethod(method);
        if (httpMethod == null) {
            return Decision.PASS;
        }
        String uri = cleanUri(pathInfo);
        if (uri == null) {
            return Decision.PASS;
        }
        ApiServiceDispatcher dispatcher = ApiServiceDispatcher.getInstance();

        if (OPENAPI.equalsIgnoreCase(uri)) {
            if (uriParameter == null) {
                return anyTunnelListener(dispatcher.getPatternClients().values()) ? Decision.PUBLIC_OPENAPI : Decision.PASS;
            }
            return hasTunnelListener(dispatcher.findExactMatchingConfigForUri(uriParameter))
                    ? Decision.OPENAPI_NOT_FOUND : Decision.PASS;
        }
        if (uri.endsWith("openapi.json")) {
            String pattern = uri.substring(0, uri.lastIndexOf('/'));
            return hasTunnelListener(dispatcher.findExactMatchingConfigForUri(pattern)) ? Decision.OPENAPI_NOT_FOUND : Decision.PASS;
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

    private static ApiListener.HttpMethod parseMethod(String method) {
        try {
            return EnumUtils.parse(ApiListener.HttpMethod.class, method);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Path info with one trailing slash removed; null when nothing is left. */
    private static String cleanUri(String pathInfo) {
        if (pathInfo == null || pathInfo.isEmpty()) {
            return null;
        }
        String uri = pathInfo.endsWith("/") ? pathInfo.substring(0, pathInfo.length() - 1) : pathInfo;
        return uri.isEmpty() ? null : uri;
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
