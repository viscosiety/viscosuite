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
import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.parser.XMLParser;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
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
import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the HL7v2-to-FHIR pipeline.
 *
 * <p>Starts two real servers side-by-side:</p>
 * <ol>
 *   <li><strong>ViscoStore</strong> — HAPI FHIR JPA R4 server started as a separate JVM
 *       ({@code java -jar target/viscostore.war}) with an in-memory H2 database.</li>
 *   <li><strong>ViscoLink</strong> — Frank!Framework WAR launched in a <em>separate JVM</em>
 *       via {@link ProcessBuilder} + {@link ViscolinkLauncher}, loading the
 *       {@code hl7v2-to-fhir} configuration.</li>
 * </ol>
 *
 * <p>The test exercises the full ingest pipeline without invoking any XSLT directly:</p>
 * <pre>
 *   HTTP POST (HL7v2 XML) → HL7v2-over-HTTP adapter
 *     → IbisLocalSender → HL7v2ToFHIR adapter
 *         → PutInSessionPipe (extracts trigger event)
 *         → UUIDGeneratorPipe
 *         → XsltPipe (ADT_Axx.xslt → FHIR R4 transaction Bundle)
 *         → FhirValidatorPipe
 *         → HttpSender (POST transaction Bundle to ViscoStore)
 *     → Patient + Encounter stored in ViscoStore
 * </pre>
 *
 * <h3>Why HL7v2 XML and not pipe-delimited?</h3>
 * <p>The HTTP adapter forwards the raw request body directly to the HL7v2ToFHIR pipeline,
 * which immediately applies XPath on the {@code urn:hl7-org:v2xml} namespace.  Only the MLLP
 * adapter contains an {@code Hl7v2ToXmlPipe} step.  The test JVM therefore converts
 * pipe-delimited messages to HL7v2 XML using HAPI before posting.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Hl7v2ToFhirIT {

    private static final String TEST_VS_PASSWORD = "test-viscostore-it";

    // PV1: visit V001 at field 19 — 17 pipes after patient class 'I'
    // PV1: admit (PV1.44) 25 pipes after V001; discharge (PV1.45) one pipe after admit
    private static final String ADT_A01_PIPE =
            "MSH|^~\\&|HIS|HOSPITAL|VISCO|DEST|20240315143000||ADT^A01^ADT_A01|MSG-IT-001|P|2.5\r" +
            "EVN|A01|20240315143000\r" +
            "PID|1||PAT001^^^HOSPITAL^MR||SMITH^JANE^M||19751020|F\r" +
            "PV1|1|I|CARDIO||||||||||||||||V001\r";

    private static final String ADT_A03_PIPE =
            "MSH|^~\\&|HIS|HOSPITAL|VISCO|DEST|20240318160000||ADT^A03^ADT_A03|MSG-IT-003|P|2.5\r" +
            "EVN|A03|20240318160000\r" +
            "PID|1||PAT001^^^HOSPITAL^MR||SMITH^JANE^M||19751020|F\r" +
            "PV1|1|I|CARDIO||||||||||||||||V001|||||||||||||||||||||||||20240315143000|20240318160000\r";

    private static final Path LAUNCHER_LIBS = Paths.get("target/launcher-libs").toAbsolutePath();

    private static String lib(String artifactId) {
        return LAUNCHER_LIBS.resolve(artifactId + ".jar").toString();
    }

    // ── Server processes ──────────────────────────────────────────────────────────────────────────
    private Process viscoStoreProcess;
    private int     viscoStorePort;

    private Process viscolinkProcess;
    private int     viscolinkPort;

    // ── Clients ───────────────────────────────────────────────────────────────────────────────────
    private IGenericClient storeClient;
    private HttpClient     http;

    // ── HAPI HL7v2 parsers (test JVM only — convert pipe-delimited to XML before posting) ─────────
    private DefaultHapiContext hapiContext;
    private PipeParser         pipeParser;
    private XMLParser          xmlParser;

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

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        String javaExe = ProcessHandle.current().info().command()
            .orElse(Paths.get(System.getProperty("java.home"), "bin/java").toString());

        // 1. Start ViscoStore.
        viscoStorePort = findFreePort();
        log("Step 1: starting ViscoStore on port " + viscoStorePort);
        startViscoStore(javaExe, viscoStoreWar, viscoStorePort, TEST_VS_PASSWORD);

        // 2. Throw-away H2 for JNDI datasources the launcher always binds.
        //    hl7v2-to-fhir does not use JDBC, but Tomcat JNDI resources must be bound before
        //    webapp init to avoid lookup errors on the jdbc/viscolink and jdbc/viscostore entries.
        Path h2Dir = Files.createTempDirectory("viscolink-hl7-it-h2-");
        String h2Url = "jdbc:h2:file:" + h2Dir.resolve("db") + ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
        log("Step 2: stub H2 for JNDI at " + h2Url);

        // 3. Poll ViscoStore until the FHIR metadata endpoint responds 200.
        String storeHealthUrl = "http://localhost:" + viscoStorePort + "/fhir/metadata";
        log("Step 3: polling ViscoStore metadata at " + storeHealthUrl);
        AtomicInteger vsStatus = new AtomicInteger(0);
        AtomicReference<String> vsError = new AtomicReference<>("");
        await().atMost(120, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
            .conditionEvaluationListener(c -> {
                if (!c.isSatisfied()) log("  ViscoStore not ready: status=" + vsStatus.get()
                    + (vsError.get().isEmpty() ? "" : " error=" + vsError.get()));
            })
            .until(() -> {
                try {
                    HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder().uri(URI.create(storeHealthUrl)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                    vsStatus.set(resp.statusCode());
                    vsError.set("");
                    return resp.statusCode() == 200;
                } catch (Exception e) {
                    vsStatus.set(0);
                    vsError.set(e.getClass().getSimpleName() + ": " + e.getMessage());
                    return false;
                }
            });
        log("Step 3: ViscoStore ready (status 200)");

        // 4. Build the launcher classpath.
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

        // 5. Launch ViscoLink with the hl7v2-to-fhir configuration.
        //    ViscolinkLauncher writes a custom resources.yml to WEB-INF/classes/ so the MLLP
        //    adapters bind free ports and all adapters reach STARTED state (required for the
        //    health endpoint to return 200).
        String viscoStoreUrl = "http://localhost:" + viscoStorePort + "/fhir/";
        log("Step 5: launching ViscoLink (hl7v2-to-fhir) → ViscoStore at " + viscoStoreUrl);
        ProcessBuilder pb = new ProcessBuilder(
            // spring.main.allow-circular-references: ladybug-common 4.1-20260813.135649's
            // View gained setTestTool(TestTool) (#814); whiteBoxView (autowire="byName",
            // vendored in frankframework-ladybug-common) now cycles through testTool ->
            // views -> whiteBoxView. Same fix as docker-compose.yml's JAVA_OPTS, needed here
            // too since this JVM is launched directly, not via that compose file.
            javaExe, "-Dspring.main.allow-circular-references=true", "-cp", classpath,
            "com.viscosiety.viscorunner.it.ViscolinkLauncher",
            viscolinkWar.toString(),
            demoConfigs.toString(),
            viscoStoreUrl,
            h2Url,
            TEST_VS_PASSWORD,
            "hl7v2-to-fhir"
        );
        pb.redirectErrorStream(false);
        viscolinkProcess = pb.start();
        drainStream(viscolinkProcess.getErrorStream(), "[viscolink-stderr]");

        // 6. Read stdout until READY:{port}.
        log("Step 6: waiting for ViscoLink READY signal");
        viscolinkPort = readReadyPort(viscolinkProcess, 120);
        log("Step 6: ViscoLink ready on port " + viscolinkPort);

        // 7. Poll the F!F health endpoint until all adapters are started.
        //    ViscolinkLauncher writes a custom resources.yml defining all MLLP resources
        //    (inbound on a dynamically allocated port, outbound-ris as a stub) so no adapter
        //    ends up in ERROR state and the health endpoint reaches 200.
        String viscolinkHealthUrl = "http://localhost:" + viscolinkPort + "/viscolink/iaf/api/server/health";
        log("Step 7: polling ViscoLink health at " + viscolinkHealthUrl);
        AtomicInteger healthStatus = new AtomicInteger(0);
        AtomicReference<String> healthBody = new AtomicReference<>("");
        await().atMost(90, TimeUnit.SECONDS).pollInterval(3, TimeUnit.SECONDS)
            .conditionEvaluationListener(c -> {
                if (!c.isSatisfied()) log("  ViscoLink not healthy: status=" + healthStatus.get()
                    + (healthBody.get().isEmpty() ? "" : " body=" + truncate(healthBody.get(), 400)));
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
        log("Step 7: ViscoLink healthy");

        // 8. Set up HAPI FHIR client for ViscoStore.
        log("Step 8: setting up FHIR client");
        FhirContext ctx = FhirContext.forR4();
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        ctx.getRestfulClientFactory().setSocketTimeout(60_000);
        storeClient = ctx.newRestfulGenericClient("http://localhost:" + viscoStorePort + "/fhir/");
        storeClient.registerInterceptor(new BasicAuthInterceptor("viscolink", TEST_VS_PASSWORD));

        // 9. Set up HAPI HL7v2 parsers (pipe-delimited → HL7v2 XML for the HTTP endpoint).
        log("Step 9: initialising HAPI HL7v2 parsers");
        hapiContext = new DefaultHapiContext(new CanonicalModelClassFactory("2.5"));
        pipeParser  = hapiContext.getPipeParser();
        xmlParser   = hapiContext.getXMLParser();
        log("Setup complete — Frank!Console: http://localhost:" + viscolinkPort + "/viscolink/iaf/");
    }

    @AfterAll
    void tearDown() throws Exception {
        log("Tear-down: stopping ViscoLink and ViscoStore");
        if (hapiContext != null) hapiContext.close();
        if (viscolinkProcess  != null) viscolinkProcess.destroyForcibly();
        if (viscoStoreProcess != null) viscoStoreProcess.destroyForcibly();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Test
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Exercises the full admit → discharge scenario over the HL7v2-over-HTTP route:
     * <ol>
     *   <li>ADT^A01 (admit) — a Patient and an in-progress Encounter must appear in ViscoStore</li>
     *   <li>ADT^A03 (discharge) — the same Encounter must be updated to "finished" with period.end</li>
     * </ol>
     */
    @Test
    void hl7v2AdtScenario_admitFollowedByDischargeStoredCorrectlyInViscoStore() throws Exception {
        // ── Admit ────────────────────────────────────────────────────────────────────────────────
        log("Test: sending ADT^A01 (admit) for patient PAT001 / visit V001");
        int status = postHl7v2Xml(ADT_A01_PIPE);
        log("Test: ADT^A01 response status=" + status);
        assertEquals(200, status,
            "HL7v2-over-HTTP must return 200 for ADT^A01; " +
            "check Frank!Console at http://localhost:" + viscolinkPort + "/viscolink/iaf/");

        log("Test: searching ViscoStore for Patient identifier=PAT001");
        Patient patient = findPatient("PAT001");
        assertNotNull(patient, "ADT^A01 must create Patient PAT001 in ViscoStore");
        log("Test: Patient found — family=" + patient.getNameFirstRep().getFamily()
            + " gender=" + patient.getGender().toCode());
        assertEquals("SMITH", patient.getNameFirstRep().getFamily(), "Patient family name must match PID.5");
        assertEquals("female", patient.getGender().toCode(), "Patient gender must map PID.8 F → female");

        log("Test: searching ViscoStore for Encounter identifier=V001");
        Encounter encounter = findEncounter("V001");
        assertNotNull(encounter, "ADT^A01 must create Encounter V001 in ViscoStore");
        log("Test: Encounter found — status=" + encounter.getStatus().toCode());
        assertEquals("in-progress", encounter.getStatus().toCode(), "ADT^A01 Encounter status must be in-progress");

        // ── Discharge ────────────────────────────────────────────────────────────────────────────
        log("Test: sending ADT^A03 (discharge) for visit V001");
        status = postHl7v2Xml(ADT_A03_PIPE);
        log("Test: ADT^A03 response status=" + status);
        assertEquals(200, status, "HL7v2-over-HTTP must return 200 for ADT^A03");

        log("Test: re-fetching Encounter V001 after discharge");
        encounter = findEncounter("V001");
        assertNotNull(encounter, "Encounter V001 must still be in ViscoStore after ADT^A03");
        log("Test: Encounter status=" + encounter.getStatus().toCode()
            + " period.end=" + encounter.getPeriod().getEnd());
        assertEquals("finished", encounter.getStatus().toCode(),
            "ADT^A03 must update Encounter status to finished");
        assertNotNull(encounter.getPeriod().getEnd(), "ADT^A03 must set period.end from PV1.45");

        log("Test: all assertions passed");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Converts a pipe-delimited HL7v2 message to HL7v2 XML and POSTs it to the
     * HL7v2-over-HTTP endpoint, returning the HTTP response status code.
     */
    private int postHl7v2Xml(String pipeDelimited) throws Exception {
        String hl7Xml = xmlParser.encode(pipeParser.parse(pipeDelimited));
        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + viscolinkPort + "/viscolink/api/hl7v2"))
                .header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(hl7Xml))
                .timeout(Duration.ofSeconds(30))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log("  POST /api/hl7v2 returned " + resp.statusCode() + ": " + truncate(resp.body(), 300));
        }
        return resp.statusCode();
    }

    private Patient findPatient(String identifierValue) {
        Bundle bundle = storeClient.search()
            .forResource(Patient.class)
            .where(Patient.IDENTIFIER.exactly().identifier(identifierValue))
            .returnBundle(Bundle.class)
            .execute();
        return bundle.getEntry().isEmpty() ? null : (Patient) bundle.getEntryFirstRep().getResource();
    }

    private Encounter findEncounter(String identifierValue) {
        Bundle bundle = storeClient.search()
            .forResource(Encounter.class)
            .where(Encounter.IDENTIFIER.exactly().identifier(identifierValue))
            .returnBundle(Bundle.class)
            .execute();
        return bundle.getEntry().isEmpty() ? null : (Encounter) bundle.getEntryFirstRep().getResource();
    }

    private void startViscoStore(String javaExe, Path war, int port, String vsPassword)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            javaExe,
            "-jar", war.toString(),
            "--server.port=" + port,
            "--spring.datasource.url=jdbc:h2:mem:dbhl7it;DB_CLOSE_DELAY=-1",
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
        System.out.printf("[%s][Hl7v2ToFhirIT] %s%n", LocalTime.now().toString().substring(0, 12), msg);
        System.out.flush();
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
