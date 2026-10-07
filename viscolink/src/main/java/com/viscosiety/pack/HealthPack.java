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
import java.util.Map;
import java.util.Optional;

import com.viscosiety.components.ViscoLinkModule;

/**
 * The healthcare pack. It lives inside the viscolink module for now; the values are the ones
 * the Ladybug wiring, ViscoFlow and the console security registrar used to hardcode, so a
 * health image behaves exactly as before.
 *
 * <p>{@link #consoleViews()} is empty on purpose in M1: {@code DeploymentSpecifics.properties}
 * declares only {@code customViews.names=viscoLink}, which belongs to the core; ViscoFlow is a
 * {@code viscolink.views.*} landing-page entry, not a console custom view. The FHIR webservices
 * block is a script injected into the console's {@code index.html}, not a {@code customViews.*}
 * entry, so there is no health-specific view to carry over yet.</p>
 */
public final class HealthPack implements PackDescriptor {

    private static final SubjectIdentifier SUBJECT =
            new SubjectIdentifier("patientId", "patientId", "PatientId", "Patient", Optional.empty());

    @Override
    public String id() {
        return "health";
    }

    @Override
    public String displayName() {
        return "Healthcare";
    }

    @Override
    public String version() {
        return ViscoLinkModule.IMPLEMENTATION_VERSION;
    }

    @Override
    public SubjectIdentifier subject() {
        return SUBJECT;
    }

    @Override
    public List<ConsoleView> consoleViews() {
        return List.of();
    }

    @Override
    public List<String> frankOwnedPaths() {
        // The FHIR facade servlets answer on /fhir/ and authenticate themselves.
        return List.of("/fhir/");
    }

    @Override
    public Map<String, String> propertyDefaults() {
        return Map.of();
    }

    @Override
    public List<String> deidentificationStrategyIds() {
        return List.of("fhir-patient", "hl7v2");
    }
}
