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

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.frankframework.core.ListenerException;
import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * Abstract base for HAPI FHIR resource providers that delegate to a Frank!Framework pipeline
 * via {@link FhirFfBridge}.
 *
 * <p>Subclasses declare the HAPI operation methods ({@code @Transaction}, {@code @Read}, etc.).
 * Routing to the correct F!F listener is handled by the {@link FhirOperation} key, which the
 * bridge resolves through {@link FhirOperationRegistry}.</p>
 */
public abstract class AbstractBridgeProvider implements IResourceProvider {

    protected final Logger      log;
    protected final FhirFfBridge bridge;
    protected final FhirContext  fhirContext;
    private   final FhirOperation operation;

    protected AbstractBridgeProvider(FhirFfBridge bridge, FhirContext fhirContext,
                                     FhirOperation operation) {
        this.log       = LogManager.getLogger(getClass());
        this.bridge    = bridge;
        this.fhirContext = fhirContext;
        this.operation = operation;
    }

    protected FhirOperation getOperation() {
        return operation;
    }

    /** Serialise a FHIR resource to a compact XML string. */
    protected String toXml(IBaseResource resource) {
        IParser parser = fhirContext.newXmlParser();
        parser.setPrettyPrint(false);
        return parser.encodeResourceToString(resource);
    }

    /** Deserialise an XML string back to a typed FHIR resource. */
    protected <T extends IBaseResource> T fromXml(String xml, Class<T> type) {
        return fhirContext.newXmlParser().parseResource(type, xml);
    }

    /**
     * Serialise {@code resource} to XML, forward to the F!F pipeline via the operation key,
     * and return the raw XML response string.
     */
    protected String callBridge(IBaseResource resource) throws ListenerException {
        return callBridge(toXml(resource));
    }

    /**
     * Forward a pre-built XML string to the F!F pipeline via the operation key, and return
     * the raw XML response.  Use this for operations without an incoming FHIR resource
     * (e.g. read-by-id, where only an ID is available).
     */
    protected String callBridge(String xml) throws ListenerException {
        log.debug("{}: forwarding {} chars for operation [{}]",
                getClass().getSimpleName(), xml.length(), operation);
        return bridge.processRequest(operation, xml);
    }
}
