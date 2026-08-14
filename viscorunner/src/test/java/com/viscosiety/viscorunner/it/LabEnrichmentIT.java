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

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the ViscoLink loinc-enriched FHIR facade.
 *
 * <p>Starts two real servers side-by-side:</p>
 * <ol>
 *   <li><strong>ViscoStore</strong> — HAPI FHIR JPA R4 server started as a separate JVM
 *       ({@code java -jar target/viscostore.war}) with an in-memory H2 database.</li>
 *   <li><strong>ViscoLink</strong> — Frank!Framework WAR launched in a <em>separate JVM</em> via
 *       {@link ProcessBuilder} + {@link ViscolinkLauncher}, connected to a file-based H2 database
 *       that is shared with the test JVM via {@code AUTO_SERVER=TRUE}.</li>
 * </ol>
 *
 * <p>The test exercises the full loinc-enriched pipeline without invoking any XSLT directly:</p>
 * <pre>
 *   FhirReadProvider  →  LabEnrichmentObservationRead adapter
 *     → XsltPipe (build-read-url.xslt)
 *     → IbisLocalSender → FetchAndEnrichBundle adapter
 *         → HttpSender (fetches Bundle from ViscoStore)
 *         → FixedQuerySender (loads loinc_mapping from H2)
 *         → XsltPipe (enrich-loinc.xslt: replaces NullFlavor/OTH with LOINC)
 *     → enriched Observation returned to caller
 * </pre>
 *
 * <h3>Why separate JVMs for both servers?</h3>
 * <p>Frank!Framework 10.x ships Spring Boot 4 / Spring 7, while ViscoStore uses Spring Boot 3 /
 * Spring 6.  Deploying both in the same JVM causes classloader constraint violations
 * (SLF4J {@code Marker} identity, duplicate {@code META-INF/spring.factories}).  Separate JVMs
 * provide complete classpath isolation.  The test JVM itself carries no Spring dependency at
 * all.</p>
 *
 * <h3>Database sharing</h3>
 * <p>H2's {@code AUTO_SERVER=TRUE} mode starts an embedded TCP server on the first connection
 * and allows subsequent connections from other JVMs to attach to the same file-based database.
 * The test JVM seeds the {@code loinc_mapping} table; the launcher JVM's {@code FixedQuerySender}
 * reads from it.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabEnrichmentIT {

    private static final String TEST_VS_PASSWORD = "test-viscostore-it";

    // maven-dependency-plugin copies these to target/launcher-libs/ (version-stripped) at
    // pre-integration-test phase, so the path is stable regardless of the .m2/ location.
    private static final Path LAUNCHER_LIBS = Paths.get("target/launcher-libs").toAbsolutePath();

    private static String lib(String artifactId) {
        return LAUNCHER_LIBS.resolve(artifactId + ".jar").toString();
    }

    // ── Server processes ──────────────────────────────────────────────────────────────────────────
    private Process viscoStoreProcess;
    private int     viscoStorePort;

    private Process viscolinkProcess;
    private int     viscolinkPort;

    // ── HAPI FHIR client ─────────────────────────────────────────────────────────────────────────
    private IGenericClient storeClient;

    // ── Test data ─────────────────────────────────────────────────────────────────────────────────
    private String inboundObsId;

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // One-time setup
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    @BeforeAll
    void setUpOnce() throws Exception {
        Path viscoStoreWar = Paths.get("target/viscostore.war").toAbsolutePath();
        Path viscolinkWar  = Paths.get("target/viscolink.war").toAbsolutePath();
        Path demoConfigs   = Paths.get("demo-configurations").toAbsolutePath();
        Path testClasses   = Paths.get("target/test-classes").toAbsolutePath();

        if (!Files.exists(viscoStoreWar)) throw new IllegalStateException(
            "viscostore WAR not found at " + viscoStoreWar +
            " — run `mvn install -pl viscolink,viscostore && mvn package -pl viscorunner` first");
        if (!Files.exists(viscolinkWar)) throw new IllegalStateException(
            "viscolink WAR not found at " + viscolinkWar +
            " — run `mvn install -pl viscolink` first");

        String javaExe = ProcessHandle.current().info().command()
            .orElse(Paths.get(System.getProperty("java.home"), "bin/java").toString());

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        // 1. Start ViscoStore.
        viscoStorePort = findFreePort();
        log("Step 1: starting ViscoStore on port " + viscoStorePort);
        startViscoStore(javaExe, viscoStoreWar, viscoStorePort, TEST_VS_PASSWORD);

        // 2. Create a shared file-based H2 database.
        Path h2Dir = Files.createTempDirectory("viscolink-it-h2-");
        String h2Url = "jdbc:h2:file:" + h2Dir.resolve("db") + ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
        log("Step 2: H2 database at " + h2Url);

        // 3. Poll ViscoStore until the FHIR metadata endpoint responds 200.
        String storeHealthUrl = "http://localhost:" + viscoStorePort + "/fhir/metadata";
        log("Step 3: polling ViscoStore metadata at " + storeHealthUrl);
        AtomicInteger lastStatus = new AtomicInteger(0);
        AtomicReference<String> lastError = new AtomicReference<>("");
        await().atMost(120, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
            .conditionEvaluationListener(c -> {
                if (!c.isSatisfied()) log("  ViscoStore not ready yet: status=" + lastStatus.get()
                    + (lastError.get().isEmpty() ? "" : " error=" + lastError.get()));
            })
            .until(() -> {
                try {
                    HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder().uri(URI.create(storeHealthUrl)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                    lastStatus.set(resp.statusCode());
                    lastError.set("");
                    return resp.statusCode() == 200;
                } catch (Exception e) {
                    lastStatus.set(0);
                    lastError.set(e.getClass().getSimpleName() + ": " + e.getMessage());
                    return false;
                }
            });
        log("Step 3: ViscoStore ready (status 200)");

        // 4. Seed the loinc_mapping table before ViscoLink starts.
        log("Step 4: seeding loinc_mapping table in H2");
        seedLoincMapping(h2Url);
        log("Step 4: loinc_mapping seeded");

        // 5. Build the minimal classpath for the ViscolinkLauncher JVM.
        String pathSep   = System.getProperty("path.separator");
        String classpath = String.join(pathSep,
            lib("tomcat-embed-core"),
            lib("tomcat-embed-websocket"),
            lib("tomcat-embed-el"),
            lib("tomcat-embed-jasper"),
            lib("tomcat-dbcp"),
            lib("jakarta.annotation-api"),
            lib("h2"),
            testClasses.toString()
        );

        // 6. Launch ViscoLink (fhir-to-fhir configuration).
        String viscoStoreUrl = "http://localhost:" + viscoStorePort + "/fhir/";
        log("Step 6: launching ViscoLink (fhir-to-fhir) → ViscoStore at " + viscoStoreUrl);
        ProcessBuilder pb = new ProcessBuilder(
            // spring.main.allow-circular-references: see same flag in Hl7v2ToFhirIT --
            // ladybug-common's View gained setTestTool() (#814), cycling through
            // testTool -> views -> whiteBoxView in this directly-launched JVM too.
            javaExe, "-Dspring.main.allow-circular-references=true", "-cp", classpath,
            "com.viscosiety.viscorunner.it.ViscolinkLauncher",
            viscolinkWar.toString(),
            demoConfigs.toString(),
            viscoStoreUrl,
            h2Url,
            TEST_VS_PASSWORD
        );
        pb.redirectErrorStream(false);
        viscolinkProcess = pb.start();
        drainStream(viscolinkProcess.getErrorStream(), "[viscolink-stderr]");

        // 7. Read stdout until READY:{port}.
        log("Step 7: waiting for ViscoLink READY signal");
        viscolinkPort = readReadyPort(viscolinkProcess, 120);
        log("Step 7: ViscoLink ready on port " + viscolinkPort);

        // 8. Poll the F!F health endpoint until all adapters have started.
        String viscolinkHealthUrl = "http://localhost:" + viscolinkPort + "/viscolink/iaf/api/server/health";
        log("Step 8: polling ViscoLink health at " + viscolinkHealthUrl);
        AtomicInteger healthStatus = new AtomicInteger(0);
        AtomicReference<String> healthBody = new AtomicReference<>("");
        await().atMost(90, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
            .conditionEvaluationListener(c -> {
                if (!c.isSatisfied()) log("  ViscoLink not healthy yet: status=" + healthStatus.get()
                    + (healthBody.get().isEmpty() ? "" : " body=" + truncate(healthBody.get(), 300)));
            })
            .until(() -> {
                try {
                    HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder().uri(URI.create(viscolinkHealthUrl)).GET()
                            .timeout(Duration.ofSeconds(4)).build(),
                        HttpResponse.BodyHandlers.ofString());
                    healthStatus.set(resp.statusCode());
                    healthBody.set(resp.body());
                    return resp.statusCode() == 200;
                } catch (Exception e) {
                    healthStatus.set(0);
                    healthBody.set(e.getClass().getSimpleName() + ": " + e.getMessage());
                    return false;
                }
            });
        log("Step 8: ViscoLink healthy");

        // 9. Set up HAPI FHIR client and seed the test Observation.
        log("Step 9: setting up FHIR client and seeding test Observation");
        FhirContext ctx = FhirContext.forR4();
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        ctx.getRestfulClientFactory().setSocketTimeout(60_000);
        storeClient = ctx.newRestfulGenericClient("http://localhost:" + viscoStorePort + "/fhir/");
        storeClient.registerInterceptor(new BasicAuthInterceptor("viscolink", TEST_VS_PASSWORD));
        inboundObsId = seedInboundObservation();
        log("Step 9: seeded Observation id=" + inboundObsId);
    }

    @AfterAll
    void tearDown() {
        log("Tear-down: stopping ViscoLink and ViscoStore");
        if (viscolinkProcess  != null) viscolinkProcess.destroyForcibly();
        if (viscoStoreProcess != null) viscoStoreProcess.destroyForcibly();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Test
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Reads the inbound Observation through the loinc-enriched ViscoLink facade and verifies that:
     * <ul>
     *   <li>The NullFlavor/OTH placeholder coding is replaced by LOINC 2000-8</li>
     *   <li>{@code code.text} "Calcium" is preserved</li>
     *   <li>The {@code loinc-enriched} enrichment meta tag is present</li>
     * </ul>
     */
    @Test
    void whenObservationReadThroughLoincEnrichedFacade_loincCodingReplacesNullFlavor() {
        log("Test: reading Observation " + inboundObsId + " through loinc-enriched facade");
        FhirContext facadeCtx = FhirContext.forR4();
        facadeCtx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        facadeCtx.getRestfulClientFactory().setSocketTimeout(30_000);
        IGenericClient facadeClient = facadeCtx.newRestfulGenericClient(
            "http://localhost:" + viscolinkPort + "/viscolink/fhir/r4/loinc-enriched/");

        Observation enriched = facadeClient.read()
            .resource(Observation.class)
            .withId(inboundObsId)
            .execute();

        assertNotNull(enriched, "Facade must return an Observation");
        log("Test: received enriched Observation with " + enriched.getCode().getCoding().size() + " coding(s)");

        assertTrue(
            enriched.getCode().getCoding().stream()
                .anyMatch(c -> "http://loinc.org".equals(c.getSystem()) && "2000-8".equals(c.getCode())),
            "Enriched Observation must carry LOINC code 2000-8");
        log("Test: LOINC 2000-8 coding present — OK");

        assertFalse(
            enriched.getCode().getCoding().stream()
                .anyMatch(c -> "http://terminology.hl7.org/CodeSystem/v3-NullFlavor".equals(c.getSystem())),
            "Enriched Observation must not retain the NullFlavor/OTH coding");
        log("Test: NullFlavor coding absent — OK");

        assertEquals("Calcium", enriched.getCode().getText(), "code.text must be preserved");
        log("Test: code.text='Calcium' preserved — OK");

        assertTrue(
            enriched.getMeta().getTag().stream()
                .anyMatch(t -> "http://terminology.viscosiety.com/enrichment".equals(t.getSystem())
                               && "loinc-enriched".equals(t.getCode())),
            "Enriched Observation must carry the loinc-enriched provenance tag");
        log("Test: loinc-enriched meta tag present — OK");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Setup helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    private void startViscoStore(String javaExe, Path viscoStoreWar, int port, String vsPassword)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            javaExe,
            "-jar", viscoStoreWar.toString(),
            "--server.port=" + port,
            "--spring.datasource.url=jdbc:h2:mem:dbenrichment;DB_CLOSE_DELAY=-1",
            "--hapi.fhir.fhir_version=R4",
            "--hapi.fhir.custom-bean-packages=com.viscosiety.viscostore",
            "--hapi.fhir.cr.enabled=false",
            "--hapi.fhir.mdm_enabled=false",
            "--hapi.fhir.enable_repository_validating_interceptor=false",
            "--hapi.fhir.advanced_lucene_indexing=false",
            "--hapi.fhir.search_index_full_text_enabled=false",
            "--spring.ai.mcp.server.enabled=false",
            "--spring.main.allow-bean-definition-overriding=true",
            "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
            "--spring.security.user.name=viscolink",
            "--spring.security.user.password=" + vsPassword
        );
        pb.redirectErrorStream(false);
        viscoStoreProcess = pb.start();
        drainStream(viscoStoreProcess.getInputStream(), "[viscostore-stdout]");
        drainStream(viscoStoreProcess.getErrorStream(),  "[viscostore-stderr]");
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

    private void seedLoincMapping(String h2Url) throws Exception {
        try (Connection conn = DriverManager.getConnection(h2Url, "sa", "")) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS loinc_mapping (
                    text     TEXT NOT NULL,
                    specimen TEXT NOT NULL DEFAULT '',
                    code     TEXT NOT NULL,
                    display  TEXT NOT NULL,
                    PRIMARY KEY (text, specimen)
                )
                """);
            conn.createStatement().execute("""
                MERGE INTO loinc_mapping (text, specimen, code, display)
                KEY (text, specimen)
                VALUES ('Calcium', '', '2000-8', 'Calcium [Mass/volume] in Serum or Plasma')
                """);
        }
    }

    private String seedInboundObservation() {
        Patient patient = new Patient();
        patient.setActive(true);
        String patientId = storeClient.create().resource(patient).execute().getId().getIdPart();
        log("  seeded Patient id=" + patientId);

        Observation obs = new Observation();
        obs.setStatus(Observation.ObservationStatus.FINAL);
        obs.getCode()
            .addCoding()
            .setSystem("http://terminology.hl7.org/CodeSystem/v3-NullFlavor")
            .setCode("OTH")
            .setDisplay("Other");
        obs.getCode().setText("Calcium");
        obs.setSubject(new Reference("Patient/" + patientId));
        return storeClient.create().resource(obs).execute().getId().getIdPart();
    }

    private int readReadyPort(Process process, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive()) throw new IllegalStateException(
                    "ViscolinkLauncher exited with code " + process.exitValue() + " before READY");
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null) {
                        System.out.println("[viscolink-stdout] " + line);
                        if (line.startsWith("READY:"))
                            return Integer.parseInt(line.substring("READY:".length()).trim());
                    }
                } else {
                    Thread.sleep(200);
                }
            }
        }
        throw new IllegalStateException(
            "Timed out after " + timeoutSeconds + "s waiting for ViscolinkLauncher READY signal");
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        }
    }

    private static void log(String msg) {
        System.out.printf("[%s][LabEnrichmentIT] %s%n", LocalTime.now().toString().substring(0, 12), msg);
        System.out.flush();
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
