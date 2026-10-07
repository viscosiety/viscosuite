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

import java.util.List;
import java.util.Optional;

/**
 * Test mirror of the health pack's values. The core cannot test-depend on the pack (the pack
 * compiles against the core's classes), so the D8 pins in this module use this mirror and the
 * pack's own HealthPackTest pins that the real HealthPack carries the same values. Keep both
 * in lock-step when one changes.
 */
public final class HealthValuesPack implements PackDescriptor {
    @Override public String id() { return "health"; }
    @Override public String displayName() { return "Healthcare"; }
    @Override public String version() { return "test"; }
    @Override public SubjectIdentifier subject() {
        return new SubjectIdentifier("patientId", "patientId", "PatientId", "Patient", Optional.empty());
    }
    @Override public List<ConsoleView> consoleViews() { return List.of(); }
    @Override public List<String> frankOwnedPaths() { return List.of("/fhir/"); }
    @Override public List<String> deidentificationStrategyIds() { return List.of("fhir-patient", "hl7v2"); }
}
