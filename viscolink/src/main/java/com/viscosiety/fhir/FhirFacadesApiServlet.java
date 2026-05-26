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

package com.viscosiety.fhir;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Comparator;
import java.util.Map;

/**
 * Serves all registered FHIR facades as a JSON array for consumption by the
 * script injected into the F!F console.
 *
 * <p>Registered at {@code /iaf/api/fhir-facades} by {@link FhirServletRegistrar}. Sitting under
 * {@code /iaf/api/**} means it falls within F!F's console {@code SecurityFilterChain} scope and
 * is protected by whatever authentication mechanism is configured there — no custom filter needed.
 * Tomcat's exact-path-match rule routes requests for this specific URL to this servlet rather than
 * to the console backend's {@code DispatcherServlet} wildcard mapping.</p>
 *
 * <p>Each entry includes: {@code fhirVersion}, {@code facadeName}, {@code resourceType},
 * {@code operation}, and — for proxy operations — {@code proxyCdrBaseUrl}.</p>
 */
class FhirFacadesApiServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonArrayBuilder array = Json.createArrayBuilder();

        FhirOperationRegistry.getAllRegistrations().entrySet().stream()
                .sorted(Comparator.<Map.Entry<FhirOperation, FhirListener>, String>comparing(e -> e.getKey().fhirVersion())
                        .thenComparing(e -> e.getKey().facadeName())
                        .thenComparing(e -> e.getKey().resourceType())
                        .thenComparing(e -> e.getKey().operation()))
                .forEach(e -> {
                    FhirOperation op = e.getKey();
                    JsonObjectBuilder obj = Json.createObjectBuilder()
                            .add("fhirVersion", op.fhirVersion())
                            .add("facadeName", op.facadeName())
                            .add("resourceType", op.resourceType())
                            .add("operation", op.operation());
                    String proxyUrl = e.getValue().getProxyCdrBaseUrl();
                    if (proxyUrl != null && !proxyUrl.isBlank()) {
                        obj.add("proxyCdrBaseUrl", proxyUrl);
                    }
                    array.add(obj);
                });

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        Json.createWriter(resp.getWriter()).writeArray(array.build());
    }
}
