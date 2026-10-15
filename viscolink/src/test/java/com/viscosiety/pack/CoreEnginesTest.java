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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class CoreEnginesTest {

    private static String pomLemmaVersion() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher m = Pattern.compile("<artifactId>lemma-engine</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
        assertTrue(m.find(), "no lemma-engine version in the pom");
        return m.group(1).strip();
    }

    /** The descriptor reports the version of the jar that is actually loaded, which is the pom's. */
    @Test
    void lemmaVersionComesFromTheLoadedEngineAndMatchesThePom() throws IOException {
        assertEquals(Map.of("lemma", pomLemmaVersion()), CoreEngines.versions());
    }

    @Test
    void anEngineWithoutAReadableVersionIsLeftOut() {
        assertEquals(Map.of(), CoreEngines.versions(type -> null));
        assertEquals(Map.of(), CoreEngines.versions(type -> "  "));
        assertEquals(Map.of("lemma", "1.2.3"), CoreEngines.versions(type -> " 1.2.3 "));
    }
}
