package com.viscosiety.fhir;

import org.frankframework.receivers.JavaListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class FhirOperationRegistryTest {

    @Mock JavaListener<?> listener;

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

        JavaListener<?> replacement = org.mockito.Mockito.mock(JavaListener.class);
        FhirOperationRegistry.register(op, replacement);

        assertSame(replacement, FhirOperationRegistry.getListener(op));
    }
}
