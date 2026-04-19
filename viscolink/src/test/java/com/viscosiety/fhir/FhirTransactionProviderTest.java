package com.viscosiety.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.frankframework.core.ListenerException;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FhirTransactionProviderTest {

    private static final FhirContext CTX = FhirContext.forR4();
    private static final FhirOperation OP = new FhirOperation("R4", "my-facade", "Bundle", "bundle-transaction");

    @Mock FhirFfBridge bridge;

    private FhirTransactionProvider provider;

    @BeforeEach
    void setUp() {
        provider = new FhirTransactionProvider(bridge, CTX, OP);
    }

    @Test
    void resourceTypeIsR4Bundle() {
        assertEquals(Bundle.class, provider.getResourceType());
    }

    @Test
    void transactionForwardsBundleXmlAndReturnsDeserializedBundle() throws Exception {
        Bundle request = new Bundle();
        request.setType(BundleType.TRANSACTION);
        request.addEntry().setResource(new Patient().setId("p1"));

        String responseXml = CTX.newXmlParser().encodeResourceToString(request);
        when(bridge.processRequest(eq(OP), any())).thenReturn(responseXml);

        IBaseBundle result = provider.transaction(request);
        assertInstanceOf(Bundle.class, result);
    }

    @Test
    void bridgeExceptionIsWrappedAsInternalError() throws Exception {
        Bundle request = new Bundle();
        request.setType(BundleType.TRANSACTION);
        when(bridge.processRequest(any(), any())).thenThrow(new ListenerException("pipeline error"));

        assertThrows(ca.uhn.fhir.rest.server.exceptions.InternalErrorException.class,
                () -> provider.transaction(request));
    }
}
