package com.viscosiety.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * Creates HAPI FHIR {@link IResourceProvider} instances from {@link FhirOperation} descriptors.
 *
 * <p>The factory resolves the FHIR resource class from the {@link FhirContext} at runtime,
 * so new resource types can be added purely through F!F configuration — no Java changes
 * are needed.</p>
 */
public final class FhirProviderFactory {

    private FhirProviderFactory() {}

    /**
     * Creates the appropriate HAPI provider for {@code operation}.
     *
     * <p>Returns {@code null} for {@code operation="proxy"}: proxy operations have no
     * corresponding HAPI resource provider — routing is handled at servlet level by
     * {@link FhirFacadeServlet}. Callers must filter out null values.</p>
     *
     * @throws IllegalArgumentException if the operation name is not supported or the resource
     *                                  type is not known to the given {@link FhirContext}
     */
    @SuppressWarnings("unchecked")
    public static IResourceProvider create(FhirOperation operation, FhirFfBridge bridge,
                                           FhirContext fhirContext) {
        // Proxy operations have no HAPI provider; FhirFacadeServlet handles them directly.
        if ("proxy".equals(operation.operation())) {
            return null;
        }

        Class<? extends IBaseResource> resourceClass;
        try {
            resourceClass = (Class<? extends IBaseResource>)
                    fhirContext.getResourceDefinition(operation.resourceType()).getImplementingClass();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Unknown FHIR resource type '" + operation.resourceType() +
                    "' for version " + operation.fhirVersion(), e);
        }

        return switch (operation.operation()) {
            case "read"               -> new FhirReadProvider<>(bridge, fhirContext, operation, resourceClass);
            case "bundle-transaction" -> new FhirTransactionProvider(bridge, fhirContext, operation);
            case "search"             -> new FhirSearchProvider<>(bridge, fhirContext, operation, resourceClass);
            default -> throw new IllegalArgumentException(
                    "Unsupported FHIR operation '" + operation.operation() + "' in " + operation);
        };
    }
}
