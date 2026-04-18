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
     * @throws IllegalArgumentException if the operation name is not supported or the resource
     *                                  type is not known to the given {@link FhirContext}
     */
    @SuppressWarnings("unchecked")
    public static IResourceProvider create(FhirOperation operation, FhirFfBridge bridge,
                                           FhirContext fhirContext) {
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
            case "read" -> new FhirReadProvider<>(bridge, fhirContext, operation, resourceClass);
            case "bundle-transaction" -> new FhirTransactionProvider(bridge, fhirContext, operation);
            default -> throw new IllegalArgumentException(
                    "Unsupported FHIR operation '" + operation.operation() + "' in " + operation);
        };
    }
}
