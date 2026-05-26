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
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class FhirProviderFactoryTest {

    @Mock FhirFfBridge bridge;

    private static final FhirContext R4    = FhirContext.forR4();
    private static final FhirContext DSTU3 = FhirContext.forDstu3();

    @Test
    void readOperationCreatesReadProvider() {
        FhirOperation op = new FhirOperation("R4", "my-facade", "Patient", "read");
        IResourceProvider provider = FhirProviderFactory.create(op, bridge, R4);
        assertInstanceOf(FhirReadProvider.class, provider);
    }

    @Test
    void readProviderHasCorrectR4ResourceType() {
        FhirOperation op = new FhirOperation("R4", "my-facade", "Patient", "read");
        IResourceProvider provider = FhirProviderFactory.create(op, bridge, R4);
        assertEquals(org.hl7.fhir.r4.model.Patient.class, provider.getResourceType());
    }

    @Test
    void readProviderHasCorrectDstu3ResourceType() {
        FhirOperation op = new FhirOperation("DSTU3", "my-facade", "Patient", "read");
        IResourceProvider provider = FhirProviderFactory.create(op, bridge, DSTU3);
        assertEquals(org.hl7.fhir.dstu3.model.Patient.class, provider.getResourceType());
    }

    @Test
    void bundleTransactionOperationCreatesTransactionProvider() {
        FhirOperation op = new FhirOperation("R4", "my-facade", "Bundle", "bundle-transaction");
        IResourceProvider provider = FhirProviderFactory.create(op, bridge, R4);
        assertInstanceOf(FhirTransactionProvider.class, provider);
    }

    @Test
    void transactionProviderResourceTypeIsVersionSpecificBundleClass() {
        FhirOperation r4Op    = new FhirOperation("R4",    "my-facade", "Bundle", "bundle-transaction");
        FhirOperation dstu3Op = new FhirOperation("DSTU3", "my-facade", "Bundle", "bundle-transaction");

        assertEquals(org.hl7.fhir.r4.model.Bundle.class,
                FhirProviderFactory.create(r4Op, bridge, R4).getResourceType());
        assertEquals(org.hl7.fhir.dstu3.model.Bundle.class,
                FhirProviderFactory.create(dstu3Op, bridge, DSTU3).getResourceType());
    }

    @Test
    void unknownOperationThrows() {
        FhirOperation op = new FhirOperation("R4", "my-facade", "Patient", "delete");
        assertThrows(IllegalArgumentException.class,
                () -> FhirProviderFactory.create(op, bridge, R4));
    }

    @Test
    void unknownResourceTypeThrows() {
        FhirOperation op = new FhirOperation("R4", "my-facade", "Unicorn", "read");
        assertThrows(IllegalArgumentException.class,
                () -> FhirProviderFactory.create(op, bridge, R4));
    }
}
