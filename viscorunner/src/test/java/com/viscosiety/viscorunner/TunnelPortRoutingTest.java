package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.rewrite.RewriteValve;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.viscosiety.viscorunner.tunnel.TestCertificates;
import com.viscosiety.viscorunner.tunnel.TunnelPortListener;
import com.viscosiety.viscorunner.tunnel.TunnelPortSettings;

/**
 * The image's routing by ARRIVAL PORT, run in a real embedded Tomcat with the image's own rewrite.config and
 * the production {@link TunnelPortListener}: on a tunnel port /api/** goes to /runtime/tunnel-api/** and
 * everything else is 404; on the public port /tunnel-api does not exist. Stand-in servlets echo which one
 * answered.
 */
class TunnelPortRoutingTest {

    private static final Path IMAGE_RULES = Path.of("conf/Catalina/localhost/rewrite.config");
    private static final String PASSWORD = "routing-pass";

    /** A running embedded Tomcat plus what a test needs to call it. */
    private record Running(Tomcat tomcat, int publicPort) {
        void stop() throws Exception {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private static Running main;
    private static int tunnelPort;
    private static int pemTunnelPort;
    private static HttpClient client;
    private static Path keystore;

    @BeforeAll
    static void start() throws Exception {
        Path base = Files.createTempDirectory("tunnel-routing");
        keystore = TestCertificates.pkcs12(base, PASSWORD);
        Path[] pem = TestCertificates.pemPair(base, keystore, PASSWORD);
        Files.writeString(base.resolve("pw"), PASSWORD);
        tunnelPort = freePort();
        pemTunnelPort = freePort();
        Path settings = base.resolve("connectors.properties");
        Files.writeString(settings, "ports=" + tunnelPort + "," + pemTunnelPort + "\n"
                + tunnelPort + ".keystoreFile=" + keystore + "\n" + tunnelPort + ".keystorePasswordFile=" + base.resolve("pw") + "\n"
                + pemTunnelPort + ".certificateFile=" + pem[0] + "\n" + pemTunnelPort + ".certificateKeyFile=" + pem[1] + "\n");
        main = startTomcat(settings, true);
        client = TestCertificates.client(keystore, PASSWORD);
    }

    @AfterAll
    static void stop() throws Exception {
        main.stop();
        System.clearProperty(TunnelPortListener.PORTS_PROPERTY);
    }

    /**
     * The rules say 8080 for the public port. The plain connector listens on 8080 when that is free; otherwise
     * (or when {@code wantPublic8080} is false) it listens on a free port and the 8080 in the rules this Tomcat
     * loads is replaced by that port -- every other rule is the image's own.
     */
    private static Running startTomcat(Path settingsFile, boolean wantPublic8080) throws Exception {
        Path base = Files.createTempDirectory("tunnel-routing-tomcat");
        int publicPort = wantPublic8080 && isFree(8080) ? 8080 : freePort();
        Path rules = base.resolve("conf/Tomcat/localhost/rewrite.config");
        Files.createDirectories(rules.getParent());
        Files.writeString(rules, Files.readString(IMAGE_RULES).replace("8080", String.valueOf(publicPort)));
        EmbeddedTomcatSupport.useBaseDir(base);

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        tomcat.setPort(publicPort);
        tomcat.getConnector(); // the public connector exists before start; the listener adds the tunnel ones
        String old = System.getProperty(TunnelPortSettings.FILE_PROPERTY);
        System.setProperty(TunnelPortSettings.FILE_PROPERTY, settingsFile == null ? base.resolve("none.properties").toString() : settingsFile.toString());
        try {
            tomcat.getServer().addLifecycleListener(new TunnelPortListener());
        } finally {
            if (old == null) {
                System.clearProperty(TunnelPortSettings.FILE_PROPERTY);
            } else {
                System.setProperty(TunnelPortSettings.FILE_PROPERTY, old);
            }
        }
        Context runtime = tomcat.addContext("/runtime", new File(".").getAbsolutePath());
        addEcho(runtime, "api", "/api/*");
        addEcho(runtime, "tunnelApi", "/tunnel-api/*");
        addEcho(runtime, "console", "/iaf/*");
        tomcat.addContext("", new File(".").getAbsolutePath());
        tomcat.getHost().getPipeline().addValve(new RewriteValve());
        tomcat.start();
        System.clearProperty(TunnelPortListener.PORTS_PROPERTY);
        return new Running(tomcat, publicPort);
    }

    private static void addEcho(Context context, String name, String mapping) {
        Tomcat.addServlet(context, name, new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.getWriter().write(name + " path=" + request.getPathInfo() + " query=" + request.getQueryString()
                        + " secure=" + request.isSecure() + " port=" + request.getLocalPort());
            }
        });
        context.addServletMappingDecoded(mapping, name);
    }

    private static HttpResponse<String> get(String scheme, int port, String path, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(scheme + "://localhost:" + port + path))
                .header("Accept", accept).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> tunnel(String path, String accept) throws Exception {
        return get("https", tunnelPort, path, accept);
    }

    private static HttpResponse<String> publicGet(String path, String accept) throws Exception {
        return get("http", main.publicPort(), path, accept);
    }

    private static void assertTunnelEcho(HttpResponse<String> response, String pathInfo, String query) {
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().startsWith("tunnelApi path=" + pathInfo + " query=" + query + " secure=true port="), response.body());
    }

    private static void assertTunnelEcho(HttpResponse<String> response, String pathInfo, String query, int port) {
        assertTunnelEcho(response, pathInfo, query);
        assertTrue(response.body().endsWith(" port=" + port), response.body());
    }

    /** A request line sent as written over a raw socket (java.net.http may normalise paths); returns status line + body. */
    private static String raw(int port, String path) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAccept: application/json\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void anApiCallOnATunnelPortReachesTheTunnelServletWhateverTheStartOfThePath() throws Exception {
        for (String accept : new String[] {"application/json", "text/html"}) {
            for (String path : new String[] {"/runtime/api/x", "/api/x", "/viscolink/api/x"}) {
                assertTunnelEcho(tunnel(path, accept), "/x", "null", tunnelPort);
            }
        }
    }

    @Test
    void theQueryStringArrivesUnchanged() throws Exception {
        assertTunnelEcho(tunnel("/runtime/api/x?a=1&b=2", "application/json"), "/x", "a=1&b=2");
    }

    @Test
    void theApiRootWithAndWithoutASlash() throws Exception {
        for (String path : new String[] {"/runtime/api", "/runtime/api/"}) {
            HttpResponse<String> response = tunnel(path, "application/json");
            assertEquals(200, response.statusCode(), path);
            assertTrue(response.body().startsWith("tunnelApi "), path + ": " + response.body());
        }
    }

    @Test
    void everythingElseOnATunnelPortIsNotFound() throws Exception {
        for (String path : new String[] {"/runtime/iaf/gui/", "/", "/console/ui/", "/fhir-store/fhir/metadata",
                "/runtime/tunnel-apix", "/runtime/apix/y", "/runtime/tunnel-api/x", "/viscolink/tunnel-api/x", "/tunnel-api/x"}) {
            HttpResponse<String> response = tunnel(path, "text/html");
            assertEquals(404, response.statusCode(), path + " body=" + response.body());
        }
    }

    @Test
    void tricksWithDotsDoubleSlashesAndCaseNeverReachTheConsole() throws Exception {
        for (String path : new String[] {"/runtime/api/%2e%2e/iaf/x", "/runtime/api/../iaf/x", "/runtime//api/x", "/RUNTIME/api/x"}) {
            HttpResponse<String> response = tunnel(path, "application/json");
            assertFalse(response.body().startsWith("console"), path + " reached /iaf: " + response.body());
            assertTrue(response.statusCode() == 404 || response.body().startsWith("tunnelApi "),
                    path + " answered " + response.statusCode() + " " + response.body());
        }
    }

    @Test
    void thePublicPortKeepsItsApiAndHasNoTunnelServlet() throws Exception {
        HttpResponse<String> api = publicGet("/runtime/api/x", "application/json");
        assertEquals("api path=/x query=null secure=false port=" + main.publicPort(), api.body());
        for (String path : new String[] {"/runtime/tunnel-api/x", "/viscolink/tunnel-api/x", "/runtime/tunnel-api"}) {
            assertEquals(404, publicGet(path, "application/json").statusCode(), path);
        }
        assertEquals("api path=/x query=null secure=false port=" + main.publicPort(), publicGet("/viscolink/api/x", "application/json").body(), "old path, in place");
        for (String path : new String[] {"/runtime/tunnel-api;x=1/y", "/runtime/./tunnel-api/x", "/runtime//tunnel-api/x",
                "/runtime/api/../tunnel-api/x", "/runtime/tunnel%2dapi/x"}) {
            String answer = raw(main.publicPort(), path);
            assertFalse(answer.contains("tunnelApi"), path + " reached the tunnel servlet: " + answer);
            String status = answer.lines().findFirst().orElse("");
            assertTrue(status.contains(" 404") || status.contains(" 400"), path + " answered " + status);
        }
        HttpResponse<String> page = publicGet("/viscolink/iaf/gui/", "text/html");
        assertEquals(302, page.statusCode());
        assertEquals("/runtime/iaf/gui/", page.headers().firstValue("Location").orElse(""));
    }

    @Test
    void aPemCertificateAndKeyPairAnswersToo() throws Exception {
        assertTunnelEcho(get("https", pemTunnelPort, "/runtime/api/orders", "application/json"), "/orders", "null");
    }

    @Test
    void withoutASettingsFileThereIsOneConnectorAndNoTunnelPort() throws Exception {
        Running plain = startTomcat(null, false);
        try {
            assertEquals(1, plain.tomcat().getService().findConnectors().length);
            assertNull(System.getProperty(TunnelPortListener.PORTS_PROPERTY));
        } finally {
            plain.stop();
        }
    }

    @Test
    void aBadPortIsSkippedAndTheGoodOneAnswers() throws Exception {
        Path dir = Files.createTempDirectory("tunnel-routing-bad");
        Files.writeString(dir.resolve("pw"), PASSWORD);
        int good = freePort();
        Path settings = dir.resolve("connectors.properties");
        Files.writeString(settings, "ports=" + good + ",9\n"
                + good + ".keystoreFile=" + keystore + "\n" + good + ".keystorePasswordFile=" + dir.resolve("pw") + "\n"
                + "9.keystoreFile=" + keystore + "\n9.keystorePasswordFile=" + dir.resolve("pw") + "\n");
        Running running = startTomcat(settings, false);
        try {
            assertEquals(2, running.tomcat().getService().findConnectors().length);
            assertTunnelEcho(get("https", good, "/runtime/api/x", "application/json"), "/x", "null");
        } finally {
            running.stop();
        }
    }

    @Test
    void aTunnelPortThatIsAlreadyInUseIsSkippedAndTheRuntimeStillStarts() throws Exception {
        Path dir = Files.createTempDirectory("tunnel-routing-busy");
        Files.writeString(dir.resolve("pw"), PASSWORD);
        int good = freePort();
        try (ServerSocket busy = new ServerSocket(0)) {
            int busyPort = busy.getLocalPort();
            Path settings = dir.resolve("connectors.properties");
            Files.writeString(settings, "ports=" + busyPort + "," + good + "\n"
                    + busyPort + ".keystoreFile=" + keystore + "\n" + busyPort + ".keystorePasswordFile=" + dir.resolve("pw") + "\n"
                    + good + ".keystoreFile=" + keystore + "\n" + good + ".keystorePasswordFile=" + dir.resolve("pw") + "\n");
            Running running = startTomcat(settings, false);
            try {
                assertEquals(2, running.tomcat().getService().findConnectors().length, "public + the good tunnel port");
                assertTunnelEcho(get("https", good, "/runtime/api/x", "application/json"), "/x", "null");
                assertEquals("api path=/x query=null secure=false port=" + running.publicPort(), get("http", running.publicPort(), "/runtime/api/x", "application/json").body());
            } finally {
                running.stop();
            }
        }
    }

    private static boolean isFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
