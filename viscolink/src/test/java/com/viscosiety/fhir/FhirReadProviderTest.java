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
