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

package com.viscosiety.viscostore.operation;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.annotations.OnR4Condition;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import com.viscosiety.viscostore.interceptor.CodificationService;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Exposes POST [base]/fhir/$convert for on-demand and batch codification.
 *
 * This operation is the ViscoSuite manual trigger for FML-based codification.
 * It is useful for:
 *   - Re-codifying an inbound resource after its StructureMap has been updated
 *   - Triggering initial codification for resources that were imported before
 *     the interceptor was active
 *   - Operator troubleshooting after a codification failure (Task resource)
 *
 * Parameters:
 *   resourceType  (string, required)   — FHIR resource type of the inbound resource
 *   resourceId    (string, required)   — Logical ID of the inbound resource
 *   structureMap  (canonical, optional) — Override StructureMap URL; if omitted,
 *                                         reads ext-fml-map-ref from the resource itself
 *
 * Registration: declared via hapi.fhir.custom-provider-classes in application.yaml.
 * The global=true flag means the operation is available at [base]/fhir/$convert
 * (not bound to a specific resource type).
 */
@Component
@Conditional(OnR4Condition.class)
public class ConvertOperationProvider {

    private static final String EXT_FML_MAP_REF =
            "https://ig.viscosiety.com/StructureDefinition/ext-fml-map-ref";

    @Autowired
    private DaoRegistry myDaoRegistry;

    @Autowired
    private CodificationService myCodificationService;

    @Operation(name = "$convert", idempotent = false, global = true)
    public Parameters convert(
            @OperationParam(name = "resourceType", min = 1) StringType theResourceType,
            @OperationParam(name = "resourceId",   min = 1) StringType theResourceId,
            @OperationParam(name = "structureMap")           CanonicalType theStructureMap
    ) {
        if (theResourceType == null || theResourceId == null) {
            throw new IllegalArgumentException(
                    "$convert requires both resourceType and resourceId parameters");
        }

        String resourceType = theResourceType.getValue();
        String resourceId   = theResourceId.getValue();

        // Use explicitly provided StructureMap URL, or fall back to ext-fml-map-ref on the resource
        String mapCanonical = theStructureMap != null
                ? theStructureMap.getValue()
                : resolveMapCanonical(resourceType, resourceId);

        // isRevision=false: $convert always creates-or-replaces using the conditional create path
        myCodificationService.codify(resourceType, resourceId, mapCanonical, false);

        Parameters result = new Parameters();
        result.addParameter()
                .setName("outcome")
                .setValue(new StringType(
                        "Codification complete for " + resourceType + "/" + resourceId
                        + " using " + mapCanonical));
        return result;
    }

    /**
     * Reads the inbound resource and extracts the ext-fml-map-ref extension value.
     * Throws if the resource lacks the extension and no structureMap param was provided.
     */
    private String resolveMapCanonical(String resourceType, String resourceId) {
        IBaseResource resource = myDaoRegistry.getResourceDao(resourceType)
                .read(new IdType(resourceType, resourceId), new SystemRequestDetails());

        if (!(resource instanceof DomainResource dr)) {
            throw new IllegalStateException(
                    "Resource " + resourceType + "/" + resourceId + " is not a DomainResource");
        }

        return dr.getExtension().stream()
                .filter(ext -> EXT_FML_MAP_REF.equals(ext.getUrl()))
                .findFirst()
                .map(ext -> ((CanonicalType) ext.getValue()).getValue())
                .orElseThrow(() -> new IllegalStateException(
                        "Resource " + resourceType + "/" + resourceId
                        + " has no ext-fml-map-ref extension and no structureMap parameter was provided"));
    }
}
