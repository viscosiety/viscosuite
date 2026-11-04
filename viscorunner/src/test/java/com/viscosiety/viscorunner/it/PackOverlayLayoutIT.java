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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The staging the Dockerfile and the ITs read from: {@code target/packs/health/} (the unpacked overlay the
 * launcher ITs run), {@code target/demo/{health,core}/} and, for the fat image, {@code target/components/}
 * plus the two local components lists.
 */
class PackOverlayLayoutIT {

    private static final Path TARGET = Path.of("target");

    @Test
    void healthOverlayIsUnpackedForTheLauncherIts() throws IOException {
        Path health = TARGET.resolve("packs/health");
        assertTrue(Files.isDirectory(health.resolve("WEB-INF/lib")), "target/packs/health/WEB-INF/lib");
        assertTrue(Files.isRegularFile(health.resolve("WEB-INF/classes/console/index.html")), "patched console index");
        try (Stream<Path> jars = Files.list(health.resolve("WEB-INF/lib"))) {
            assertTrue(jars.anyMatch(p -> p.getFileName().toString().startsWith("viscolink-pack-health-")),
                "the pack's own jar is in the overlay's WEB-INF/lib");
        }
        assertFalse(Files.exists(health.resolve("WEB-INF/pack.properties")), "no pack.properties: the identity is HealthPack.java");
        assertFalse(Files.exists(TARGET.resolve("packs/core")), "no core staging: the core is the absence of an overlay");
        assertFalse(Files.exists(TARGET.resolve("store")), "no store staging: the store is a component");
    }

    @Test
    void demoConfigurationsAreStagedPerPack() {
        assertTrue(Files.isDirectory(TARGET.resolve("demo/health/hl7v2-to-fhir")), "target/demo/health/hl7v2-to-fhir/");
        assertTrue(Files.isRegularFile(TARGET.resolve("demo/core/echo/Configuration.xml")), "target/demo/core/echo/Configuration.xml");
    }

    @Test
    void componentsAreStagedWithTheirLists() throws IOException {
        assertTrue(Files.isRegularFile(TARGET.resolve("components/viscostore.war")), "target/components/viscostore.war");
        assertTrue(Files.isRegularFile(TARGET.resolve("components/viscolink-pack-health-overlay.zip")), "target/components/viscolink-pack-health-overlay.zip");
        List<String> suite = Files.readAllLines(TARGET.resolve("components-suite.list"));
        List<String> link = Files.readAllLines(TARGET.resolve("components-viscolink.list"));
        assertTrue(suite.get(0).matches("release \\S+"), "suite list header: " + suite.get(0));
        assertEquals(3, suite.size(), "release + store + pack-health: " + suite);
        assertEquals(2, link.size(), "release + pack-health: " + link);
        assertEquals(suite.get(0), link.get(0), "same release");
        assertTrue(suite.get(1).matches("store war \\S+ file:///opt/frank/component-files/viscostore\\.war [0-9a-f]{64} fhir-store"), suite.get(1));
        assertTrue(suite.get(2).matches("pack-health overlay \\S+ file:///opt/frank/component-files/viscolink-pack-health-overlay\\.zip [0-9a-f]{64} overlay/runtime"), suite.get(2));
        assertEquals(suite.get(2), link.get(1), "the pack line is the same in both lists");
        String storeSha = suite.get(1).split(" ")[4];
        assertEquals(sha256(TARGET.resolve("components/viscostore.war")), storeSha, "the list's sha256 is the staged file's");
    }

    @Test
    void theOverlayZipCarriesNoPackProperties() throws IOException {
        try (ZipFile zip = new ZipFile(TARGET.resolve("components/viscolink-pack-health-overlay.zip").toFile())) {
            assertTrue(zip.stream().noneMatch(e -> e.getName().endsWith("WEB-INF/pack.properties")), "pack.properties is gone from the overlay");
            assertTrue(zip.stream().anyMatch(e -> e.getName().startsWith("WEB-INF/lib/viscolink-pack-health-")), "the pack jar is there");
        }
    }

    @Test
    void existingArtefactsStayWhereTheyWere() {
        assertTrue(Files.isRegularFile(TARGET.resolve("viscolink.war")), "target/viscolink.war");
        assertTrue(Files.isRegularFile(TARGET.resolve("viscostore.war")), "target/viscostore.war");
        assertTrue(Files.isDirectory(TARGET.resolve("drivers")), "target/drivers");
    }

    /** The staged list feeds the real fetcher (paths rewritten to this checkout): Ant's checksum and the fetcher's agree. */
    @Test
    void theStagedListFeedsTheFetcher(@TempDir Path tmp) throws Exception {
        String list = Files.readString(TARGET.resolve("components-suite.list"))
                .replace("file:///opt/frank/component-files/", "file://" + TARGET.resolve("components").toAbsolutePath() + "/");
        Path file = tmp.resolve("components.list");
        Files.writeString(file, list);
        ProcessBuilder pb = new ProcessBuilder("sh", Path.of("src/scripts/fetch-components").toAbsolutePath().toString(), file.toString())
                .redirectErrorStream(true).directory(tmp.toFile());
        pb.environment().put("VF_COMPONENTS_DIR", tmp.resolve("components").toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        assertTrue(Files.isRegularFile(tmp.resolve("components/webapps/fhir-store.war")));
        assertTrue(Files.isDirectory(tmp.resolve("components/overlay/runtime/WEB-INF/lib")));
    }

    private static String sha256(Path file) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
