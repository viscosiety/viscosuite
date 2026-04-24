package com.viscosiety.viscorunner.it;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
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
import java.util.concurrent.TimeUnit;

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
 * (SLF4J {@code Marker} identity, {@code FilterSecurityInterceptor} removal in spring-security-web
 * 7, duplicate {@code META-INF/spring.factories}).  Separate JVMs provide complete classpath
 * isolation.  The test JVM itself carries no Spring dependency at all.</p>
 *
 * <h3>Database sharing</h3>
 * <p>H2's {@code AUTO_SERVER=TRUE} mode starts an embedded TCP server on the first connection
 * and allows subsequent connections from other JVMs to attach to the same file-based database.
 * The test JVM seeds the {@code loinc_mapping} table; the launcher JVM's {@code FixedQuerySender}
 * reads from it.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabEnrichmentIT {

    // ── Maven local repository paths ──────────────────────────────────────────────────────────────
    // These jars form the minimal classpath for the ViscolinkLauncher JVM. They must not include
    // any Spring or logback jars — those come from the viscolink WAR's own WEB-INF/lib via
    // Tomcat's webapp classloader, completely isolated from the test JVM.

    // Resolved from the system property injected by maven-failsafe-plugin (see pom.xml).
    // Falls back to the default ~/.m2/repository for IDE / direct test runs.
    private static final String M2 = System.getProperty("maven.repo.local",
        Paths.get(System.getProperty("user.home"), ".m2/repository").toString());

    /** Tomcat 11 embedded core. */
    private static final String TOMCAT_EMBED_JAR =
        M2 + "/org/apache/tomcat/embed/tomcat-embed-core/11.0.18/tomcat-embed-core-11.0.18.jar";

    /** Tomcat 11 DBCP2 DataSource factory (for JNDI datasource resources). */
    private static final String TOMCAT_DBCP_JAR =
        M2 + "/org/apache/tomcat/tomcat-dbcp/11.0.18/tomcat-dbcp-11.0.18.jar";

    /**
     * Tomcat 11 embedded WebSocket implementation.
     * Also bundles the {@code jakarta.websocket} API classes (e.g. {@code Endpoint}) that
     * Spring's STOMP/WebSocket configuration references at startup.
     */
    private static final String TOMCAT_WEBSOCKET_JAR =
        M2 + "/org/apache/tomcat/embed/tomcat-embed-websocket/11.0.18/tomcat-embed-websocket-11.0.18.jar";

    /** Tomcat 11 embedded Expression Language implementation. */
    private static final String TOMCAT_EL_JAR =
        M2 + "/org/apache/tomcat/embed/tomcat-embed-el/11.0.18/tomcat-embed-el-11.0.18.jar";

    /** Tomcat 11 Jasper JSP engine — provides {@code JspServlet} declared in the F!F web.xml. */
    private static final String TOMCAT_JASPER_JAR =
        M2 + "/org/apache/tomcat/embed/tomcat-embed-jasper/11.0.18/tomcat-embed-jasper-11.0.18.jar";

    /** Jakarta Annotation API 3.0 — required by Tomcat 11's WebAnnotationSet at startup. */
    private static final String JAKARTA_ANNOTATION_JAR =
        M2 + "/jakarta/annotation/jakarta.annotation-api/3.0.0/jakarta.annotation-api-3.0.0.jar";

    /** H2 driver — shared by the test JVM (seeding) and the launcher JVM (FixedQuerySender). */
    private static final String H2_JAR =
        M2 + "/com/h2database/h2/2.4.240/h2-2.4.240.jar";

    /**
     * spring-security-web 6.5.7 — provides {@code FilterSecurityInterceptor} which
     * spring-security-config 7.0.4 references in its bytecode constant pool for a backwards-compat
     * check, but spring-security-web 7.0.4 has already removed the class.  Copied to the WAR's
     * {@code WEB-INF/lib/} by {@link ViscolinkLauncher} before Tomcat initialises the webapp.
     */
    private static final String SS_COMPAT_JAR =
        M2 + "/org/springframework/security/spring-security-web/6.5.7/spring-security-web-6.5.7.jar";

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
        // Paths are resolved relative to the viscorunner module directory (CWD when Failsafe runs).
        Path viscoStoreWar = Paths.get("target/viscostore.war").toAbsolutePath();
        Path viscolinkWar  = Paths.get("target/viscolink.war").toAbsolutePath();
        Path demoConfigs   = Paths.get("demo-configurations").toAbsolutePath();
        Path testClasses   = Paths.get("target/test-classes").toAbsolutePath();

        if (!Files.exists(viscoStoreWar)) {
            throw new IllegalStateException(
                "viscostore WAR not found at " + viscoStoreWar +
                " — run `mvn install -pl viscolink,viscostore && mvn package -pl viscorunner` first");
        }
        if (!Files.exists(viscolinkWar)) {
            throw new IllegalStateException(
                "viscolink WAR not found at " + viscolinkWar +
                " — run `mvn install -pl viscolink` first");
        }

        String javaExe = ProcessHandle.current().info().command()
            .orElse(Paths.get(System.getProperty("java.home"), "bin/java").toString());

        // 1. Start ViscoStore in a separate JVM.
        //    viscostore.war is an executable Spring Boot WAR — `java -jar` starts it directly.
        //    We pre-select the port so we can build the FHIR base URL before starting ViscoLink.
        viscoStorePort = findFreePort();
        startViscoStore(javaExe, viscoStoreWar, viscoStorePort);

        // 2. Create a file-based H2 database that both JVMs can access concurrently.
        //    AUTO_SERVER=TRUE lets H2 start a background TCP listener automatically so the
        //    launcher JVM can connect to the same database file.
        Path h2Dir = Files.createTempDirectory("viscolink-it-h2-");
        String h2Url = "jdbc:h2:file:" + h2Dir.resolve("db") +
                       ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";

        // 3. Poll the FHIR metadata endpoint until ViscoStore is ready.
        //    The actuator lives under /tester/actuator (because spring.mvc.servlet.path=/tester),
        //    not at the root /actuator path. Using /fhir/metadata is unambiguous — it is served
        //    by HAPI's RestfulServer servlet at /fhir/*, returns 200 only when FHIR is fully up,
        //    and is exactly what we need before making any FHIR client calls.
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        String storeHealthUrl = "http://localhost:" + viscoStorePort + "/fhir/metadata";

        await()
            .atMost(120, TimeUnit.SECONDS)
            .pollInterval(3, TimeUnit.SECONDS)
            .until(() -> {
                try {
                    HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder().uri(URI.create(storeHealthUrl)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                    return resp.statusCode() == 200;
                } catch (Exception e) {
                    return false;
                }
            });

        // 4. Seed the loinc_mapping table NOW, before ViscoLink starts, so the data is
        //    available on the very first F!F request.
        seedLoincMapping(h2Url);

        // 5. Build the minimal classpath for the ViscolinkLauncher JVM.
        //    ONLY: Tomcat 11 + DBCP2 + H2 + ViscolinkLauncher class.
        //    Spring, logback, and all other test JVM dependencies must be absent so the WAR's
        //    own WEB-INF/lib is the sole source of those libraries in the launcher JVM.
        String pathSep   = System.getProperty("path.separator"); // ':' on Unix, ';' on Windows
        String classpath = String.join(pathSep,
            TOMCAT_EMBED_JAR,
            TOMCAT_WEBSOCKET_JAR,
            TOMCAT_EL_JAR,
            TOMCAT_JASPER_JAR,
            TOMCAT_DBCP_JAR,
            JAKARTA_ANNOTATION_JAR,
            H2_JAR,
            testClasses.toString()
        );

        // 6. Launch ViscolinkLauncher in a separate JVM.
        String viscoStoreUrl = "http://localhost:" + viscoStorePort + "/fhir/";

        ProcessBuilder pb = new ProcessBuilder(
            javaExe,
            "-cp", classpath,
            "com.viscosiety.viscorunner.it.ViscolinkLauncher",
            viscolinkWar.toString(),
            demoConfigs.toString(),
            viscoStoreUrl,
            h2Url,
            SS_COMPAT_JAR
        );
        pb.redirectErrorStream(false);
        viscolinkProcess = pb.start();

        // Capture stderr in a background thread so we can include it in error messages.
        Thread stderrDrainer = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(viscolinkProcess.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.err.println("[viscolink-stderr] " + line);
                }
            } catch (Exception ignored) {}
        }, "viscolink-stderr");
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();

        // 7. Read stdout until we receive "READY:{port}".
        viscolinkPort = readReadyPort(viscolinkProcess, 120);

        // 8. Poll the F!F health endpoint until all adapters have started.
        String viscolinkHealthUrl = "http://localhost:" + viscolinkPort + "/viscolink/iaf/api/server/health";

        await()
            .atMost(90, TimeUnit.SECONDS)
            .pollInterval(3, TimeUnit.SECONDS)
            .until(() -> {
                try {
                    HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder().uri(URI.create(viscolinkHealthUrl)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                    return resp.statusCode() == 200;
                } catch (Exception e) {
                    return false;
                }
            });

        // 9. Set up HAPI FHIR client for ViscoStore and seed the inbound Observation.
        FhirContext ctx = FhirContext.forR4();
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        ctx.getRestfulClientFactory().setSocketTimeout(60_000);
        storeClient = ctx.newRestfulGenericClient("http://localhost:" + viscoStorePort + "/fhir/");

        inboundObsId = seedInboundObservation();
    }

    @AfterAll
    void tearDown() {
        if (viscolinkProcess != null) {
            viscolinkProcess.destroyForcibly();
        }
        if (viscoStoreProcess != null) {
            viscoStoreProcess.destroyForcibly();
        }
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

        // LOINC 2000-8 (Calcium) must replace the NullFlavor/OTH placeholder
        assertTrue(
            enriched.getCode().getCoding().stream()
                .anyMatch(c -> "http://loinc.org".equals(c.getSystem())
                               && "2000-8".equals(c.getCode())),
            "Enriched Observation must carry LOINC code 2000-8");

        assertFalse(
            enriched.getCode().getCoding().stream()
                .anyMatch(c -> "http://terminology.hl7.org/CodeSystem/v3-NullFlavor"
                                .equals(c.getSystem())),
            "Enriched Observation must not retain the NullFlavor/OTH coding");

        // code.text must be carried through unchanged by enrich-loinc.xslt
        assertEquals("Calcium", enriched.getCode().getText(),
            "code.text must be preserved");

        // Enrichment provenance tag: system=http://terminology.viscosiety.com/enrichment
        assertTrue(
            enriched.getMeta().getTag().stream()
                .anyMatch(t -> "http://terminology.viscosiety.com/enrichment".equals(t.getSystem())
                               && "loinc-enriched".equals(t.getCode())),
            "Enriched Observation must carry the loinc-enriched provenance tag");
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Setup helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Starts ViscoStore as a separate JVM using {@code java -jar viscostore.war}.
     * The process is stored in {@link #viscoStoreProcess}; its stdout and stderr are drained in
     * background threads to prevent pipe-buffer blocking.
     */
    private void startViscoStore(String javaExe, Path viscoStoreWar, int port) throws Exception {
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
            "--spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
        );
        pb.redirectErrorStream(false);
        viscoStoreProcess = pb.start();

        // Drain stdout and stderr in background threads — Spring Boot is very chatty on startup.
        drainStream(viscoStoreProcess.getInputStream(), "[viscostore-stdout]");
        drainStream(viscoStoreProcess.getErrorStream(),  "[viscostore-stderr]");
    }

    private void drainStream(java.io.InputStream stream, String prefix) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(prefix + " " + line);
                }
            } catch (Exception ignored) {}
        }, prefix);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Creates the {@code loinc_mapping} table and inserts one text-only mapping row
     * (empty specimen) so the enrichment XSLT can match "Calcium" without a specimen context.
     */
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

    /**
     * Creates a Patient and an inbound-zone Observation (NullFlavor/OTH + code.text = "Calcium")
     * in ViscoStore, then returns the Observation ID.
     */
    private String seedInboundObservation() {
        Patient patient = new Patient();
        patient.setActive(true);
        String patientId = storeClient.create().resource(patient).execute()
            .getId().getIdPart();

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

    /**
     * Reads stdout of the {@link ViscolinkLauncher} process line by line until a
     * {@code READY:{port}} line is found, then returns the port number.
     *
     * @param process        the launched process
     * @param timeoutSeconds maximum seconds to wait before failing
     * @throws IllegalStateException if the process exits or the timeout elapses before READY
     */
    private int readReadyPort(Process process, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive()) {
                    throw new IllegalStateException(
                        "ViscolinkLauncher process exited with code " + process.exitValue() +
                        " before printing READY");
                }
                if (reader.ready()) {
                    line = reader.readLine();
                    if (line != null) {
                        System.out.println("[viscolink] " + line);
                        if (line.startsWith("READY:")) {
                            return Integer.parseInt(line.substring("READY:".length()).trim());
                        }
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
}
