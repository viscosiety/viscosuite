package com.viscosiety.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;

import java.util.List;

/**
 * HAPI FHIR plain-server servlet for a single FHIR facade.
 *
 * <p>One instance is created per {@code (fhirVersion, facadeName)} pair, triggered lazily when
 * the first {@link FhirListener} for that facade is configured.  The servlet is registered with
 * Tomcat by {@link FhirServletRegistrar} and serves requests at:</p>
 * <pre>
 *   /viscolink/fhir/{version}/{facadeName}/*
 * </pre>
 *
 * <p>Resource providers are built at HAPI {@code initialize()} time by querying
 * {@link FhirOperationRegistry} for all operations registered under the same
 * {@code (fhirVersion, facadeName)} key.</p>
 */
public class FhirFacadeServlet extends AbstractFhirServlet {

    private static final long serialVersionUID = 1L;

    private final String fhirVersion;
    private final String facadeName;
    private final FhirFfBridge bridge;

    public FhirFacadeServlet(String fhirVersion, String facadeName, FhirFfBridge bridge) {
        this.fhirVersion = fhirVersion;
        this.facadeName = facadeName;
        this.bridge = bridge;
    }

    @Override
    public String getName() {
        return "FhirFacadeServlet-" + fhirVersion + "-" + facadeName;
    }

    /** URL pattern relative to the viscolink context root, e.g. {@code fhir/r4/my-facade/*}. */
    @Override
    public String getUrlMapping() {
        return "fhir/" + fhirVersion.toLowerCase() + "/" + facadeName + "/*";
    }

    @Override
    protected FhirContext createFhirContext() {
        return switch (fhirVersion.toUpperCase()) {
            case "R4"    -> FhirContext.forR4();
            case "R5"    -> FhirContext.forR5();
            case "DSTU3" -> FhirContext.forDstu3();
            default      -> throw new IllegalArgumentException("Unsupported FHIR version: " + fhirVersion);
        };
    }

    @Override
    protected List<IResourceProvider> createProviders() {
        FhirContext ctx = getFhirContext();
        return FhirOperationRegistry.getOperationsForFacade(fhirVersion, facadeName).stream()
                .map(op -> FhirProviderFactory.create(op, bridge, ctx))
                .toList();
    }
}
