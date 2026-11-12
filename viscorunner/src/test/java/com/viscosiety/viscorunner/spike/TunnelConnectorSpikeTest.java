package com.viscosiety.viscorunner.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.ValveBase;
import org.apache.catalina.valves.rewrite.RewriteValve;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * SPIKE (2026-11-12, tunnel API) Q1: a Server LifecycleListener adds a TLS connector at start; the same
 * web application answers on it; a request can tell which connector it came in on; the image's
 * RewriteValve can route by the connector's port (%{SERVER_PORT} = request.getLocalPort() in Tomcat's
 * ResolverImpl), so the "tunnel valve" can be plain rewrite rules. Results in
 * docs/design/2026-11-12-tunnel-api-spike.md.
 */
// Off in the normal build: Tomcat's ConfigFileLoader keeps the FIRST embedded Tomcat's base dir for the whole
// JVM, so a spike Tomcat started before RuntimeSessionCookieScopeTest makes that test read no rewrite.config.
// Run with: ./mvnw test -pl viscorunner -Dtest='Tunnel*SpikeTest' -Dspike=true
@EnabledIfSystemProperty(named = "spike", matches = "true")
class TunnelConnectorSpikeTest {

    private static final Path IMAGE_RULES = Path.of("conf/Catalina/localhost/rewrite.config");
    private static final String PASSWORD = "spike-pass";

    private static Tomcat tomcat;
    private static TunnelConnectorSpikeListener listener;
    private static int publicPort;
    private static int tunnelPort;
    private static int pemTunnelPort;
    private static HttpClient client;

    @BeforeAll
    static void start() throws Exception {
        Path base = Files.createTempDirectory("tunnel-spike");
        Path keystore = base.resolve("tunnel.p12");
        generateKeystore(keystore);
        Path[] pem = exportPem(keystore, base);

        publicPort = freePort();
        tunnelPort = freePort();
        pemTunnelPort = freePort();

        // Tunnel rules first, then the image's own rules. "8080" stands for the public connector's port.
        String tunnelRules = """
                RewriteCond %{SERVER_PORT} !^8080$
                RewriteRule ^/(runtime|viscolink)/api(/.*)?$ /runtime/tunnel-api$2 [L]
                RewriteCond %{SERVER_PORT} !^8080$
                RewriteRule ^/.*$ /tunnel-not-found [L]
                RewriteCond %{SERVER_PORT} ^8080$
                RewriteRule ^/(runtime|viscolink)/tunnel-api(/.*)?$ /tunnel-not-found [L]
                """.replace("8080", String.valueOf(publicPort));
        Path rules = base.resolve("conf/Tomcat/localhost/rewrite.config");
        Files.createDirectories(rules.getParent());
        Files.writeString(rules, tunnelRules + Files.readString(IMAGE_RULES));

        tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        tomcat.setPort(publicPort);
        tomcat.getConnector(); // create the public connector BEFORE start, else getConnector() would return the tunnel one
        listener = new TunnelConnectorSpikeListener(List.of(
                TunnelConnectorSpikeListener.TunnelPort.pkcs12(tunnelPort, keystore.toString(), PASSWORD),
                TunnelConnectorSpikeListener.TunnelPort.pem(pemTunnelPort, pem[0].toString(), pem[1].toString())));
        tomcat.getServer().addLifecycleListener(listener);

        Context runtime = tomcat.addContext("/runtime", new File(".").getAbsolutePath());
        addEcho(runtime, "api", "/api/*");
        addEcho(runtime, "tunnelApi", "/tunnel-api/*");
        addEcho(runtime, "console", "/iaf/*");

        // Stand-in for "the request knows its connector": a Host valve before the RewriteValve marks it.
        tomcat.getHost().getPipeline().addValve(new ValveBase() {
            @Override
            public void invoke(Request request, Response response) throws IOException, ServletException {
                boolean onTunnel = listener.addedConnectors().contains(request.getConnector());
                request.setAttribute("onTunnelConnector", onTunnel);
                getNext().invoke(request, response);
            }
        });
        RewriteValve rewrite = new RewriteValve();
        tomcat.getHost().getPipeline().addValve(rewrite);
        tomcat.start();
        // Set the rules explicitly: the valve finds rewrite.config through catalina.base, a JVM-wide
        // system property that another embedded Tomcat in the same test JVM may have changed.
        rewrite.setConfiguration(Files.readString(rules));

        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            trust.load(in, PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        client = HttpClient.newBuilder().sslContext(ssl).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterAll
    static void stop() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    private static void addEcho(Context context, String name, String mapping) {
        Tomcat.addServlet(context, name, new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.getWriter().write(name + " path=" + request.getPathInfo() + " localPort=" + request.getLocalPort()
                        + " secure=" + request.isSecure() + " onTunnel=" + request.getAttribute("onTunnelConnector"));
            }
        });
        context.addServletMappingDecoded(mapping, name);
    }

    private static HttpResponse<String> get(String scheme, int port, String path, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(scheme + "://localhost:" + port + path))
                .header("Accept", accept).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theListenerAddedATlsConnectorThatServesTheSameWebApplication() throws Exception {
        assertEquals(3, tomcat.getService().findConnectors().length);
        HttpResponse<String> response = get("https", tunnelPort, "/runtime/api/orders/7", "application/json");
        assertEquals(200, response.statusCode());
        assertEquals("tunnelApi path=/orders/7 localPort=" + tunnelPort + " secure=true onTunnel=true", response.body());
    }

    @Test
    void aPemCertificateAndKeyPairWorkAsWell() throws Exception {
        HttpResponse<String> response = get("https", pemTunnelPort, "/runtime/api/orders", "application/json");
        assertEquals(200, response.statusCode());
        assertEquals("tunnelApi path=/orders localPort=" + pemTunnelPort + " secure=true onTunnel=true", response.body());
    }

    @Test
    void theOldPathOnTheTunnelPortReachesTheTunnelServletToo() throws Exception {
        HttpResponse<String> response = get("https", tunnelPort, "/viscolink/api/orders", "text/html");
        assertEquals(200, response.statusCode(), "tunnel rule runs before the image's old-path redirect");
        assertEquals("tunnelApi path=/orders localPort=" + tunnelPort + " secure=true onTunnel=true", response.body());
    }

    @Test
    void everythingElseOnTheTunnelPortIsNotFound() throws Exception {
        HttpResponse<String> probe = get("https", tunnelPort, "/runtime/iaf/gui/", "text/html");
        assertEquals(404, probe.statusCode(), "body=" + probe.body() + " headers=" + probe.headers().map());
        assertEquals(404, get("https", tunnelPort, "/runtime/tunnel-api/orders", "application/json").statusCode());
        assertEquals(404, get("https", tunnelPort, "/console/", "text/html").statusCode());
        assertEquals(404, get("https", tunnelPort, "/", "text/html").statusCode());
    }

    @Test
    void thePublicPortIsUnchangedAndCannotReachTheTunnelServlet() throws Exception {
        HttpResponse<String> api = get("http", publicPort, "/runtime/api/orders", "application/json");
        assertEquals("api path=/orders localPort=" + publicPort + " secure=false onTunnel=false", api.body());
        assertEquals(404, get("http", publicPort, "/runtime/tunnel-api/orders", "application/json").statusCode());
        assertEquals(302, get("http", publicPort, "/viscolink/iaf/gui/", "text/html").statusCode(), "image rules still apply");
    }

    @Test
    void withoutTheSettingNoTunnelConnectorIsAddedAndNothingListens() throws Exception {
        int reserved = freePort();
        Tomcat plain = new Tomcat();
        plain.setBaseDir(Files.createTempDirectory("tunnel-spike-plain").toString());
        plain.setPort(0);
        plain.getConnector();
        TunnelConnectorSpikeListener none = new TunnelConnectorSpikeListener(List.of());
        plain.getServer().addLifecycleListener(none);
        plain.addContext("/runtime", new File(".").getAbsolutePath());
        plain.start();
        try {
            assertEquals(1, plain.getService().findConnectors().length);
            assertEquals(List.<Connector>of(), none.addedConnectors());
            assertThrows(ConnectException.class, () -> new Socket("localhost", reserved).close());
        } finally {
            plain.stop();
            plain.destroy();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void generateKeystore(Path keystore) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "tunnel", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
    }

    /** Writes the keystore's certificate and (unencrypted PKCS#8) key as PEM files. */
    private static Path[] exportPem(Path keystore, Path dir) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, PASSWORD.toCharArray());
        }
        Certificate certificate = store.getCertificate("tunnel");
        Key key = store.getKey("tunnel", PASSWORD.toCharArray());
        Base64.Encoder encoder = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        Path cert = dir.resolve("tunnel.crt");
        Path keyFile = dir.resolve("tunnel.key");
        Files.writeString(cert, "-----BEGIN CERTIFICATE-----\n" + encoder.encodeToString(certificate.getEncoded()) + "\n-----END CERTIFICATE-----\n");
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\n" + encoder.encodeToString(key.getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        return new Path[] {cert, keyFile};
    }

}
