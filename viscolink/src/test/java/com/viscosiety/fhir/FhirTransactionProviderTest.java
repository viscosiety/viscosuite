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
