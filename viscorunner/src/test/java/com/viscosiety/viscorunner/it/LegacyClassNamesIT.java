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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The health pack's classes moved to {@code com.viscosiety.pack.health.*}; configurations written before
 * the move still name {@code com.viscosiety.pipes.Hl7v2ToXmlPipe}, {@code com.viscosiety.fhir.FhirListener}
 * and so on. This IT runs a real ViscoLink (embedded Tomcat, the health pack overlay on the class path) with
 * the {@code legacy-class-names} test configuration, which uses old AND new names side by side, and checks:
 * <ul>
 *   <li>every adapter starts (old names load);</li>
 *   <li>the old-name and new-name pipe convert the same HL7v2 message identically;</li>
 *   <li>the old-name FhirListener registers its FHIR facade;</li>
 *   <li>Frank!Framework reports exactly the two old names as deprecated, naming the new class.</li>
 * </ul>
 * No ViscoStore is started: the configuration never calls it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LegacyClassNamesIT {

    private static final String ADT_A01_PIPE =
            "MSH|^~\\&|HIS|HOSPITAL|VISCO|DEST|20240315143000||ADT^A01^ADT_A01|MSG-IT-001|P|2.5\r" +
            "EVN|A01|20240315143000\r" +
            "PID|1||PAT001^^^HOSPITAL^MR||SMITH^JANE^M||19751020|F\r" +
            "PV1|1|I|CARDIO||||||||||||||||V001\r";

    private static final Path LAUNCHER_LIBS = Paths.get("target/launcher-libs").toAbsolutePath();

    private static String lib(String artifactId) {
        return LAUNCHER_LIBS.resolve(artifactId + ".jar").toString();
    }

    private Process viscolinkProcess;
    private int viscolinkPort;
    private HttpClient http;

    @BeforeAll
    void setUpOnce() throws Exception {
        Path viscolinkWar  = Paths.get("target/viscolink.war").toAbsolutePath();
        Path configs       = Paths.get("src/test/resources/it-configurations").toAbsolutePath();
        Path testClasses   = Paths.get("target/test-classes").toAbsolutePath();
        Path healthOverlay = Paths.get("target/packs/health").toAbsolutePath();

        if (!Files.exists(viscolinkWar)) throw new IllegalStateException(
            "viscolink WAR not found at " + viscolinkWar +
            " — run `mvn install -pl viscolink,packs/health,viscostore && mvn package -pl viscorunner` first");
        if (!Files.isDirectory(healthOverlay.resolve("WEB-INF/lib"))) throw new IllegalStateException(
            "health pack overlay not found at " + healthOverlay +
            " — run `mvn install -pl viscolink,packs/health,viscostore && mvn package -pl viscorunner` first");

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String javaExe = ProcessHandle.current().info().command()
            .orElse(Paths.get(System.getProperty("java.home"), "bin/java").toString());

        // Throw-away H2 for the JNDI datasources the launcher always binds.
        Path h2Dir = Files.createTempDirectory("viscolink-legacy-names-it-h2-");
        String h2Url = "jdbc:h2:file:" + h2Dir.resolve("db") + ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";

        String classpath = String.join(System.getProperty("path.separator"),
            lib("tomcat-embed-core"),
            lib("tomcat-embed-websocket"),
            lib("tomcat-embed-el"),
            lib("tomcat-embed-jasper"),
            lib("tomcat-dbcp"),
            lib("jakarta.annotation-api"),
            lib("h2"),
            testClasses.toString()
        );

        log("launching ViscoLink with configuration legacy-class-names");
        ProcessBuilder pb = new ProcessBuilder(
            // see Hl7v2ToFhirIT for why the circular-references flag is needed
            javaExe, "-Dspring.main.allow-circular-references=true",
            "-Dviscolink.overlay.dir=" + healthOverlay, "-cp", classpath,
            "com.viscosiety.viscorunner.it.ViscolinkLauncher",
            viscolinkWar.toString(),
            configs.toString(),
            "http://localhost:1/fhir/",   // ViscoStore: never called by this configuration
            h2Url,
            "unused",
            "legacy-class-names"
        );
        pb.redirectErrorStream(false);
        viscolinkProcess = pb.start();
        drainStream(viscolinkProcess.getErrorStream(), "[viscolink-stderr]");
        viscolinkPort = readReadyPort(viscolinkProcess, 120);
        log("ViscoLink ready on port " + viscolinkPort);

        // Health 200 = every adapter STARTED, i.e. the old class names loaded.
        String healthUrl = base() + "/iaf/api/server/health";
        AtomicInteger status = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>("");
        await().atMost(90, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
            .conditionEvaluationListener(c -> {
                if (!c.isSatisfied()) log("  not healthy: status=" + status.get() + " body=" + truncate(body.get(), 400));
            })
            .until(() -> {
                try {
                    HttpResponse<String> resp = get(healthUrl);
                    status.set(resp.statusCode());
                    body.set(resp.body());
                    return resp.statusCode() == 200;
                } catch (Exception e) {
                    status.set(0);
                    body.set(e.getClass().getSimpleName() + ": " + e.getMessage());
                    return false;
                }
            });
        log("all adapters started");
    }

    @AfterAll
    void tearDown() {
        if (viscolinkProcess != null) viscolinkProcess.destroyForcibly();
    }

    @Test
    void oldAndNewPipeNamesConvertIdentically() throws Exception {
        HttpResponse<String> old = post("/api/legacy/old", ADT_A01_PIPE);
        HttpResponse<String> now = post("/api/legacy/new", ADT_A01_PIPE);
        assertEquals(200, old.statusCode(), old.body());
        assertEquals(200, now.statusCode(), now.body());
        assertTrue(now.body().contains("ADT_A01"), now.body());
        assertEquals(now.body(), old.body());
    }

    @Test
    void oldListenerNameRegistersItsFhirFacade() throws Exception {
        HttpResponse<String> resp = get(base() + "/fhir/r4/legacy-names/metadata");
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("Patient"), resp.body());
    }

    @Test
    void onlyTheOldNamesAreReportedAsDeprecated() throws Exception {
        HttpResponse<String> resp = get(base() + "/iaf/api/server/warnings");
        assertEquals(200, resp.statusCode(), resp.body());
        String warnings = resp.body();
        log("server warnings: " + truncate(warnings, 3000));
        assertEquals(2, count(warnings, "marked for removal"), warnings);
        assertTrue(warnings.contains("com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe"), warnings);
        assertTrue(warnings.contains("com.viscosiety.pack.health.fhir.FhirListener"), warnings);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private String base() {
        return "http://localhost:" + viscolinkPort + "/viscolink";
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(URI.create(url)).GET()
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder().uri(URI.create(base() + path))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int count(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private void drainStream(java.io.InputStream stream, String prefix) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = r.readLine()) != null) System.out.println(prefix + " " + line);
            } catch (Exception ignored) {}
        }, prefix);
        t.setDaemon(true);
        t.start();
    }

    private int readReadyPort(Process process, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) throw new IllegalStateException(
                "ViscolinkLauncher exited with code " + process.exitValue() + " before READY");
            if (reader.ready()) {
                String line = reader.readLine();
                if (line != null) {
                    System.out.println("[viscolink-stdout] " + line);
                    if (line.startsWith("READY:")) {
                        // keep draining stdout so the launcher never blocks on a full pipe
                        Thread t = new Thread(() -> {
                            try { while (reader.readLine() != null) { /* discard */ } } catch (Exception ignored) {}
                        }, "viscolink-stdout");
                        t.setDaemon(true);
                        t.start();
                        return Integer.parseInt(line.substring("READY:".length()).trim());
                    }
                }
            } else {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException(
            "Timed out after " + timeoutSeconds + "s waiting for ViscolinkLauncher READY signal");
    }

    private static void log(String msg) {
        System.out.printf("[%s][LegacyClassNamesIT] %s%n", LocalTime.now().toString().substring(0, 12), msg);
        System.out.flush();
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
