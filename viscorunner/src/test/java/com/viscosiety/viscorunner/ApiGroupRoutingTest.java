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

import com.viscosiety.viscorunner.apigroups.ApiGroupNames;
import com.viscosiety.viscorunner.tunnel.TestCertificates;
import com.viscosiety.viscorunner.tunnel.TunnelPortListener;
import com.viscosiety.viscorunner.tunnel.TunnelPortSettings;

/** The image's routing of /runtime/api/<group>/ to the group's servlet, with the image's own rewrite.config. */
class ApiGroupRoutingTest {

    private static final Path IMAGE_RULES = Path.of("conf/Catalina/localhost/rewrite.config");
    private static final String PASSWORD = "group-routing-pass";

    private record Running(Tomcat tomcat, int publicPort) {
        void stop() throws Exception {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private static Running main;
    private static int tunnelPort;
    private static HttpClient client;

    @BeforeAll
    static void start() throws Exception {
        Path base = Files.createTempDirectory("group-routing");
        Path keystore = TestCertificates.pkcs12(base, PASSWORD);
        Files.writeString(base.resolve("pw"), PASSWORD);
        tunnelPort = freePort();
        Path settings = base.resolve("connectors.properties");
        Files.writeString(settings, "ports=" + tunnelPort + "\n"
                + tunnelPort + ".keystoreFile=" + keystore + "\n" + tunnelPort + ".keystorePasswordFile=" + base.resolve("pw") + "\n");
        System.setProperty(ApiGroupNames.PROPERTY, "webhooks");
        try {
            main = startTomcat(settings);
        } finally {
            System.clearProperty(ApiGroupNames.PROPERTY);
        }
        client = TestCertificates.client(keystore, PASSWORD);
    }

    @AfterAll
    static void stop() throws Exception {
        main.stop();
        System.clearProperty(TunnelPortListener.PORTS_PROPERTY);
        System.clearProperty(ApiGroupNames.PROPERTY);
    }

    private static Running startTomcat(Path settingsFile) throws Exception {
        Path base = Files.createTempDirectory("group-routing-tomcat");
        int publicPort = freePort();
        Path rules = base.resolve("conf/Tomcat/localhost/rewrite.config");
        Files.createDirectories(rules.getParent());
        Files.writeString(rules, Files.readString(IMAGE_RULES).replace("8080", String.valueOf(publicPort)));
        EmbeddedTomcatSupport.useBaseDir(base);

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        tomcat.setPort(publicPort);
        tomcat.getConnector();
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
        addEcho(runtime, "apiGroupWebhooks", "/api-group/webhooks/*");
        addEcho(runtime, "tunnelApi", "/tunnel-api/*");
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
                response.getWriter().write(name + " path=" + request.getPathInfo());
            }
        });
        context.addServletMappingDecoded(mapping, name);
    }

    private static HttpResponse<String> get(String scheme, int port, String path, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(scheme + "://localhost:" + port + path))
                .header("Accept", accept).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> publicGet(Running running, String path, String accept) throws Exception {
        return get("http", running.publicPort(), path, accept);
    }

    @Test
    void aListedGroupReachesItsServletOnBothPrefixes() throws Exception {
        for (String path : new String[] {"/runtime/api/webhooks/github", "/viscolink/api/webhooks/github"}) {
            HttpResponse<String> response = publicGet(main, path, "application/json");
            assertEquals(200, response.statusCode(), path + " " + response.body());
            assertEquals("apiGroupWebhooks path=/github", response.body(), path);
        }
    }

    @Test
    void theGroupRootWithoutASlashReachesItsServlet() throws Exception {
        HttpResponse<String> response = publicGet(main, "/runtime/api/webhooks", "application/json");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().startsWith("apiGroupWebhooks"), response.body());
    }

    @Test
    void anUnlistedGroupAndANameThatOnlyStartsLikeOneStayOnTheDefaultApi() throws Exception {
        assertEquals("api path=/orders", publicGet(main, "/runtime/api/orders", "application/json").body());
        assertEquals("api path=/webhooksx/a", publicGet(main, "/runtime/api/webhooksx/a", "application/json").body());
        // Group names are lower case and the rule compares exactly: another case is not the group.
        assertEquals("api path=/Webhooks/x", publicGet(main, "/runtime/api/Webhooks/x", "application/json").body());
    }

    @Test
    void aBrowserPageLoadOnTheOldPrefixIsRewrittenInPlaceNotRedirected() throws Exception {
        HttpResponse<String> response = publicGet(main, "/viscolink/api/webhooks/a", "text/html");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("apiGroupWebhooks path=/a", response.body());
    }

    @Test
    void aTunnelPortStillReachesTheTunnelServlet() throws Exception {
        HttpResponse<String> response = get("https", tunnelPort, "/api/webhooks/github", "application/json");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().startsWith("tunnelApi path=/webhooks/github"), response.body());
    }

    @Test
    void withoutTheSettingNoGroupIsRouted() throws Exception {
        Running plain = startTomcat(null);
        try {
            assertEquals("api path=/webhooks/github", publicGet(plain, "/runtime/api/webhooks/github", "application/json").body());
        } finally {
            plain.stop();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
