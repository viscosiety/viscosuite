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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The staging the one Dockerfile COPYs from: {@code target/packs/{health,core}/},
 * {@code target/demo/{health,core}/} and {@code target/store/{viscostore,none}/}.
 */
class PackOverlayLayoutIT {

    private static final Path TARGET = Path.of("target");

    @Test
    void healthOverlayIsUnpackedWithItsIdentity() throws IOException {
        Path health = TARGET.resolve("packs/health");
        assertTrue(Files.isDirectory(health.resolve("WEB-INF/lib")), "target/packs/health/WEB-INF/lib");
        assertTrue(Files.isRegularFile(health.resolve("WEB-INF/classes/console/index.html")), "patched console index");
        try (Stream<Path> jars = Files.list(health.resolve("WEB-INF/lib"))) {
            assertTrue(jars.anyMatch(p -> p.getFileName().toString().startsWith("viscolink-pack-health-")),
                "the pack's own jar is in the overlay's WEB-INF/lib");
        }
        assertPackIdentity(health.resolve("WEB-INF/pack.properties"), "health");
    }

    @Test
    void coreHasItsIdentityAndNoLibraries() throws IOException {
        Path core = TARGET.resolve("packs/core");
        assertPackIdentity(core.resolve("WEB-INF/pack.properties"), "core");
        Path lib = core.resolve("WEB-INF/lib");
        assertTrue(Files.isDirectory(lib), "target/packs/core/WEB-INF/lib exists");
        try (Stream<Path> entries = Files.list(lib)) {
            assertEquals(0, entries.count(), "the core pack ships no libraries");
        }
    }

    @Test
    void demoConfigurationsAreStagedPerPack() {
        assertTrue(Files.isDirectory(TARGET.resolve("demo/health/hl7v2-to-fhir")), "target/demo/health/hl7v2-to-fhir/");
        assertTrue(Files.isRegularFile(TARGET.resolve("demo/core/echo/Configuration.xml")), "target/demo/core/echo/Configuration.xml");
    }

    @Test
    void storeIsTheWarOrNothing() throws IOException {
        assertTrue(Files.isRegularFile(TARGET.resolve("store/viscostore/viscostore.war")), "target/store/viscostore/viscostore.war");
        Path none = TARGET.resolve("store/none");
        assertTrue(Files.isDirectory(none), "target/store/none exists");
        try (Stream<Path> entries = Files.list(none)) {
            assertEquals(0, entries.count(), "target/store/none is empty (COPY cannot be conditional)");
        }
    }

    @Test
    void existingArtefactsStayWhereTheyWere() {
        assertTrue(Files.isRegularFile(TARGET.resolve("viscolink.war")), "target/viscolink.war");
        assertTrue(Files.isRegularFile(TARGET.resolve("viscostore.war")), "target/viscostore.war");
        assertTrue(Files.isDirectory(TARGET.resolve("drivers")), "target/drivers");
    }

    private static void assertPackIdentity(Path file, String id) throws IOException {
        assertTrue(Files.isRegularFile(file), file + " exists");
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            props.load(in);
        }
        assertEquals(id, props.getProperty("pack.id"), "pack.id in " + file);
        String version = props.getProperty("pack.version");
        assertTrue(version != null && !version.isBlank(), "pack.version in " + file);
        assertFalse(version.contains("@") || version.contains("${"), "pack.version is filtered, not a token: " + version);
        assertTrue(props.getProperty("pack.displayName") != null && !props.getProperty("pack.displayName").isBlank(),
            "pack.displayName in " + file);
    }
}
