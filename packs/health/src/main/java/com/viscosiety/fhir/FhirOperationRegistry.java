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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Static registry that maps {@link FhirOperation} keys to their {@link FhirListener} instances.
 *
 * <p>{@link FhirListener#configure()} registers each listener here during F!F configuration
 * loading.  The HAPI FHIR servlets query the registry in their {@code initialize()} method
 * (called lazily on the first request) to build their resource-provider lists dynamically —
 * so adding a new FHIR operation only requires a new F!F adapter XML file, no Java changes.</p>
 */
public final class FhirOperationRegistry {

    private static final Map<FhirOperation, FhirListener> REGISTRY = new ConcurrentHashMap<>();

    private FhirOperationRegistry() {}

    /** Called by {@link FhirListener#configure()} to publish the listener. */
    static void register(FhirOperation operation, FhirListener listener) {
        REGISTRY.put(operation, listener);
    }

    /**
     * Returns the listener registered for {@code operation}, or {@code null} if none is
     * configured.
     */
    public static FhirListener getListener(FhirOperation operation) {
        return REGISTRY.get(operation);
    }

    /** Removes all entries — for use in unit tests only. */
    static void clearForTesting() {
        REGISTRY.clear();
    }

    /** Returns all registered operation→listener pairs, for display in the console webservices block. */
    public static Map<FhirOperation, FhirListener> getAllRegistrations() {
        return Map.copyOf(REGISTRY);
    }

    /** Returns all registered operations (keys only). */
    public static Set<FhirOperation> getAllOperations() {
        return Set.copyOf(REGISTRY.keySet());
    }

    /**
     * Returns all operations registered for the given FHIR facade
     * (e.g. version {@code "R4"}, facade {@code "my-facade"}).
     *
     * <p>Called by {@link FhirFacadeServlet} during {@code initialize()} to build its
     * resource-provider list for a specific facade.</p>
     */
    public static Set<FhirOperation> getOperationsForFacade(String fhirVersion, String facadeName) {
        return REGISTRY.keySet().stream()
                .filter(op -> op.fhirVersion().equals(fhirVersion) && op.facadeName().equals(facadeName))
                .collect(Collectors.toSet());
    }

    /**
     * Returns the {@link FhirListener} declared with {@code operation="metadata"} for the given
     * facade, or {@code null} if no custom metadata handler is configured.
     *
     * <p>When non-null the servlet delegates {@code /metadata} requests to the F!F pipeline
     * instead of generating the capability statement automatically.</p>
     */
    public static FhirListener getMetadataListener(String fhirVersion, String facadeName) {
        return REGISTRY.entrySet().stream()
                .filter(e -> e.getKey().fhirVersion().equals(fhirVersion)
                        && e.getKey().facadeName().equals(facadeName)
                        && "metadata".equals(e.getKey().operation()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the {@link FhirListener} declared with {@code operation="proxy"} for the given
     * facade, or {@code null} if no proxy is configured.
     *
     * <p>Called by {@link FhirFacadeServlet} during {@code initialize()} to determine whether
     * unhandled routes should be forwarded to an upstream CDR.</p>
     */
    public static FhirListener getProxyListener(String fhirVersion, String facadeName) {
        return REGISTRY.entrySet().stream()
                .filter(e -> e.getKey().fhirVersion().equals(fhirVersion)
                        && e.getKey().facadeName().equals(facadeName)
                        && "proxy".equals(e.getKey().operation()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
