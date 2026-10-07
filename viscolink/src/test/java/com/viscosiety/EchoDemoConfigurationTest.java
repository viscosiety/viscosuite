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

package com.viscosiety;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class EchoDemoConfigurationTest {

    @Test
    void theCoreDemoRecordsTheSubjectIdentifier() throws Exception {
        String xml = Files.readString(Path.of("demo-configurations/echo/Configuration.xml"));
        assertTrue(xml.contains("name=\"subjectId\""), "the echo adapter must put subjectId in the session so Ladybug records it");
        assertTrue(xml.contains("uriPattern=\"echo\""));
        assertTrue(!xml.toLowerCase().contains("fhir") && !xml.toLowerCase().contains("hl7"), "neutral demo");
    }
}
