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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonWriter;
import jakarta.json.stream.JsonGenerator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frankframework.http.openapi.OpenApiGenerator;
import org.frankframework.http.rest.ApiDispatchConfig;
import org.frankframework.http.rest.ApiServiceDispatcher;

/** The OpenAPI document of one group, and the test the /api document uses to leave group endpoints out. */
public final class ApiGroupOpenApi {

    private ApiGroupOpenApi() {
    }

    public static List<ApiDispatchConfig> configsOf(String group, Collection<ApiDispatchConfig> configs) {
        List<ApiDispatchConfig> mine = new ArrayList<>();
        for (ApiDispatchConfig config : configs) {
            if (group.equals(firstSegment(config.getUriPattern()))) {
                mine.add(config);
            }
        }
        return mine;
    }

    public static boolean inAnyGroup(ApiDispatchConfig config) {
        String first = firstSegment(config.getUriPattern());
        return first != null && ApiGroups.names().contains(first);
    }

    /** Server URL {@code <scheme>://<host>/runtime/api}: what callers use, never the internal /api-group path. */
    public static void write(HttpServletResponse response, String group, HttpServletRequest request) throws IOException {
        String endpoint = request.getScheme() + "://" + request.getServerName()
                + portPart(request) + request.getContextPath() + "/api";
        JsonObject json = OpenApiGenerator.generateOpenApiJsonSchema(
                configsOf(group, ApiServiceDispatcher.getInstance().getPatternClients().values()), endpoint);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Content-Type", "application/json");
        try (JsonWriter writer = Json.createWriterFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true))
                .createWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(json);
        }
    }

    private static String portPart(HttpServletRequest request) {
        int port = request.getServerPort();
        boolean standard = ("http".equals(request.getScheme()) && port == 80) || ("https".equals(request.getScheme()) && port == 443);
        return standard || port <= 0 ? "" : ":" + port;
    }

    static String firstSegment(String uriPattern) {
        if (uriPattern == null) {
            return null;
        }
        String rest = uriPattern.startsWith("/") ? uriPattern.substring(1) : uriPattern;
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
