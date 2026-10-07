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

package com.viscosiety.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.viscosiety.components.ViscoLinkModule;

class HealthPackTest {

    private final HealthPack pack = new HealthPack();

    @Test
    void identity() {
        assertEquals("health", pack.id());
        assertEquals("Healthcare", pack.displayName());
    }

    @Test
    void subjectIsThePatientIdTheLadybugColumnAndViscoFlowUseToday() {
        SubjectIdentifier subject = pack.subject();
        assertEquals("patientId", subject.sessionKey());
        assertEquals("patientId", subject.metadataName());
        assertEquals("PatientId", subject.metadataLabel());
        assertEquals("Patient", subject.displayLabel());
        assertTrue(subject.format().isEmpty());
    }

    @Test
    void fhirStaysOwnedByTheFrankFramework() {
        assertEquals(List.of("/fhir/"), pack.frankOwnedPaths());
    }

    @Test
    void deidentificationStrategies() {
        assertEquals(List.of("fhir-patient", "hl7v2"), pack.deidentificationStrategyIds());
    }

    @Test
    void propertyDefaultsAreEmptyInM1() {
        assertEquals(Map.of(), pack.propertyDefaults());
    }

    @Test
    void versionIsTheViscoLinkModuleVersion() throws Exception {
        assertFalse(pack.version().isBlank());
        assertEquals(ViscoLinkModule.IMPLEMENTATION_VERSION, pack.version());
        assertEquals(pack.version(), new ViscoLinkModule().getModuleInformation().getVersion(),
                "the pack version and the module manifest must come from one source");
        assertEquals(pack.version(), new CorePack().version());
    }
}
