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
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import org.frankframework.core.ListenerException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;

/**
 * Generic HAPI FHIR {@code @Read} provider backed by a Frank!Framework {@link FhirListener}.
 *
 * <p>Handles {@code GET /fhir/{version}/{Resource}/{id}}.  The ID is forwarded to the F!F
 * pipeline as {@code <id value="..."/>}; the pipeline must return a valid FHIR resource XML
 * document of the matching resource type.</p>
 *
 * <p>One instance is created per registered {@code read} operation by
 * {@link FhirProviderFactory}; no per-resource subclass is needed.</p>
 */
public class FhirReadProvider<T extends IBaseResource> extends AbstractBridgeProvider {

    private final Class<T> resourceClass;

    public FhirReadProvider(FhirFfBridge bridge, FhirContext fhirContext,
                            FhirOperation operation, Class<T> resourceClass) {
        super(bridge, fhirContext, operation);
        this.resourceClass = resourceClass;
    }

    @Override
    public Class<T> getResourceType() {
        return resourceClass;
    }

    @Read
    public T read(@IdParam IIdType id) {
        try {
            String xml = "<id value=\"" + id.getIdPart() + "\"/>";
            String responseXml = callBridge(xml);
            return fromXml(responseXml, resourceClass);
        } catch (ListenerException e) {
            throw new InternalErrorException(
                    "Pipeline error processing " + getOperation() + " for id: " + id.getIdPart(), e);
        }
    }
}
