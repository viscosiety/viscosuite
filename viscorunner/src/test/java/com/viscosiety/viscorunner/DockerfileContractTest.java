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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
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

    /** A SQL file's statements: the {@code --} comment lines dropped, so prose in a header cannot satisfy an assertion. */
    private static String sqlStatements(String relative) throws IOException {
        return read(relative).lines()
            .filter(line -> !line.stripLeading().startsWith("--"))
            .collect(Collectors.joining("\n"));
    }

    /** The {@code <Resource name="...">} names a Tomcat context file declares, XML comments dropped. */
    private static Set<String> contextResourceNames(String relative) throws IOException {
        String xml = read(relative).replaceAll("(?s)<!--.*?-->", "");
        Matcher resource = Pattern.compile("<Resource\\s+name=\"([^\"]+)\"").matcher(xml);
        Set<String> names = new TreeSet<>();
        while (resource.find()) {
            names.add(resource.group(1));
        }
        return names;
    }

    private static List<String> stage(List<String> lines, String from, String to) {
        int start = lines.indexOf(from);
        int end = to == null ? lines.size() : lines.indexOf(to);
        assertTrue(start >= 0, from);
        assertTrue(end > start, to);
        return lines.subList(start, end);
    }

    // ---- the two stages ----

    @Test
    void oneDockerfileTwoStages() throws IOException {
        List<String> lines = dockerfileInstructions();
        assertTrue(hasLine(lines, "FROM tomcat:11.0.14-jre25 AS viscobase"), "stage viscobase on the Frank!Framework base image");
        assertTrue(hasLine(lines, "FROM viscobase AS fat"), "stage fat builds on viscobase");
        assertTrue(lines.stream().noneMatch(l -> l.contains("${PACK}") || l.contains("${STORE}") || l.startsWith("ARG PACK") || l.startsWith("ARG STORE")),
            "no PACK/STORE variants: composition is a components list");
    }

    @Test
    void viscobaseHoldsNoComponent() throws IOException {
        List<String> base = stage(dockerfileInstructions(), "FROM tomcat:11.0.14-jre25 AS viscobase", "FROM viscobase AS fat");
        for (String forbidden : List.of("target/packs/", "target/store/", "fhir-store.war", "webapp-overlay/viscolink/WEB-INF", "components.list")) {
            assertTrue(base.stream().noneMatch(l -> l.contains(forbidden)), "viscobase never copies " + forbidden);
        }
        assertTrue(hasLine(base, "COPY --chown=tomcat target/viscolink.war /usr/local/tomcat/webapps/runtime.war"), "the core WAR, served at /runtime");
        assertTrue(hasLine(base, "COPY --chown=tomcat conf/Catalina/localhost/viscolink.xml /usr/local/tomcat/conf/Catalina/localhost/runtime.xml"),
            "the overlay context file follows the WAR's name");
    }

    @Test
    void theOneGlobalContextDeclaresEveryDatasource() throws IOException {
        List<String> lines = dockerfileInstructions();
        assertTrue(hasLine(lines, "COPY --chown=tomcat conf/context.xml /usr/local/tomcat/conf/context.xml"), "one context for every composition");
        assertFalse(Files.exists(MODULE.resolve("conf/context-none.xml")), "context-none.xml is gone");
        assertFalse(Files.exists(MODULE.resolve("conf/context-viscostore.xml")), "renamed to context.xml");
        assertEquals(Set.of("jdbc/viscolink", "jdbc/viscostore", "jdbc/ladybug"), contextResourceNames("conf/context.xml"),
            "jdbc/viscostore stays declared: Tomcat creates it lazily and the Frank!Framework never enumerates JNDI, so a runtime without a store never sees it");
    }

    @Test
    void theFetcherAndThePlacerShipInViscobase() throws IOException {
        List<String> lines = dockerfileInstructions();
        List<String> base = stage(lines, "FROM tomcat:11.0.14-jre25 AS viscobase", "FROM viscobase AS fat");
        assertTrue(hasLine(base, "COPY --chown=tomcat src/scripts/fetch-components src/scripts/place-components /opt/frank/bin/"), "the two scripts");
        int unzip = -1, user = -1;
        for (int i = 0; i < base.size(); i++) {
            if (base.get(i).startsWith("RUN ") && base.get(i).contains("apt-get install") && base.get(i).contains("unzip")) unzip = i;
            if (base.get(i).equals("USER tomcat")) user = i;
        }
        assertTrue(unzip >= 0 && user > unzip, "unzip is installed as root, before USER tomcat");
        assertTrue(base.get(unzip).contains(" curl ") && base.get(unzip).contains(" ca-certificates "),
            "curl is installed with it: the fetcher, the HEALTHCHECK and the smoke test call it, and the Java 25 base image does not ship it");
        assertTrue(read("src/scripts/entrypoint.sh").contains("/opt/frank/bin/place-components"), "the entrypoint places the components at every start");
        assertTrue(Files.isExecutable(MODULE.resolve("src/scripts/fetch-components")) && Files.isExecutable(MODULE.resolve("src/scripts/place-components")),
            "both scripts are executable in git");
    }

    @Test
    void theFatStageFetchesFromTheListAndPlaces() throws IOException {
        List<String> fat = stage(dockerfileInstructions(), "FROM viscobase AS fat", null);
        assertTrue(hasLine(fat, "ARG COMPONENTS_LIST=target/components-suite.list"), "the list is a build argument (a path in the build context)");
        assertTrue(hasLine(fat, "COPY --chown=tomcat ${COMPONENTS_LIST} /opt/frank/components.list"), "the list itself");
        // a RUN spans several lines (backslash continuations): look at the whole instruction
        List<String> fatInstructions = String.join("\n", fat).replace(" \\\n", " ").lines().collect(Collectors.toList());
        assertTrue(fatInstructions.stream().anyMatch(l -> l.startsWith("RUN ") && l.contains("--mount=type=secret,id=job_token,uid=2000")
                && l.contains("/run/secrets/job_token") && l.contains("/opt/frank/bin/fetch-components /opt/frank/components.list")
                && l.contains("/opt/frank/bin/place-components")),
            "fetch then place, with the job token as a BuildKit secret (never a build argument, never a layer)");
        assertTrue(fatInstructions.stream().anyMatch(l -> l.startsWith("RUN --mount=type=bind,source=target/components,target=/opt/frank/component-files,ro ")),
            "local artifacts for file:// lists are bind-mounted read-only, never copied into a layer");
        assertTrue(fatInstructions.stream().anyMatch(l -> l.startsWith("RUN ")
                && l.endsWith("rm -rf /opt/frank/components/webapps /opt/frank/components/overlay /opt/frank/components/files")),
            "after placing, the fat image keeps only MANIFEST and components.json");
        assertTrue(fat.stream().noneMatch(l -> l.startsWith("COPY") && l.contains("target/components/")), "no COPY of the local artifacts");
        assertTrue(Files.exists(MODULE.resolve(".dockerignore")) && read(".dockerignore").lines().anyMatch(l -> l.strip().equals("target/publish/")),
            ".dockerignore keeps target/publish/ (~850 MB) out of the build context");
        assertTrue(fat.stream().noneMatch(l -> l.startsWith("ARG") && l.contains("TOKEN")), "no token build argument");
    }

    @Test
    void theDemoSetsOfBothPacksShipInViscobase() throws IOException {
        List<String> base = stage(dockerfileInstructions(), "FROM tomcat:11.0.14-jre25 AS viscobase", "FROM viscobase AS fat");
        assertTrue(hasLine(base, "COPY --chown=tomcat target/demo/health/ /opt/frank/demo-configurations/health/"), "the health demo set");
        assertTrue(hasLine(base, "COPY --chown=tomcat target/demo/core/ /opt/frank/demo-configurations/core/"), "the core demo set");
    }

    @Test
    void theRuntimeDirectoriesTheOverlayAndTheJarNeedExist() throws IOException {
        String dockerfile = read("Dockerfile");
        for (String dir : List.of("/opt/frank/lib", "/opt/frank/bin", "/opt/frank/components", "/opt/frank/webapp-overlay/viscolink",
                "/opt/frank/webapp-overlay/console", "/opt/frank/demo-configurations")) {
            assertTrue(dockerfile.contains("mkdir -p " + dir), "mkdir -p " + dir);
        }
    }

    @Test
    void theWarStubCheckCoversTheRuntimeOnly() throws IOException {
        String dockerfile = read("Dockerfile");
        assertTrue(dockerfile.contains("stat -c%s /usr/local/tomcat/webapps/runtime.war"), "runtime.war is checked (a stale .m2 stub)");
        assertFalse(dockerfile.contains("fhir-store.war"), "a fetched WAR is sha256-verified instead");
    }

    @Test
    void oneDockerfileForTheRunnerAndNoMoreViscolinkDockerfile() {
        assertFalse(Files.exists(MODULE.resolve("Dockerfile.viscolink")), "Dockerfile.viscolink is replaced by Dockerfile");
        assertTrue(Files.exists(MODULE.resolve("Dockerfile.viscostore")), "Dockerfile.viscostore stays");
    }

    @Test
    void thePackIdentityFilesAreGone() {
        assertFalse(Files.exists(MODULE.resolve("src/packs")), "src/packs/core (its pack.properties) is gone: the core is the absence of an overlay");
        assertFalse(Files.exists(MODULE.resolve("../packs/health/src/main/overlay")), "the health pack carries no pack.properties: its identity is HealthPack.java");
    }

    // ---- neutral web paths (2026-10-28) ----

    @Test
    void theOldPathsAreServedThroughTheHostRewriteValve() throws IOException {
        String server = read("conf/server.xml");
        assertTrue(server.matches("(?s).*<Host [^>]*>.*<Valve className=\"org\\.apache\\.catalina\\.valves\\.rewrite\\.RewriteValve\" />.*</Host>.*"),
            "the RewriteValve sits in the Host (a Host-level rewrite can change the context)");
        List<String> lines = dockerfileInstructions();
        assertTrue(hasLine(lines,
                "COPY --chown=tomcat conf/Catalina/localhost/rewrite.config /usr/local/tomcat/conf/Catalina/localhost/rewrite.config"),
            "the rules ship with the runner image");
        assertTrue(read("Dockerfile.viscostore").contains(
                "COPY --chown=tomcat conf/Catalina/localhost/rewrite.config /usr/local/tomcat/conf/Catalina/localhost/rewrite.config"),
            "and with the store-only image");
        assertTrue(read("Dockerfile.viscostore").contains("COPY --chown=tomcat target/viscostore.war /usr/local/tomcat/webapps/fhir-store.war"),
            "the store-only image serves the store at /fhir-store");
    }

    @Test
    void theRewriteRulesRedirectBrowsersAndServeEverythingElseInPlace() throws IOException {
        String rules = read("conf/Catalina/localhost/rewrite.config");
        for (String line : rules.split("\n")) {
            assertFalse(line.trim().startsWith("#"), "no comment lines: they made Tomcat 11 skip the conditional rules");
        }
        for (String[] pair : new String[][] {{"viscolink", "runtime"}, {"viscostore", "fhir-store"}}) {
            String browser = "RewriteCond %{REQUEST_METHOD} ^GET$\nRewriteCond %{HTTP:Accept} text/html\n"
                    + "RewriteRule ^/" + pair[0] + "/(.*)$ /" + pair[1] + "/$1 [R=302,L]\n";
            assertTrue(rules.contains(browser), "a browser page load on /" + pair[0] + " is redirected");
            assertTrue(rules.contains("RewriteRule ^/" + pair[0] + "/(.*)$ /" + pair[1] + "/$1 [L]\n"),
                "every other request on /" + pair[0] + " is served in place");
            assertTrue(rules.indexOf(browser) < rules.indexOf("RewriteRule ^/" + pair[0] + "/(.*)$ /" + pair[1] + "/$1 [L]"),
                "the redirect rule comes first");
        }
    }

    @Test
    void theHealthchecksUseTheNewPaths() throws IOException {
        assertTrue(read("Dockerfile").contains("http://localhost:8080/runtime/iaf/api/server/health"), "runner healthcheck");
        assertTrue(read("Dockerfile.viscostore").contains("http://localhost:8080/fhir-store/fhir/metadata"), "store healthcheck");
    }

    @Test
    void theLemmaNativeIsPrebuiltForTheBuildArchitecture() throws IOException {
        List<String> lines = dockerfileInstructions();
        assertTrue(hasLine(lines, "COPY --chown=tomcat target/native/ /tmp/lemma-native/"),
            "both Linux natives are staged by the pom");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("RUN ") && l.contains("uname -m")
                && l.contains("x86_64-unknown-linux-gnu") && l.contains("aarch64-unknown-linux-gnu")
                && l.contains("/opt/frank/native/liblemma_jni.so")),
            "the RUN step picks the native for the build machine's architecture and fails on any other");
        assertTrue(hasLine(lines, "ENV LEMMA_JNI_LIBRARY=/opt/frank/native/liblemma_jni.so"),
            "the binding loads the prebuilt library instead of extracting one at run time");
        assertTrue(lines.stream().noneMatch(l -> l.contains("/opt/viscoforge")),
            "never image content under /opt/viscoforge (a volume on the platform)");
    }

    @Test
    void everyTomcatContextDeclaresTheLadybugDatasource() throws IOException {
        // Every compose stack sets ladybug.jdbc.datasource=jdbc/ladybug; a context without it fails the
        // deploy of /viscolink (bean ladybugDataSource: unable to find resource [jdbc/ladybug]).
        for (String file : List.of("conf/context.xml", "demo-conf/context.xml")) {
            assertTrue(contextResourceNames(file).contains("jdbc/ladybug"), file + " declares jdbc/ladybug");
        }
    }

    @Test
    void theDemoContextDeclaresEveryDatasourceOfTheSuiteContextItReplaces() throws IOException {
        // docker-compose.demo.yml mounts demo-conf/context.xml over the suite image's conf/context.xml.
        Set<String> suite = contextResourceNames("conf/context.xml");
        Set<String> demo = contextResourceNames("demo-conf/context.xml");
        assertTrue(demo.containsAll(suite), "demo-conf/context.xml declares " + demo + ", the suite context " + suite);
        assertTrue(read("docker-compose.demo.yml").contains("./demo-conf/context.xml:/usr/local/tomcat/conf/context.xml:ro"),
            "the demo compose still replaces the context (otherwise this test guards nothing)");
    }

    @Test
    void thePreResourcesCommentsAreNotCalledPostResources() throws IOException {
        assertFalse(read("Dockerfile").contains("PostResources"), "the overlay is a PreResources set");
        assertFalse(read("Dockerfile.viscostore").contains("PostResources"), "the overlay is a PreResources set");
    }

    // ---- compose ----

    @Test
    void suiteComposeBuildsTheFatStageWithTheSuiteList() throws IOException {
        String compose = read("docker-compose.yml");
        assertTrue(matches(compose, "^\\s+dockerfile:\\s*Dockerfile\\s*$"), "builds the one Dockerfile");
        assertTrue(matches(compose, "^\\s+target:\\s*fat\\s*$"), "target: fat");
        assertTrue(matches(compose, "^\\s+COMPONENTS_LIST:\\s*target/components-suite\\.list\\s*$"), "store + pack-health");
        assertFalse(compose.contains("PACK:") || compose.contains("STORE:"), "no PACK/STORE build arguments");
    }

    @Test
    void viscolinkComposeBuildsTheFatStageWithTheViscolinkList() throws IOException {
        String compose = read("docker-compose.viscolink.yml");
        assertTrue(matches(compose, "^\\s+target:\\s*fat\\s*$"), "target: fat");
        assertTrue(matches(compose, "^\\s+COMPONENTS_LIST:\\s*target/components-viscolink\\.list\\s*$"), "pack-health only");
        assertTrue(matches(compose, "^\\s+image:\\s*" + Pattern.quote(LINK_IMAGE) + ":latest\\s*$"), "image ...:latest");
        assertTrue(compose.contains("2575:2575"), "the MLLP port stays");
    }

    @Test
    void coreComposeBuildsViscobase() throws IOException {
        String compose = read("docker-compose.core.yml");
        assertTrue(matches(compose, "^\\s+target:\\s*viscobase\\s*$"), "the core IS viscobase");
        assertFalse(compose.contains("COMPONENTS_LIST"), "no list: no components");
        assertTrue(matches(compose, "^\\s+image:\\s*" + Pattern.quote(LINK_IMAGE) + ":latest-core\\s*$"), "image ...:latest-core (an alias of viscobase)");
        assertTrue(matches(compose, "^\\s{2}viscolink:\\s*$"), "the service keeps the name viscolink");
        assertTrue(compose.contains("8180:8080"), "console port");
        assertFalse(compose.contains("2575"), "no MLLP port: the core has no HL7v2 listener");
        assertTrue(compose.contains("/runtime/api/echo"), "the header says what the demo is");
    }

    @Test
    void theStoreLessStacksGetALadybugDatabase() throws IOException {
        for (String file : List.of("docker-compose.viscolink.yml", "docker-compose.core.yml")) {
            assertTrue(read(file).contains("./postgres/init-ladybug.sql:/docker-entrypoint-initdb.d/init-ladybug.sql:ro"),
                file + " mounts the ladybug init");
        }
        assertTrue(sqlStatements("postgres/init-ladybug.sql").contains("CREATE DATABASE ladybug OWNER visco"),
            "postgres/init-ladybug.sql creates it (in a statement, not in a comment)");
    }

    @Test
    void theLadybugInitSurvivesTheSuiteComposeThatMountsTheWholeDirectory() throws IOException {
        String compose = read("docker-compose.yml");
        assertTrue(compose.contains("./postgres:/docker-entrypoint-initdb.d:ro"), "the suite compose mounts the whole postgres/ directory");
        String sql = sqlStatements("postgres/init-ladybug.sql");
        assertTrue(matches(sql, "WHERE NOT EXISTS \\(SELECT FROM pg_database WHERE datname = 'ladybug'\\)\\\\gexec"),
            "init-databases.sql already creates ladybug; the CREATE DATABASE must be guarded and run by \\gexec"
                + " (comments do not count: the header says the same words)");
        assertFalse(matches(sql, "^\\s*CREATE DATABASE"),
            "a plain CREATE DATABASE statement would abort the suite's first start");
    }

    // ---- landing page ----

    @Test
    void theLandingPageReadsThePackFromComponentsJsonAndOnlyTheBuildStampIsBaked() throws IOException {
        String page = read("src/webapp/ROOT/index.html");
        for (String gone : List.of("%%PACK_TAGLINE%%", "%%PACK_LINK_BLURB%%", "%%PACK_STAMP%%")) {
            assertFalse(page.contains(gone), "index.html no longer carries " + gone);
            assertFalse(read("Dockerfile").contains(gone), "the Dockerfile no longer fills " + gone);
            assertFalse(read("Dockerfile.viscostore").contains(gone), "Dockerfile.viscostore no longer fills " + gone);
        }
        assertTrue(page.contains("%%BUILD_TIMESTAMP%%") && read("Dockerfile").contains("%%BUILD_TIMESTAMP%%")
                && read("Dockerfile.viscostore").contains("%%BUILD_TIMESTAMP%%"), "the build stamp is still baked by both Dockerfiles");
        assertTrue(page.contains("<p data-pack-tagline>Integration platform</p>"), "the core's tagline is the page's default");
        assertTrue(page.contains("data-pack-blurb") && page.contains("<span data-pack-stamp></span>"), "pack blurb and stamp slots");
        assertTrue(page.contains("fetch('components.json'"), "the page reads components.json (written by the fetcher next to it)");
        assertTrue(page.contains("'pack-health'") && page.contains("Healthcare integration platform"), "the health pack's words live in the page");
        assertTrue(page.contains("Viscosiety B.V.</span><span data-pack-stamp></span>%%BUILD_TIMESTAMP%%"), "the footer: company (brandable), pack/release stamp, then build stamp");
    }

    private static String lemmaEngineVersion(String pom) {
        Matcher m = Pattern.compile("<artifactId>lemma-engine</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
        assertTrue(m.find(), "no lemma-engine version in the pom");
        return m.group(1).strip();
    }

    /** Tests load the jar's own native library, the image the unpacked one: the two poms must name one version. */
    @Test
    void lemmaEngineVersionIsTheSameInTheViscolinkAndViscorunnerPoms() throws IOException {
        org.junit.jupiter.api.Assertions.assertEquals(
            lemmaEngineVersion(read("../viscolink/pom.xml")), lemmaEngineVersion(read("pom.xml")));
    }

    @Test
    void theRuleSetAliasesEveryLegacyPathIncludingTheConsole() throws Exception {
        String rules = read("conf/Catalina/localhost/rewrite.config");
        for (String[] alias : new String[][] {{"viscolink", "runtime"}, {"viscostore", "fhir-store"}, {"viscoforge", "console"}}) {
            String legacy = alias[0], ctx = alias[1];
            assertTrue(rules.contains("RewriteRule ^/" + legacy + "/(.*)$ /" + ctx + "/$1 [R=302,L]"), legacy + ": browser GET redirects");
            assertTrue(rules.contains("RewriteRule ^/" + legacy + "$ /" + ctx + "/ [R=302,L]"), legacy + ": bare path redirects");
            assertTrue(rules.contains("RewriteRule ^/" + legacy + "$ /" + ctx + "/ [L]"), legacy + ": bare path rewritten in place");
            assertTrue(rules.contains("RewriteRule ^/" + legacy + "/(.*)$ /" + ctx + "/$1 [L]"), legacy + ": every other request rewritten in place");
        }
    }
}
