package com.viscosiety.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.frankframework.core.ListenerException;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
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
class FhirReadProviderTest {

    private static final FhirContext CTX = FhirContext.forR4();
    private static final FhirOperation OP = new FhirOperation("R4", "my-facade", "Patient", "read");

    @Mock FhirFfBridge bridge;

    private FhirReadProvider<Patient> provider;

    @BeforeEach
    void setUp() {
        provider = new FhirReadProvider<>(bridge, CTX, OP, Patient.class);
    }

    @Test
    void readForwardsIdToBridgeAndReturnsDeserializedPatient() throws Exception {
        String responseXml = "<Patient xmlns=\"http://hl7.org/fhir\"><id value=\"pat-7\"/></Patient>";
        when(bridge.processRequest(eq(OP), any())).thenReturn(responseXml);

        Patient result = provider.read(new IdType("pat-7"));

        assertEquals("pat-7", result.getIdElement().getIdPart());
    }

    @Test
    void readSendsIdElementXmlToBridge() throws Exception {
        String responseXml = "<Patient xmlns=\"http://hl7.org/fhir\"><id value=\"abc\"/></Patient>";
        when(bridge.processRequest(eq(OP), eq("<id value=\"abc\"/>"))).thenReturn(responseXml);

        Patient result = provider.read(new IdType("abc"));
        assertNotNull(result);
    }

    @Test
    void bridgeListenerExceptionIsWrappedAsInternalError() throws Exception {
        when(bridge.processRequest(any(), any())).thenThrow(new ListenerException("pipeline down"));

        var ex = assertThrows(ca.uhn.fhir.rest.server.exceptions.InternalErrorException.class,
                () -> provider.read(new IdType("fail")));
        assertTrue(ex.getMessage().contains("pipeline down") || ex.getCause().getMessage().contains("pipeline down"));
    }
}
