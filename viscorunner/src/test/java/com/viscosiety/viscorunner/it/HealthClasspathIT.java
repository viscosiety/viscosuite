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

package com.viscosiety.viscorunner.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * D8 for the class path: the core WAR's libraries plus the health overlay's libraries are exactly
 * the libraries the one WAR shipped before the split (golden/health-webapp-libs.txt, frozen), and
 * no jar is in both (duplicate classes on the webapp class loader).
 */
class HealthClasspathIT {

    private static final String PACK_JAR_PREFIX = "viscolink-pack-health-";

    @Test
    void warLibsAndPackLibsAreTodaysLibsWithNoOverlap() throws Exception {
        Set<String> war = new TreeSet<>();
        try (ZipFile zip = new ZipFile("target/viscolink.war")) {
            zip.stream().map(e -> e.getName())
                .filter(n -> n.startsWith("WEB-INF/lib/") && n.endsWith(".jar"))
                .map(n -> n.substring("WEB-INF/lib/".length()))
                .forEach(war::add);
        }
        Set<String> pack = new TreeSet<>();
        try (Stream<Path> files = Files.list(Path.of("target/packs/health/WEB-INF/lib"))) {
            files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar")).forEach(pack::add);
        }
        Set<String> golden = new TreeSet<>(Files.readAllLines(Path.of("src/test/resources/golden/health-webapp-libs.txt")));

        Set<String> overlap = new HashSet<>(war);
        overlap.retainAll(pack);
        assertTrue(overlap.isEmpty(), "jars in BOTH the WAR and the pack overlay (mark them provided in packs/health/pom.xml): " + overlap);

        Set<String> union = new TreeSet<>(war);
        union.addAll(pack);
        union.removeIf(n -> n.startsWith(PACK_JAR_PREFIX));
        assertEquals(golden, union, "WAR ∪ pack must be exactly today's libraries (missing = lost at runtime; extra = a new transitive)");
    }
}
