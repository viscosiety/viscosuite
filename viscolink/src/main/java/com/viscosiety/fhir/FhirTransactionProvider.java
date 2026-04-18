package com.viscosiety.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.annotation.Transaction;
import ca.uhn.fhir.rest.annotation.TransactionParam;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import org.frankframework.core.ListenerException;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * Generic HAPI FHIR {@code @Transaction} provider backed by a Frank!Framework
 * {@link FhirListener}.
 *
 * <p>Handles {@code POST /fhir/{version}} with a FHIR transaction Bundle.  The Bundle is
 * serialised to XML, forwarded to the F!F pipeline, and the XML response is deserialised
 * back to a Bundle before HAPI returns it to the caller.</p>
 *
 * <p>One instance is created per registered {@code bundle-transaction} operation by
 * {@link FhirProviderFactory}; no per-version subclass is needed.</p>
 */
public class FhirTransactionProvider extends AbstractBridgeProvider {

    @SuppressWarnings("unchecked")
    private final Class<? extends IBaseResource> bundleClass;

    @SuppressWarnings("unchecked")
    public FhirTransactionProvider(FhirFfBridge bridge, FhirContext fhirContext, FhirOperation operation) {
        super(bridge, fhirContext, operation);
        this.bundleClass = (Class<? extends IBaseResource>)
                fhirContext.getResourceDefinition("Bundle").getImplementingClass();
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return bundleClass;
    }

    @Transaction
    public IBaseBundle transaction(@TransactionParam IBaseBundle bundle) {
        try {
            String responseXml = callBridge((IBaseResource) bundle);
            return (IBaseBundle) fromXml(responseXml, bundleClass);
        } catch (ListenerException e) {
            throw new InternalErrorException(
                    "Pipeline error processing " + getOperation(), e);
        }
    }
}
