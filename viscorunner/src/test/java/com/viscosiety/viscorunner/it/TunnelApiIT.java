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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.viscosiety.viscorunner.tunnel.TestCertificates;

/**
 * The tunnel side on a real Frank!Framework: {@code TunnelApiServlet} at {@code /tunnel-api/*} under its own
 * {@code servlet.tunnelApi.*} login, served on an HTTPS tunnel port only, next to the public
 * {@code ApiListenerServlet} at {@code /api/*} with a different login. The launcher has no rewrite rules, so the
 * tunnel side is called at {@code /viscolink/tunnel-api/...} directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TunnelApiIT {

    private static final Path LAUNCHER_LIBS = Paths.get("target/launcher-libs").toAbsolutePath();

    private static String lib(String artifactId) {
        return LAUNCHER_LIBS.resolve(artifactId + ".jar").toString();
    }

    private final List<String> output = Collections.synchronizedList(new ArrayList<>());
    private Process process;
    private int publicPort;
    private int tunnelPort;
    private String publicPassword;
    private String sitePassword;
    private HttpClient https;
    private HttpClient http;

    @BeforeAll
    void start() throws Exception {
        Path viscolinkWar = Paths.get("target/viscolink.war").toAbsolutePath();
        Path configs = Paths.get("src/test/resources/tunnel-api").toAbsolutePath();
        Path testClasses = Paths.get("target/test-classes").toAbsolutePath();
        Path classes = Paths.get("target/classes").toAbsolutePath();
        Path healthOverlay = Paths.get("target/packs/health").toAbsolutePath();
        if (!Files.exists(viscolinkWar)) {
            throw new IllegalStateException("viscolink WAR not found at " + viscolinkWar
                    + " — run `mvn install -pl viscolink,packs/health && mvn package -pl viscorunner` first");
        }

        publicPassword = UUID.randomUUID().toString();
        sitePassword = UUID.randomUUID().toString();
        String keystorePassword = UUID.randomUUID().toString();

        Path work = Files.createTempDirectory("tunnel-api-it-");
        Path keystore = TestCertificates.pkcs12(work, keystorePassword);
        Path passwordFile = work.resolve("keystore.password");
        Files.writeString(passwordFile, keystorePassword);
        tunnelPort = findFreePort();
        Path settings = work.resolve("connectors.properties");
        Files.writeString(settings, "ports=" + tunnelPort + "\n"
                + tunnelPort + ".keystoreFile=" + keystore + "\n"
                + tunnelPort + ".keystorePasswordFile=" + passwordFile + "\n");

        https = TestCertificates.client(keystore, keystorePassword);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        Path h2Dir = Files.createTempDirectory("tunnel-api-it-h2-");
        String h2Url = "jdbc:h2:file:" + h2Dir.resolve("db") + ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";

        String javaExe = ProcessHandle.current().info().command()
                .orElse(Paths.get(System.getProperty("java.home"), "bin/java").toString());
        String classpath = String.join(System.getProperty("path.separator"),
                lib("tomcat-embed-core"), lib("tomcat-embed-websocket"), lib("tomcat-embed-el"),
                lib("tomcat-embed-jasper"), lib("tomcat-dbcp"), lib("jakarta.annotation-api"), lib("h2"),
                testClasses.toString(), classes.toString());

        List<String> command = new ArrayList<>(List.of(javaExe,
                "-Dspring.main.allow-circular-references=true",
                "-Dviscolink.overlay.dir=" + healthOverlay,
                "-Dvisco.tunnel.api.connectors=" + settings,
                "-Dapplication.security.http.authentication=true",
                "-Dapplication.security.http.transportGuarantee=NONE",
                "-Dservlet.tunnelApi.transportGuarantee=CONFIDENTIAL",
                "-Dapplication.security.http.authenticators=publicBasic,tunnelBasic",
                "-Dapplication.security.http.authenticators.publicBasic.type=IN_MEMORY",
                "-Dapplication.security.http.authenticators.publicBasic.username=public-user",
                "-Dapplication.security.http.authenticators.publicBasic.password=" + publicPassword,
                "-Dapplication.security.http.authenticators.tunnelBasic.type=IN_MEMORY",
                "-Dapplication.security.http.authenticators.tunnelBasic.username=site-user",
                "-Dapplication.security.http.authenticators.tunnelBasic.password=" + sitePassword,
                "-Dservlet.ApiListenerServlet.authenticator=publicBasic",
                "-Dservlet.ApiListenerServlet.securityRoles=IbisTester",
                "-Dservlet.tunnelApi.authenticator=tunnelBasic",
                "-Dservlet.tunnelApi.securityRoles=IbisTester",
                "-cp", classpath,
                "com.viscosiety.viscorunner.it.ViscolinkLauncher",
                viscolinkWar.toString(), configs.toString(), "http://localhost:1/fhir/", h2Url, "unused",
                "tunnelapi"));
        process = new ProcessBuilder(command).redirectErrorStream(true).start();
        publicPort = readReadyPort(process.getInputStream());
        log("launcher ready on " + publicPort + ", tunnel port " + tunnelPort);

        long deadline = System.currentTimeMillis() + 180_000;
        int last = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                last = https.send(get(tunnel("/viscolink/tunnel-api/open"), "site-user", sitePassword),
                        HttpResponse.BodyHandlers.ofString()).statusCode();
                if (last == 200) {
                    log("tunnel side ready");
                    return;
                }
            } catch (Exception e) {
                last = 0;
            }
            Thread.sleep(2000);
        }
        printOutput();
        throw new IllegalStateException("tunnel side not ready after 3 minutes (last status " + last + ")");
    }

    @AfterAll
    void stop() {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    @Test
    void tunnelOnlyListenerAnswersOnTheTunnelSide() throws Exception {
        HttpResponse<String> response = https.send(post(tunnel("/viscolink/tunnel-api/feed"), "site-user", sitePassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("feed", response.body().trim());
    }

    @Test
    void plainApiListenerAnswersOnTheTunnelSideToo() throws Exception {
        HttpResponse<String> response = https.send(get(tunnel("/viscolink/tunnel-api/open"), "site-user", sitePassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("open", response.body().trim());
    }

    @Test
    void tunnelSideRefusesAnonymousCallers() throws Exception {
        HttpResponse<String> response = https.send(get(tunnel("/viscolink/tunnel-api/open"), null, null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void tunnelSideRefusesThePublicLogin() throws Exception {
        HttpResponse<String> response = https.send(get(tunnel("/viscolink/tunnel-api/open"), "public-user", publicPassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void publicSideAnswersThePublicLogin() throws Exception {
        HttpResponse<String> response = http.send(get(open("/viscolink/api/open"), "public-user", publicPassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("open", response.body().trim());
    }

    @Test
    void publicSideRefusesTheSiteLogin() throws Exception {
        HttpResponse<String> response = http.send(get(open("/viscolink/api/open"), "site-user", sitePassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void tunnelServletIsNotReachableOnThePublicPort() throws Exception {
        HttpResponse<String> response = http.send(get(open("/viscolink/tunnel-api/open"), "site-user", sitePassword),
                HttpResponse.BodyHandlers.ofString());
        log("public port, /tunnel-api/open: " + response.statusCode());
        assertNotEquals(200, response.statusCode());
        assertFalse(response.body().contains("open"), "body must not carry the adapter's text");
    }

    @Test
    void unknownPathOnTheTunnelSideIsNotFound() throws Exception {
        HttpResponse<String> response = https.send(get(tunnel("/viscolink/tunnel-api/nothing-here"), "site-user", sitePassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }

    @Test
    void mixedPatternAnswersBothMethodsOnTheTunnelSide() throws Exception {
        HttpResponse<String> posted = https.send(post(tunnel("/viscolink/tunnel-api/mixed"), "site-user", sitePassword),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> got = https.send(get(tunnel("/viscolink/tunnel-api/mixed"), "site-user", sitePassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, posted.statusCode());
        assertEquals("mixed-post", posted.body().trim());
        assertEquals(200, got.statusCode());
        assertEquals("mixed-get", got.body().trim());
    }

    @Test
    void tunnelOnlyListenerIsNotFoundOnThePublicSide() throws Exception {
        HttpResponse<String> response = http.send(post(open("/viscolink/api/feed"), "public-user", publicPassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void tunnelOnlyListenerAsksAnonymousCallersToLogInLikeAnyPath() throws Exception {
        HttpResponse<String> response = http.send(post(open("/viscolink/api/feed"), null, null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void unknownPathAsksAnonymousCallersToLogIn() throws Exception {
        HttpResponse<String> response = http.send(post(open("/viscolink/api/nothing-here"), null, null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void mixedPatternHidesOnlyItsTunnelMethodOnThePublicSide() throws Exception {
        HttpResponse<String> posted = http.send(post(open("/viscolink/api/mixed"), "public-user", publicPassword),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> got = http.send(get(open("/viscolink/api/mixed"), "public-user", publicPassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, posted.statusCode());
        assertEquals("", posted.body());
        assertEquals(200, got.statusCode());
        assertEquals("mixed-get", got.body().trim());
        String allow = got.headers().firstValue("Allow").orElse("");
        log("public GET /api/mixed Allow: " + allow);
        assertTrue(allow.contains("GET"), allow);
        assertFalse(allow.contains("POST"), allow);

        HttpResponse<String> withOrigin = http.send(withLogin(HttpRequest.newBuilder(open("/viscolink/api/mixed"))
                .timeout(Duration.ofSeconds(30)).header("Origin", "https://elsewhere.example").GET(),
                "public-user", publicPassword).build(), HttpResponse.BodyHandlers.ofString());
        String corsMethods = withOrigin.headers().firstValue("Access-Control-Allow-Methods").orElse("");
        log("public GET /api/mixed with Origin, Access-Control-Allow-Methods: " + corsMethods);
        assertEquals(200, withOrigin.statusCode());
        assertTrue(corsMethods.contains("GET"), corsMethods);
        assertFalse(corsMethods.contains("POST"), corsMethods);
    }

    @Test
    void publicPreflightOfAMixedPatternLeavesOutItsTunnelMethods() throws Exception {
        HttpResponse<String> response = http.send(options(open("/viscolink/api/mixed"), "public-user", publicPassword),
                HttpResponse.BodyHandlers.ofString());
        String methods = response.headers().firstValue("Access-Control-Allow-Methods").orElse("");
        log("public OPTIONS /api/mixed: " + response.statusCode() + ", Access-Control-Allow-Methods: " + methods);
        assertEquals(200, response.statusCode());
        assertTrue(methods.contains("GET"), methods);
        assertFalse(methods.contains("POST"), methods);
    }

    @Test
    void tunnelSideStillListsEveryMethodOfAMixedPattern() throws Exception {
        HttpResponse<String> preflight = https.send(options(tunnel("/viscolink/tunnel-api/mixed"), "site-user", sitePassword),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> got = https.send(get(tunnel("/viscolink/tunnel-api/mixed"), "site-user", sitePassword),
                HttpResponse.BodyHandlers.ofString());
        String methods = preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("");
        String allow = got.headers().firstValue("Allow").orElse("");
        log("tunnel OPTIONS /mixed Access-Control-Allow-Methods: " + methods + "; GET Allow: " + allow);
        assertTrue(methods.contains("POST"), methods);
        assertTrue(allow.contains("POST"), allow);
    }

    @Test
    void perPatternOpenApiOfATunnelPatternLooksLikeOneThatDoesNotExist() throws Exception {
        HttpResponse<String> tunnelPattern = http.send(get(open("/viscolink/api/feed/openapi.json"), "public-user", publicPassword),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> missing = http.send(get(open("/viscolink/api/nothing-here/openapi.json"), "public-user", publicPassword),
                HttpResponse.BodyHandlers.ofString());
        log("public /api/feed/openapi.json: " + tunnelPattern.statusCode() + " (" + tunnelPattern.body().length()
                + " chars), /api/nothing-here/openapi.json: " + missing.statusCode() + " (" + missing.body().length() + " chars)");
        assertEquals(404, missing.statusCode());
        assertEquals(missing.statusCode(), tunnelPattern.statusCode());
        assertEquals(missing.body(), tunnelPattern.body());
        assertEquals(missing.headers().firstValue("Content-Type"), tunnelPattern.headers().firstValue("Content-Type"));
    }

    @Test
    void publicOpenApiDocumentLeavesOutTunnelPatterns() throws Exception {
        HttpResponse<String> response = http.send(get(open("/viscolink/api/openapi.json"), "public-user", publicPassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"/open\""), body);
        assertFalse(body.contains("\"/feed\""), body);
        assertFalse(body.contains("\"/mixed\""), body);
    }

    @Test
    void tunnelOpenApiDocumentListsEveryPattern() throws Exception {
        HttpResponse<String> response = https.send(get(tunnel("/viscolink/tunnel-api/openapi.json"), "site-user", sitePassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"/open\""), body);
        assertTrue(body.contains("\"/feed\""), body);
        assertTrue(body.contains("\"/mixed\""), body);
    }

    @Test
    void encodedAndTrailingSlashFormsOfATunnelPathAreNotFound() throws Exception {
        int encoded = rawPublicStatus("POST", "/viscolink/api/%66eed", "public-user", publicPassword);
        int trailing = rawPublicStatus("POST", "/viscolink/api/feed/", "public-user", publicPassword);
        log("public POST /api/%66eed: " + encoded + ", /api/feed/: " + trailing);
        assertEquals(404, encoded);
        assertEquals(404, trailing);
    }

    @Test
    void dotSegmentFormOfATunnelPathDoesNotReachIt() throws Exception {
        int status = rawPublicStatus("POST", "/viscolink/api/x/../feed", "public-user", publicPassword);
        log("public POST /api/x/../feed: " + status);
        assertNotEquals(200, status);
    }

    /** Sends the request line exactly as given (no client-side normalisation) to the public port; returns the status. */
    private int rawPublicStatus(String method, String rawPath, String user, String password) throws Exception {
        String token = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        String request = method + " " + rawPath + " HTTP/1.1\r\n"
                + "Host: localhost:" + publicPort + "\r\n"
                + "Authorization: Basic " + token + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: 4\r\n"
                + "Connection: close\r\n\r\n"
                + "ping";
        try (Socket socket = new Socket("localhost", publicPort)) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = reader.readLine();
            if (statusLine == null) {
                throw new IllegalStateException("no answer for " + method + " " + rawPath);
            }
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    private URI tunnel(String path) {
        return URI.create("https://localhost:" + tunnelPort + path);
    }

    private URI open(String path) {
        return URI.create("http://localhost:" + publicPort + path);
    }

    private static HttpRequest get(URI uri, String user, String password) {
        return withLogin(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET(), user, password).build();
    }

    private static HttpRequest options(URI uri, String user, String password) {
        return withLogin(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Origin", "https://elsewhere.example")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()), user, password).build();
    }

    private static HttpRequest post(URI uri, String user, String password) {
        return withLogin(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("ping")), user, password).build();
    }

    private static HttpRequest.Builder withLogin(HttpRequest.Builder builder, String user, String password) {
        if (user != null) {
            String token = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }
        return builder;
    }

    /** Reads the launcher's output until READY:<port>, then keeps collecting it for a failure report. */
    private int readReadyPort(InputStream stream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 180_000;
        String line;
        while (System.currentTimeMillis() < deadline && (line = reader.readLine()) != null) {
            output.add(line);
            if (line.startsWith("READY:")) {
                Thread drain = new Thread(() -> {
                    try {
                        String rest;
                        while ((rest = reader.readLine()) != null) {
                            output.add(rest);
                        }
                    } catch (Exception ignored) {
                        // the process was stopped
                    }
                }, "tunnel-api-it-output");
                drain.setDaemon(true);
                drain.start();
                return Integer.parseInt(line.substring("READY:".length()).trim());
            }
        }
        printOutput();
        throw new IllegalStateException("launcher did not report READY");
    }

    /** The launcher's output, with the generated passwords masked. */
    private void printOutput() {
        synchronized (output) {
            for (String line : output) {
                String masked = line;
                for (String secret : new String[] {publicPassword, sitePassword}) {
                    if (secret != null) {
                        masked = masked.replace(secret, "[password]");
                    }
                }
                System.out.println("[launcher] " + masked);
            }
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static void log(String message) {
        System.out.printf("[%s][TunnelApiIT] %s%n", LocalTime.now().toString().substring(0, 12), message);
    }
}
