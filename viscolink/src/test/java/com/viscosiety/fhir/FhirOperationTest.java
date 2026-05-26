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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FhirOperationTest {

    @Test
    void toStringFormatsAsVersionColonFacadeColonResourceColonOperation() {
        FhirOperation op = new FhirOperation("R4", "my-facade", "Patient", "read");
        assertEquals("R4:my-facade:Patient:read", op.toString());
    }

    @Test
    void recordEqualityMatchesOnAllFourFields() {
        FhirOperation a = new FhirOperation("R4", "my-facade", "Patient", "read");
        FhirOperation b = new FhirOperation("R4", "my-facade", "Patient", "read");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentVersionIsNotEqual() {
        assertNotEquals(
                new FhirOperation("R4",    "my-facade", "Patient", "read"),
                new FhirOperation("DSTU3", "my-facade", "Patient", "read"));
    }

    @Test
    void differentFacadeIsNotEqual() {
        assertNotEquals(
                new FhirOperation("R4", "facade-a", "Patient", "read"),
                new FhirOperation("R4", "facade-b", "Patient", "read"));
    }

    @Test
    void differentResourceIsNotEqual() {
        assertNotEquals(
                new FhirOperation("R4", "my-facade", "Patient", "read"),
                new FhirOperation("R4", "my-facade", "Bundle",  "read"));
    }

    @Test
    void differentOperationIsNotEqual() {
        assertNotEquals(
                new FhirOperation("R4", "my-facade", "Bundle", "read"),
                new FhirOperation("R4", "my-facade", "Bundle", "bundle-transaction"));
    }
}
