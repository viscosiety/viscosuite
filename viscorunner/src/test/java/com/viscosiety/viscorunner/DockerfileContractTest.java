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

package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The contract between the one {@code Dockerfile}, the compose files and the landing page, asserted on
 * the files as text (no docker needed; the docker proofs run them for real). Paths are relative to the
 * module directory, where surefire runs.
 */
class DockerfileContractTest {

    private static final Path MODULE = Path.of(".");
    private static final String LINK_IMAGE = "registry.git.viscosiety.com/public-applications/viscosuite/viscolink";

    private static String read(String relative) throws IOException {
        return Files.readString(MODULE.resolve(relative));
    }

    /** The Dockerfile's instruction lines: comments dropped, runs of blanks collapsed. */
    private static List<String> dockerfileInstructions() throws IOException {
        return read("Dockerfile").lines()
            .filter(line -> !line.stripLeading().startsWith("#"))
            .map(line -> line.replaceAll("\\s+", " ").strip())
            .collect(Collectors.toList());
    }

    private static boolean hasLine(List<String> lines, String exact) {
        return lines.contains(exact);
    }

    private static boolean matches(String text, String regex) {
        return Pattern.compile(regex, Pattern.MULTILINE).matcher(text).find();
    }

    // ---- the Dockerfile ----

    @Test
    void packAndStoreAreDeclaredBeforeTheirFirstUse() throws IOException {
        List<String> lines = dockerfileInstructions();
        int packArg = lines.indexOf("ARG PACK=health");
        int storeArg = lines.indexOf("ARG STORE=viscostore");
        assertTrue(packArg >= 0, "ARG PACK=health (no trailing comment: a Dockerfile reads it as more ARG names)");
        assertTrue(storeArg >= 0, "ARG STORE=viscostore (no trailing comment)");

        int firstPackUse = -1;
        int firstStoreUse = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (firstPackUse < 0 && lines.get(i).contains("${PACK}")) {
                firstPackUse = i;
            }
            if (firstStoreUse < 0 && lines.get(i).contains("${STORE}")) {
                firstStoreUse = i;
            }
        }
        assertTrue(firstPackUse > packArg, "ARG PACK precedes the first ${PACK}");
        assertTrue(firstStoreUse > storeArg, "ARG STORE precedes the first ${STORE}");
    }

    @Test
    void packStoreAndContextAreCopiedFromTheirVariants() throws IOException {
        List<String> lines = dockerfileInstructions();
        assertTrue(hasLine(lines, "COPY --chown=tomcat target/packs/${PACK}/ /opt/frank/webapp-overlay/viscolink/"),
            "the pack's overlay lands in the PreResources set of the /viscolink context");
        assertTrue(hasLine(lines, "COPY --chown=tomcat target/demo/${PACK}/ /opt/frank/demo-configurations/"),
            "the pack's demo configurations");
        assertTrue(hasLine(lines, "COPY --chown=tomcat target/store/${STORE}/ /usr/local/tomcat/webapps/"),
            "the store WAR (target/store/none/ is empty: nothing is copied)");
        assertTrue(hasLine(lines, "COPY --chown=tomcat conf/context-${STORE}.xml /usr/local/tomcat/conf/context.xml"),
            "the Tomcat context follows the store");
        assertTrue(hasLine(lines, "COPY --chown=tomcat target/viscolink.war /usr/local/tomcat/webapps/viscolink.war"),
            "the core WAR");
    }

    @Test
    void theRuntimeDirectoriesTheOverlayAndTheJarNeedExist() throws IOException {
        String dockerfile = read("Dockerfile");
        for (String dir : List.of("/opt/frank/lib", "/opt/frank/webapp-overlay/viscolink", "/opt/frank/demo-configurations")) {
            assertTrue(dockerfile.contains("mkdir -p " + dir), "mkdir -p " + dir);
        }
    }

    @Test
    void theLandingPageIsStampedFromThePackOnTheOverlayInTheImage() throws IOException {
        String dockerfile = read("Dockerfile");
        assertTrue(dockerfile.contains("WEB-INF/pack.properties"), "reads the pack's identity from the overlay");
        for (String placeholder : List.of("%%PACK_TAGLINE%%", "%%PACK_LINK_BLURB%%", "%%PACK_STAMP%%", "%%BUILD_TIMESTAMP%%")) {
            assertTrue(dockerfile.contains(placeholder), "the Dockerfile fills " + placeholder);
        }
        assertTrue(dockerfile.indexOf("target/packs/${PACK}/") < dockerfile.indexOf("WEB-INF/pack.properties"),
            "the overlay is copied before the stamp reads it");
    }

    @Test
    void theWarIsOnlyCheckedForTheStoreThatIsInTheImage() throws IOException {
        String dockerfile = read("Dockerfile");
        assertTrue(dockerfile.contains("\"${STORE}\" != \"none\""), "the viscostore.war stub check is conditional on the store");
    }

    @Test
    void oneDockerfileForTheRunnerAndNoMoreViscolinkDockerfile() {
        assertFalse(Files.exists(MODULE.resolve("Dockerfile.viscolink")), "Dockerfile.viscolink is replaced by Dockerfile");
        assertTrue(Files.exists(MODULE.resolve("Dockerfile.viscostore")), "Dockerfile.viscostore stays");
    }

    @Test
    void contextFilesAreNamedAfterTheStore() throws IOException {
        assertTrue(Files.exists(MODULE.resolve("conf/context-viscostore.xml")), "conf/context-viscostore.xml");
        assertTrue(Files.exists(MODULE.resolve("conf/context-none.xml")), "conf/context-none.xml");
        assertFalse(Files.exists(MODULE.resolve("conf/context-viscosuite.xml")), "renamed to context-viscostore.xml");
        assertFalse(Files.exists(MODULE.resolve("conf/context-viscolink.xml")), "renamed to context-none.xml");
        assertTrue(read("conf/context-viscostore.xml").contains("jdbc/viscostore"), "the suite context carries jdbc/viscostore");
        assertFalse(read("conf/context-none.xml").contains("jdbc/viscostore"), "the store-less context does not");
    }

    @Test
    void thePreResourcesCommentsAreNotCalledPostResources() throws IOException {
        assertFalse(read("Dockerfile").contains("PostResources"), "the overlay is a PreResources set");
        assertFalse(read("Dockerfile.viscostore").contains("PostResources"), "the overlay is a PreResources set");
    }

    // ---- compose ----

    @Test
    void suiteComposeBuildsTheHealthPackWithTheStore() throws IOException {
        String compose = read("docker-compose.yml");
        assertTrue(matches(compose, "^\\s+dockerfile:\\s*Dockerfile\\s*$"), "builds the one Dockerfile");
        assertTrue(matches(compose, "^\\s+PACK:\\s*health\\s*$"), "PACK: health");
        assertTrue(matches(compose, "^\\s+STORE:\\s*viscostore\\s*$"), "STORE: viscostore");
    }

    @Test
    void viscolinkComposeBuildsTheHealthPackWithoutAStore() throws IOException {
        String compose = read("docker-compose.viscolink.yml");
        assertTrue(matches(compose, "^\\s+dockerfile:\\s*Dockerfile\\s*$"), "builds the one Dockerfile");
        assertTrue(matches(compose, "^\\s+PACK:\\s*health\\s*$"), "PACK: health");
        assertTrue(matches(compose, "^\\s+STORE:\\s*none\\s*$"), "STORE: none");
        assertTrue(matches(compose, "^\\s+image:\\s*" + Pattern.quote(LINK_IMAGE) + ":latest\\s*$"), "image ...:latest");
        assertTrue(compose.contains("2575:2575"), "the MLLP port stays");
    }

    @Test
    void coreComposeBuildsTheCorePackWithoutAStore() throws IOException {
        String compose = read("docker-compose.core.yml");
        assertTrue(matches(compose, "^\\s+dockerfile:\\s*Dockerfile\\s*$"), "builds the one Dockerfile");
        assertTrue(matches(compose, "^\\s+PACK:\\s*core\\s*$"), "PACK: core");
        assertTrue(matches(compose, "^\\s+STORE:\\s*none\\s*$"), "STORE: none");
        assertTrue(matches(compose, "^\\s+image:\\s*" + Pattern.quote(LINK_IMAGE) + ":latest-core\\s*$"), "image ...:latest-core");
        assertTrue(matches(compose, "^\\s{2}viscolink:\\s*$"), "the service keeps the name viscolink");
        assertTrue(compose.contains("8180:8080"), "console port");
        assertFalse(compose.contains("2575"), "no MLLP port: the core has no HL7v2 listener");
        assertTrue(compose.contains("/viscolink/api/echo"), "the header says what the demo is");
    }

    @Test
    void theStoreLessStacksGetALadybugDatabase() throws IOException {
        for (String file : List.of("docker-compose.viscolink.yml", "docker-compose.core.yml")) {
            assertTrue(read(file).contains("./postgres/init-ladybug.sql:/docker-entrypoint-initdb.d/init-ladybug.sql:ro"),
                file + " mounts the ladybug init");
        }
        assertTrue(read("postgres/init-ladybug.sql").contains("ladybug"), "postgres/init-ladybug.sql creates it");
    }

    @Test
    void theLadybugInitSurvivesTheSuiteComposeThatMountsTheWholeDirectory() throws IOException {
        String compose = read("docker-compose.yml");
        assertTrue(compose.contains("./postgres:/docker-entrypoint-initdb.d:ro"), "the suite compose mounts the whole postgres/ directory");
        String sql = read("postgres/init-ladybug.sql");
        assertTrue(sql.contains("NOT EXISTS") && sql.contains("\\gexec"),
            "init-databases.sql already creates ladybug; a plain CREATE DATABASE would abort the suite's first start");
    }

    // ---- landing page ----

    @Test
    void theLandingPageTakesItsWordsFromThePack() throws IOException {
        String page = read("src/webapp/ROOT/index.html");
        for (String placeholder : List.of("%%PACK_TAGLINE%%", "%%PACK_LINK_BLURB%%", "%%PACK_STAMP%%", "%%BUILD_TIMESTAMP%%")) {
            assertTrue(page.contains(placeholder), "index.html carries " + placeholder);
        }
        assertFalse(page.contains("Healthcare integration platform"), "the tagline is the pack's, not the page's");
        assertTrue(page.contains("Viscosiety B.V.%%PACK_STAMP%%%%BUILD_TIMESTAMP%%"), "the footer: pack stamp, then build stamp");
    }

    @Test
    void theViscostoreOnlyImageFillsThePlaceholdersToo() throws IOException {
        String dockerfile = read("Dockerfile.viscostore");
        for (String placeholder : List.of("%%PACK_TAGLINE%%", "%%PACK_LINK_BLURB%%", "%%PACK_STAMP%%", "%%BUILD_TIMESTAMP%%")) {
            assertTrue(dockerfile.contains(placeholder),
                "Dockerfile.viscostore shares the ROOT page, so it fills " + placeholder + " (a literal would show)");
        }
    }
}
