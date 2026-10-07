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

package com.viscosiety.pack.health;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The pack's build stages the Frank!Console page with its script tag (served from the overlay's WEB-INF/classes). */
class ConsoleIndexPatchTest {

    @Test
    void theStagedConsolePageLoadsTheFhirScript() throws Exception {
        Path page = Path.of("target/console-patched/console/index.html");
        assertTrue(Files.exists(page), "the pom's patch-console-index execution must run before the tests (process-resources)");
        String html = Files.readString(page);
        assertTrue(html.contains("<script src=\"fhir-webservices.js\"></script></body>"), "script tag before </body>");
        assertTrue(Files.exists(Path.of("target/classes/console/fhir-webservices.js")), "the script ships in the pack jar");
    }
}
