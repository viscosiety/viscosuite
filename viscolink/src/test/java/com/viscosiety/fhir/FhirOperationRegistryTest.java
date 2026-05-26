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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class FhirOperationRegistryTest {

    @Mock FhirListener listener;

    @BeforeEach
    void clearRegistry() {
        FhirOperationRegistry.clearForTesting();
    }

    @Test
    void registeredListenerCanBeRetrievedByOperation() {
        FhirOperation op = new FhirOperation("R4", "my-facade", "Patient", "read");
        FhirOperationRegistry.register(op, listener);
        assertSame(listener, FhirOperationRegistry.getListener(op));
    }

    @Test
    void unregisteredOperationReturnsNull() {
        assertNull(FhirOperationRegistry.getListener(new FhirOperation("R4", "my-facade", "Observation", "read")));
    }

    @Test
    void getOperationsForFacadeReturnsOnlyMatchingFacade() {
        FhirOperation r4Read        = new FhirOperation("R4", "facade-a", "Patient", "read");
        FhirOperation r4Transaction = new FhirOperation("R4", "facade-a", "Bundle",  "bundle-transaction");
        FhirOperation otherFacade   = new FhirOperation("R4", "facade-b", "Patient", "read");
        FhirOperation dstu3Read     = new FhirOperation("DSTU3", "facade-a", "Patient", "read");

        FhirOperationRegistry.register(r4Read, listener);
        FhirOperationRegistry.register(r4Transaction, listener);
        FhirOperationRegistry.register(otherFacade, listener);
        FhirOperationRegistry.register(dstu3Read, listener);

        Set<FhirOperation> ops = FhirOperationRegistry.getOperationsForFacade("R4", "facade-a");
        assertEquals(Set.of(r4Read, r4Transaction), ops);
    }

    @Test
    void getOperationsForFacadeReturnsEmptySetWhenNoneRegistered() {
        assertTrue(FhirOperationRegistry.getOperationsForFacade("R4", "unknown-facade").isEmpty());
    }

    @Test
    void laterRegistrationOverwritesEarlierForSameKey() {
        FhirOperation op = new FhirOperation("R4", "my-facade", "Patient", "read");
        FhirOperationRegistry.register(op, listener);

        FhirListener replacement = Mockito.mock(FhirListener.class);
        FhirOperationRegistry.register(op, replacement);

        assertSame(replacement, FhirOperationRegistry.getListener(op));
    }
}
