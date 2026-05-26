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

package com.viscosiety.viscostore.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.starter.annotations.OnR4Condition;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.UriParam;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.utils.StructureMapUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Executes FML codification for a single inbound resource.
 *
 * Called post-commit from {@link CodificationInterceptor} so this service
 * always runs in a fresh transaction, completely independent of the inbound
 * resource's write transaction.
 *
 * Each public method is {@code REQUIRES_NEW} so that:
 *   - codify() and writeErrorTask() each get their own DB connection
 *   - a failure in codify() does not prevent writeErrorTask() from committing
 */
@Service
@Conditional(OnR4Condition.class)
public class CodificationService {

    private static final Logger log = LoggerFactory.getLogger(CodificationService.class);

    /** ViscoSuite IG extension carrying a back-reference to the inbound resource */
    private static final String EXT_INBOUND_SOURCE_REF =
            "https://ig.viscosiety.com/StructureDefinition/ext-inbound-source-ref";

    /**
     * Complex provenance extension: sub-extensions mappingType ("fml") and structureMapUrl.
     * Added to every codified resource so consumers can trace which StructureMap produced it.
     */
    private static final String EXT_DATA_PROVENANCE =
            "https://ig.viscosiety.com/StructureDefinition/ext-data-provenance";

    private static final String DATA_ZONE_SYSTEM =
            "https://ig.viscosiety.com/CodeSystem/data-zone";

    private static final String ZONE_CODIFIED = "codified";

    /** SearchParameter code used to look up codified resources by their inbound source reference */
    private static final String SP_INBOUND_SOURCE_REF = "inbound-source-ref";

    /** CodeSystem for ViscoStore internal task codes */
    private static final String TASK_TYPE_SYSTEM = "https://ig.viscosiety.com/CodeSystem/task-type";

    @Autowired
    private FhirContext myFhirContext;

    @Autowired
    private DaoRegistry myDaoRegistry;

    /**
     * Used to construct a HapiWorkerContext (R4 IWorkerContext implementation) on demand.
     * HAPI JPA does not register org.hl7.fhir.r4.context.IWorkerContext as a bean; the
     * internal WorkerContextValidationSupportAdapter implements the R5 interface. We
     * bridge this by constructing HapiWorkerContext(FhirContext, IValidationSupport) ourselves.
     */
    @Autowired
    private IValidationSupport myValidationSupport;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs the FML StructureMap against an inbound resource and persists the result
     * as a codified resource in ViscoStore.
     *
     * @param theResourceType  FHIR resource type of the inbound resource (e.g. "Observation")
     * @param theResourceId    Logical ID of the inbound resource
     * @param theMapCanonical  Canonical URL of the StructureMap to apply
     * @param theIsRevision    true for updates (re-codification), false for initial creation
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void codify(String theResourceType,
                       String theResourceId,
                       String theMapCanonical,
                       boolean theIsRevision) {

        SystemRequestDetails requestDetails = new SystemRequestDetails();

        // 1. Load the inbound resource from ViscoStore
        IFhirResourceDao<?> resourceDao = myDaoRegistry.getResourceDao(theResourceType);
        IBaseResource inboundResource = resourceDao.read(
                new IdType(theResourceType, theResourceId), requestDetails);

        if (!(inboundResource instanceof DomainResource inbound)) {
            log.warn("Codification skipped: {}/{} is not a DomainResource", theResourceType, theResourceId);
            return;
        }

        // 2. Load the StructureMap from ViscoStore by canonical URL
        StructureMap structureMap = loadStructureMap(theMapCanonical, requestDetails);
        if (structureMap == null) {
            throw new IllegalStateException("StructureMap not found in ViscoStore: " + theMapCanonical);
        }

        // 3. Determine target resource type from StructureMap.structure[mode=target]
        String targetType = resolveTargetType(structureMap);

        // 4. Create an empty target resource instance (populated by the FML engine)
        IBaseResource targetBase = myFhirContext.getResourceDefinition(targetType).newInstance();
        if (!(targetBase instanceof DomainResource codified)) {
            throw new IllegalStateException("Target resource type is not a DomainResource: " + targetType);
        }

        // 5. Execute the FML transform
        //    StructureMapUtilities.transform() populates `codified` in-place.
        StructureMapUtilities utils = new StructureMapUtilities(
                new HapiWorkerContext(myFhirContext, myValidationSupport), null);
        try {
            utils.transform(null, inbound, structureMap, codified);
        } catch (Exception e) {
            throw new RuntimeException(
                    "FML transform failed for " + theResourceType + "/" + theResourceId
                    + " using " + theMapCanonical, e);
        }

        // 6. Post-process: inject zone tag and traceability extensions
        injectCodifiedZoneTag(codified);
        injectInboundSourceRef(codified, theResourceType, theResourceId);
        injectDataProvenance(codified, theMapCanonical);

        // 7. Persist the codified resource
        @SuppressWarnings("unchecked")
        IFhirResourceDao<DomainResource> codifiedDao =
                (IFhirResourceDao<DomainResource>) myDaoRegistry.getResourceDao(targetType);

        if (!theIsRevision) {
            // Conditional create: idempotent on retry.
            // The ifNoneExist condition requires the inbound-source-ref SearchParameter
            // (https://ig.viscosiety.com/SearchParameter/[type]-inbound-source-ref) to be
            // registered in ViscoStore. If absent, HAPI will throw and codification will fail.
            codified.setId((String) null);
            codifiedDao.create(codified,
                    SP_INBOUND_SOURCE_REF + "=" + theResourceType + "/" + theResourceId,
                    requestDetails);
            log.info("Created codified {} from inbound {}/{}", targetType, theResourceType, theResourceId);
        } else {
            // Revision: find and update the existing codified resource
            String existingId = findExistingCodifiedId(targetType, theResourceType, theResourceId, requestDetails);
            if (existingId != null) {
                codified.setId(existingId);
                codifiedDao.update(codified, requestDetails);
                log.info("Updated codified {}/{} from revised inbound {}/{}",
                        targetType, existingId, theResourceType, theResourceId);
            } else {
                // Edge case: update before initial codification completed (e.g. retry scenario)
                codified.setId((String) null);
                codifiedDao.create(codified, requestDetails);
                log.info("Created codified {} (first codification) from updated inbound {}/{}",
                        targetType, theResourceType, theResourceId);
            }
        }
    }

    /**
     * Writes a Task resource flagging a failed codification attempt.
     * Operators can query: GET [base]/fhir/Task?code=codification-failure&status=failed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeErrorTask(String theResourceType, String theResourceId,
                                String theMapCanonical, String theErrorMessage) {
        try {
            IFhirResourceDao<Task> taskDao = myDaoRegistry.getResourceDao(Task.class);
            Task task = new Task();
            task.setStatus(Task.TaskStatus.FAILED);
            task.setIntent(Task.TaskIntent.ORDER);
            task.getCode().addCoding()
                    .setSystem(TASK_TYPE_SYSTEM)
                    .setCode("codification-failure");
            task.setFocus(new Reference(theResourceType + "/" + theResourceId));
            task.addNote().setText(
                    "Codification failed using StructureMap " + theMapCanonical + ": " + theErrorMessage);
            taskDao.create(task, new SystemRequestDetails());
            log.info("Wrote codification failure Task for {}/{}", theResourceType, theResourceId);
        } catch (Exception ex) {
            log.error("Could not write codification failure Task resource: {}", ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Load StructureMap by canonical URL
    // -------------------------------------------------------------------------

    private StructureMap loadStructureMap(String theCanonical, SystemRequestDetails requestDetails) {
        IFhirResourceDao<StructureMap> mapDao = myDaoRegistry.getResourceDao(StructureMap.class);
        SearchParameterMap params = new SearchParameterMap();
        params.add(StructureMap.SP_URL, new UriParam(theCanonical));
        IBundleProvider results = mapDao.search(params, requestDetails);
        List<IBaseResource> maps = results.getAllResources();
        return maps.isEmpty() ? null : (StructureMap) maps.get(0);
    }

    // -------------------------------------------------------------------------
    // Resolve target resource type from StructureMap.structure[mode=target]
    // -------------------------------------------------------------------------

    private String resolveTargetType(StructureMap theMap) {
        return theMap.getStructure().stream()
                .filter(s -> s.getMode() == StructureMap.StructureMapModelMode.TARGET)
                .findFirst()
                .map(s -> {
                    // Prefer the alias (as declared in FML: uses "..." alias MyType as target)
                    // which is typically the plain FHIR resource type name (e.g. "Observation").
                    if (s.hasAlias()) return s.getAlias();
                    // Fallback: extract type name from the last segment of the canonical URL
                    String url = s.getUrl();
                    return url.substring(url.lastIndexOf('/') + 1);
                })
                .orElseThrow(() -> new IllegalStateException(
                        "StructureMap has no target structure declaration: " + theMap.getUrl()));
    }

    // -------------------------------------------------------------------------
    // Post-processing: inject codified zone tag
    // -------------------------------------------------------------------------

    private void injectCodifiedZoneTag(DomainResource resource) {
        // Remove any zone tag the FML map may have copied from the source
        resource.getMeta().getTag().removeIf(tag -> DATA_ZONE_SYSTEM.equals(tag.getSystem()));
        resource.getMeta().addTag()
                .setSystem(DATA_ZONE_SYSTEM)
                .setCode(ZONE_CODIFIED)
                .setDisplay("Codified Zone");
    }

    // -------------------------------------------------------------------------
    // Post-processing: back-reference to the originating inbound resource
    // -------------------------------------------------------------------------

    private void injectInboundSourceRef(DomainResource resource,
                                         String theResourceType,
                                         String theResourceId) {
        resource.getExtension().removeIf(ext -> EXT_INBOUND_SOURCE_REF.equals(ext.getUrl()));
        Extension sourceRef = resource.addExtension();
        sourceRef.setUrl(EXT_INBOUND_SOURCE_REF);
        sourceRef.setValue(new Reference(theResourceType + "/" + theResourceId));
    }

    // -------------------------------------------------------------------------
    // Post-processing: data provenance (mapping type + StructureMap URL)
    // -------------------------------------------------------------------------

    private void injectDataProvenance(DomainResource resource, String theMapCanonical) {
        resource.getExtension().removeIf(ext -> EXT_DATA_PROVENANCE.equals(ext.getUrl()));
        Extension provenance = resource.addExtension();
        provenance.setUrl(EXT_DATA_PROVENANCE);
        // mappingType = "fml" (other strategies: "microservice", "async")
        provenance.addExtension("mappingType", new CodeType("fml"));
        // structureMapUrl = the canonical URL of the applied StructureMap
        provenance.addExtension("structureMapUrl", new CanonicalType(theMapCanonical));
    }

    // -------------------------------------------------------------------------
    // Find existing codified resource by inbound-source-ref (for Phase 5 revisions)
    // -------------------------------------------------------------------------

    private String findExistingCodifiedId(String targetType,
                                           String inboundType,
                                           String inboundId,
                                           SystemRequestDetails requestDetails) {
        // Relies on the inbound-source-ref SearchParameter being registered.
        // SearchParameter URL: https://ig.viscosiety.com/SearchParameter/[type]-inbound-source-ref
        // expression: extension('https://ig.viscosiety.com/StructureDefinition/ext-inbound-source-ref').value
        // type: reference
        try {
            IFhirResourceDao<?> dao = myDaoRegistry.getResourceDao(targetType);
            SearchParameterMap params = new SearchParameterMap();
            params.add(SP_INBOUND_SOURCE_REF,
                    new ReferenceParam(inboundType + "/" + inboundId));
            params.add("_tag",
                    new TokenParam(DATA_ZONE_SYSTEM, ZONE_CODIFIED));
            IBundleProvider results = dao.search(params, requestDetails);
            List<IBaseResource> found = results.getAllResources();
            return found.isEmpty() ? null : found.get(0).getIdElement().getIdPart();
        } catch (Exception e) {
            log.warn("Could not find existing codified resource for {}/{}: {}",
                    inboundType, inboundId, e.getMessage());
            return null;
        }
    }
}
