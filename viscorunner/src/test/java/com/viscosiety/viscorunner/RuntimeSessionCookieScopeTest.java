package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.rewrite.RewriteValve;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * A hosted page signed in under /runtime that calls the API by its old /viscolink path (2026-11-08,
 * found live: a configuration's page at /runtime/webcontent/... fetching /viscolink/api/... got 401).
 *
 * <p>The browser sends a cookie only to paths under the cookie's Path. With the runtime's session
 * cookie scoped to /runtime, a same-origin fetch of /viscolink/api/... left without it; the Host's
 * RewriteValve then served the request in place at /runtime/api/..., where no session was found, so
 * the console's OIDC login never reached the API (ApiSessionAuthListener had nothing to bridge).</p>
 *
 * <p>This test runs a real Tomcat with the image's own rewrite rules and the session-cookie settings
 * of the image's own /runtime context descriptor, and a client whose cookie jar follows the cookie
 * Path like a browser does.</p>
 */
class RuntimeSessionCookieScopeTest {

    private static final Path REWRITE_RULES = Path.of("conf/Catalina/localhost/rewrite.config");
    /** Copied to conf/Catalina/localhost/runtime.xml by the Dockerfile: the /runtime context's descriptor. */
    private static final Path RUNTIME_CONTEXT = Path.of("conf/Catalina/localhost/viscolink.xml");

    private static Tomcat tomcat;
    private static int port;

    @BeforeAll
    static void startTomcat() throws Exception {
        Path base = Files.createTempDirectory("runtime-cookie");
        // The valve reads its rules from <base>/conf/<engine>/<host>/rewrite.config at start, as in the
        // image (conf/Catalina/localhost/rewrite.config); the embedded engine is called "Tomcat".
        Path rules = base.resolve("conf/Tomcat/localhost/rewrite.config");
        Files.createDirectories(rules.getParent());
        // The image's rules treat every port but 8080 as a tunnel port (tunnel-api routing), so the rules this
        // Tomcat loads name its own port instead of 8080; every other rule is the image's own.
        int publicPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            publicPort = probe.getLocalPort();
        }
        Files.writeString(rules, Files.readString(REWRITE_RULES).replace("8080", String.valueOf(publicPort)));
        EmbeddedTomcatSupport.useBaseDir(base); // not the first embedded Tomcat of the JVM's base dir
        tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        tomcat.setPort(publicPort);

        Context runtime = tomcat.addContext("/runtime", new File(".").getAbsolutePath());
        Element descriptor = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(RUNTIME_CONTEXT.toFile()).getDocumentElement();
        if (descriptor.hasAttribute("sessionCookiePath")) {
            runtime.setSessionCookiePath(descriptor.getAttribute("sessionCookiePath"));
        }
        if (descriptor.hasAttribute("sessionCookieName")) {
            runtime.setSessionCookieName(descriptor.getAttribute("sessionCookieName"));
        }
        // Stand-ins: a page that signs the visitor in (any session will do), and an API that says
        // whether the request arrived with that session.
        Tomcat.addServlet(runtime, "login", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                request.getSession(true).setAttribute("user", "visitor");
                response.getWriter().write("signed in");
            }
        });
        runtime.addServletMappingDecoded("/webcontent/*", "login");
        Tomcat.addServlet(runtime, "api", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.getWriter().write(request.getSession(false) == null ? "no session" : "session");
            }
        });
        runtime.addServletMappingDecoded("/api/*", "api");

        tomcat.getHost().getPipeline().addValve(new RewriteValve());
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stopTomcat() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    private static HttpResponse<String> get(HttpClient client, String path, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", accept).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient browser(CookieManager jar) {
        return HttpClient.newBuilder().cookieHandler(jar).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Test
    void aPageSignedInUnderRuntimeReachesTheApiByItsOldViscolinkPath() throws Exception {
        CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = browser(jar);
        assertEquals(200, get(client, "/runtime/webcontent/tenant/aex.html", "text/html").statusCode());

        HttpResponse<String> oldPath = get(client, "/viscolink/api/aex-bars", "application/json");
        assertEquals(200, oldPath.statusCode());
        assertEquals("session", oldPath.body(), "the old path is served in place AND carries the session");
        assertEquals("session", get(client, "/runtime/api/aex-bars", "application/json").body());
    }

    @Test
    void theRuntimeSessionCookieCoversTheWholeSiteUnderItsOwnName() throws Exception {
        CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        get(browser(jar), "/runtime/webcontent/tenant/aex.html", "text/html");
        List<HttpCookie> cookies = jar.getCookieStore().getCookies();
        assertEquals(1, cookies.size(), "one session cookie: " + cookies);
        assertEquals("/", cookies.get(0).getPath(), "sent to /viscolink/... as well as /runtime/...");
        // Not JSESSIONID: the other webapps (/console, /fhir-store) keep their own JSESSIONID under their
        // own path, and a site-wide cookie with that name would travel next to theirs.
        assertEquals("RUNTIMESESSIONID", cookies.get(0).getName());
    }

    @Test
    void aBrowserPageLoadOfTheOldPathIsStillRedirected() throws Exception {
        HttpResponse<String> page = get(browser(new CookieManager()), "/viscolink/webcontent/tenant/aex.html", "text/html");
        assertEquals(302, page.statusCode());
        assertEquals("/runtime/webcontent/tenant/aex.html", page.headers().firstValue("Location").orElse(""));
    }
}
