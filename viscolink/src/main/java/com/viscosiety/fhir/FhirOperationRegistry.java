package com.viscosiety.fhir;

import org.frankframework.receivers.JavaListener;

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

    private static final Map<FhirOperation, JavaListener<?>> REGISTRY = new ConcurrentHashMap<>();

    private FhirOperationRegistry() {}

    /** Called by {@link FhirListener#configure()} to publish the listener. */
    static void register(FhirOperation operation, JavaListener<?> listener) {
        REGISTRY.put(operation, listener);
    }

    /**
     * Returns the listener registered for {@code operation}, or {@code null} if none is
     * configured.
     */
    public static JavaListener<?> getListener(FhirOperation operation) {
        return REGISTRY.get(operation);
    }

    /** Removes all entries — for use in unit tests only. */
    static void clearForTesting() {
        REGISTRY.clear();
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
}
