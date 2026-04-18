package com.viscosiety.fhir;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.frankframework.configuration.ConfigurationException;
import org.frankframework.doc.Mandatory;
import org.frankframework.receivers.JavaListener;

/**
 * Frank!Framework listener that declares a FHIR operation endpoint.
 *
 * <p>Extends {@link JavaListener} — inheriting its synchronous
 * request/response mechanism — and additionally registers itself in
 * {@link FhirOperationRegistry} so the HAPI FHIR servlets can discover and
 * wire it automatically.</p>
 *
 * <p>Because custom element names are not yet supported in the Frank!Framework XSD,
 * declare this listener using the generic escape hatch:</p>
 * <pre>{@code
 * <Listener className="com.viscosiety.fhir.FhirListener"
 *           name="R4PatientReadListener"
 *           fhirVersion="R4"
 *           facadeName="my-facade"
 *           resourceType="Patient"
 *           operation="read"/>
 * }</pre>
 *
 * <p>The {@code facadeName} determines the URL path segment between the version and the FHIR
 * resource path: {@code /viscolink/fhir/r4/my-facade/Patient/{id}}.  Multiple facades may
 * coexist for the same FHIR version as long as they have distinct names.</p>
 *
 * <p>Supported {@code operation} values:</p>
 * <ul>
 *   <li>{@code read} — handles {@code GET /fhir/{version}/{facadeName}/{Resource}/{id}}</li>
 *   <li>{@code bundle-transaction} — handles {@code POST /fhir/{version}/{facadeName}} with a
 *       transaction Bundle</li>
 * </ul>
 */
public class FhirListener extends JavaListener<String> {

    private static final Logger log = LogManager.getLogger(FhirListener.class);

    private String fhirVersion;
    private String facadeName;
    private String resourceType;
    private String operation;

    @Override
    public void configure() throws ConfigurationException {
        if (fhirVersion == null || fhirVersion.isBlank()) {
            throw new ConfigurationException("fhirVersion is required on FhirListener [" + getName() + "]");
        }
        if (facadeName == null || facadeName.isBlank()) {
            throw new ConfigurationException("facadeName is required on FhirListener [" + getName() + "]");
        }
        if (resourceType == null || resourceType.isBlank()) {
            throw new ConfigurationException("resourceType is required on FhirListener [" + getName() + "]");
        }
        if (operation == null || operation.isBlank()) {
            throw new ConfigurationException("operation is required on FhirListener [" + getName() + "]");
        }
        super.configure();
        FhirOperation op = new FhirOperation(fhirVersion, facadeName, resourceType, operation);
        FhirOperationRegistry.register(op, this);
        FhirServletRegistrar.notifyFacadeDeclared(fhirVersion, facadeName);
        log.info("FhirListener [{}] registered for operation [{}]", getName(), op);
    }

    /** FHIR version this listener handles, e.g. {@code R4} or {@code DSTU3}. */
    @Mandatory
    public void setFhirVersion(String fhirVersion) {
        this.fhirVersion = fhirVersion;
    }

    /**
     * Facade name that scopes this listener to a specific FHIR endpoint.
     * Becomes the URL path segment between the version and the resource path,
     * e.g. {@code my-facade} → {@code /fhir/r4/my-facade/Patient/{id}}.
     */
    @Mandatory
    public void setFacadeName(String facadeName) {
        this.facadeName = facadeName;
    }

    /** FHIR resource type this listener handles, e.g. {@code Patient} or {@code Bundle}. */
    @Mandatory
    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    /**
     * FHIR operation this listener handles.
     * Supported values: {@code read}, {@code bundle-transaction}.
     */
    @Mandatory
    public void setOperation(String operation) {
        this.operation = operation;
    }
}
