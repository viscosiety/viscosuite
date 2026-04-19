package com.viscosiety.viscostore.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.annotations.OnR4Condition;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.DomainResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ViscoStore codification interceptor — Phase 3a of the ViscoSuite pipeline.
 *
 * Watches every FHIR resource create and update for two conditions:
 *   1. The resource carries the ViscoSuite inbound zone tag (data-zone|inbound)
 *   2. The resource has the ext-fml-map-ref extension pointing to a StructureMap canonical URL
 *
 * When both conditions hold, FML codification is scheduled to run after the inbound
 * resource's DB transaction commits (via TransactionSynchronization.afterCommit).
 * This guarantees the inbound resource is fully persisted before the StructureMap runs.
 *
 * Registration: this bean is registered on IInterceptorService (not RestfulServer) via
 * CodificationConfig so that it fires for ALL DAO-level writes, including batch imports.
 */
@Component
@Interceptor
@Conditional(OnR4Condition.class)
public class CodificationInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CodificationInterceptor.class);

    /** ViscoSuite IG extension that signals which StructureMap to apply */
    private static final String EXT_FML_MAP_REF =
            "https://ig.viscosiety.com/StructureDefinition/ext-fml-map-ref";

    /** ViscoSuite zone CodeSystem */
    private static final String DATA_ZONE_SYSTEM =
            "https://ig.viscosiety.com/CodeSystem/data-zone";

    private static final String ZONE_INBOUND = "inbound";

    @Autowired
    private FhirContext myFhirContext;

    @Autowired
    private CodificationService myCodificationService;

    // -------------------------------------------------------------------------
    // Hook: resource created (initial ingest)
    // -------------------------------------------------------------------------

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void onResourceCreated(IBaseResource theResource, RequestDetails theRequestDetails) {
        scheduleIfInbound(theResource, false);
    }

    // -------------------------------------------------------------------------
    // Hook: resource updated (Phase 5 re-codification on inbound revision)
    // -------------------------------------------------------------------------

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void onResourceUpdated(IBaseResource theOldResource,
                                  IBaseResource theNewResource,
                                  RequestDetails theRequestDetails) {
        scheduleIfInbound(theNewResource, true);
    }

    // -------------------------------------------------------------------------
    // Core dispatch logic
    // -------------------------------------------------------------------------

    private void scheduleIfInbound(IBaseResource theResource, boolean isUpdate) {
        // Only process R4 DomainResource instances
        if (!(theResource instanceof DomainResource resource)) {
            return;
        }

        // Guard 1: inbound zone tag must be present
        if (!hasInboundZoneTag(resource)) {
            return;
        }

        // Guard 2: ext-fml-map-ref must point to a StructureMap
        String structureMapCanonical = extractFmlMapRef(resource);
        if (structureMapCanonical == null) {
            log.debug("Resource {} carries inbound zone tag but has no ext-fml-map-ref — skipping FML codification",
                    resource.getIdElement().toVersionless());
            return;
        }

        // Capture locals for the post-commit lambda (must be effectively final)
        final String capturedResourceType  = myFhirContext.getResourceDefinition(resource).getName();
        final String capturedResourceId    = resource.getIdElement().getIdPart();
        final String capturedMapCanonical  = structureMapCanonical;
        final boolean capturedIsUpdate     = isUpdate;

        log.info("Scheduling FML codification for {}/{} using StructureMap {}",
                capturedResourceType, capturedResourceId, capturedMapCanonical);

        // Register post-commit callback.
        // afterCommit() runs OUTSIDE the inbound resource's transaction so:
        //   - the inbound resource is fully visible to DAO reads
        //   - the codified resource write cannot be rolled back together with the inbound write
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            myCodificationService.codify(
                                    capturedResourceType,
                                    capturedResourceId,
                                    capturedMapCanonical,
                                    capturedIsUpdate);
                        } catch (Exception e) {
                            log.error("Codification failed for {}/{} (map={}): {}",
                                    capturedResourceType, capturedResourceId,
                                    capturedMapCanonical, e.getMessage(), e);
                            // Write a Task resource flagging the failure so operators can query
                            // GET [base]/fhir/Task?code=codification-failure&status=failed
                            myCodificationService.writeErrorTask(
                                    capturedResourceType, capturedResourceId,
                                    capturedMapCanonical, e.getMessage());
                        }
                    }
                }
        );
    }

    // -------------------------------------------------------------------------
    // Helper: check inbound zone tag
    // -------------------------------------------------------------------------

    private boolean hasInboundZoneTag(DomainResource resource) {
        if (resource.getMeta() == null) return false;
        return resource.getMeta().getTag().stream()
                .anyMatch(tag ->
                        DATA_ZONE_SYSTEM.equals(tag.getSystem()) &&
                        ZONE_INBOUND.equals(tag.getCode()));
    }

    // -------------------------------------------------------------------------
    // Helper: extract StructureMap canonical from ext-fml-map-ref
    // -------------------------------------------------------------------------

    private String extractFmlMapRef(DomainResource resource) {
        return resource.getExtension().stream()
                .filter(ext -> EXT_FML_MAP_REF.equals(ext.getUrl()))
                .findFirst()
                .map(ext -> {
                    if (ext.getValue() instanceof CanonicalType canonical) {
                        return canonical.getValue();
                    }
                    return null;
                })
                .orElse(null);
    }
}
