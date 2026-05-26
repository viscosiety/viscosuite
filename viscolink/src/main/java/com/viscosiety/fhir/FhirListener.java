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
 *   <li>{@code search} — handles {@code GET /fhir/{version}/{facadeName}/{Resource}?...} with
 *       arbitrary search parameters; the pipeline receives a {@code <searchParams>} XML document
 *       and must return a FHIR Bundle XML</li>
 *   <li>{@code proxy} — declares that all routes in this facade that are not handled by a more
 *       specific adapter are forwarded transparently to {@code proxyCdrBaseUrl}.  No
 *       {@code resourceType} is required.  The pipeline in the declaring adapter is never
 *       invoked; the forwarding is done at servlet level by {@link FhirFacadeServlet}.</li>
 * </ul>
 */
public class FhirListener extends JavaListener<String> {

    private static final Logger log = LogManager.getLogger(FhirListener.class);

    private String fhirVersion;
    private String facadeName;
    private String resourceType;
    private String operation;
    /** Base URL of the upstream CDR; required only for {@code operation="proxy"}. */
    private String proxyCdrBaseUrl;
    /** F!F credential alias for HTTP Basic auth to the upstream CDR; optional. */
    private String proxyCdrCredentialAlias;

    @Override
    public void configure() throws ConfigurationException {
        if (fhirVersion == null || fhirVersion.isBlank()) {
            throw new ConfigurationException("fhirVersion is required on FhirListener [" + getName() + "]");
        }
        if (facadeName == null || facadeName.isBlank()) {
            throw new ConfigurationException("facadeName is required on FhirListener [" + getName() + "]");
        }
        if (operation == null || operation.isBlank()) {
            throw new ConfigurationException("operation is required on FhirListener [" + getName() + "]");
        }

        if ("proxy".equals(operation)) {
            if (proxyCdrBaseUrl == null || proxyCdrBaseUrl.isBlank()) {
                throw new ConfigurationException(
                        "proxyCdrBaseUrl is required on FhirListener [" + getName() + "] for operation=proxy");
            }
            if (resourceType == null || resourceType.isBlank()) {
                resourceType = "*"; // sentinel — the proxy covers all unhandled resource types
            }
        } else if ("metadata".equals(operation)) {
            resourceType = "$metadata"; // sentinel — no real FHIR resource type, handled at servlet level
        } else {
            if (resourceType == null || resourceType.isBlank()) {
                throw new ConfigurationException("resourceType is required on FhirListener [" + getName() + "]");
            }
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

    /**
     * FHIR resource type this listener handles, e.g. {@code Patient} or {@code Bundle}.
     * Not required when {@code operation="proxy"}.
     */
    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    /**
     * FHIR operation this listener handles.
     * Supported values: {@code read}, {@code bundle-transaction}, {@code search}, {@code proxy}.
     */
    @Mandatory
    public void setOperation(String operation) {
        this.operation = operation;
    }

    /**
     * Base URL of the upstream CDR to which unhandled requests are forwarded.
     * Required when {@code operation="proxy"}; unused for all other operations.
     * Supports Frank!Framework property references, e.g. {@code ${viscostore.fhir.base.url}}.
     */
    public void setProxyCdrBaseUrl(String proxyCdrBaseUrl) {
        this.proxyCdrBaseUrl = proxyCdrBaseUrl;
    }

    /**
     * Returns the CDR base URL configured for proxy forwarding, or {@code null} if this is
     * not a proxy listener.
     */
    public String getProxyCdrBaseUrl() {
        return proxyCdrBaseUrl;
    }

    /**
     * F!F credential alias used to look up the username and password for HTTP Basic
     * authentication to the upstream CDR.  The alias must be present in the configured
     * credential store (e.g. {@code credentials.properties}).  Optional — when absent,
     * proxy requests are forwarded without an {@code Authorization} header.
     */
    public void setProxyCdrCredentialAlias(String proxyCdrCredentialAlias) {
        this.proxyCdrCredentialAlias = proxyCdrCredentialAlias;
    }

    /** Returns the credential alias, or {@code null} if not configured. */
    public String getProxyCdrCredentialAlias() {
        return proxyCdrCredentialAlias;
    }
}
