package com.viscosiety.viscorunner.it;

import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SingleServletGroupsIT {
    private final TunnelApiLauncher runtime = new TunnelApiLauncher("SingleServletGroupsIT", true,
            "src/test/resources/api-groups", "apigroups");
    private static final String API = "/runtime/api/";

    @BeforeAll
    void start() throws Exception {
        runtime.start(runtime.withConsoleLogin(List.of(
                "-Dviscolink.context.path=/runtime",
                "-Dviscolink.rewrite.rules=" + Path.of("conf/Catalina/localhost/rewrite.config").toAbsolutePath(),
                "-Dapplication.security.http.authentication=true",
                "-Dapplication.security.http.transportGuarantee=NONE",
                "-Dapplication.security.http.authenticators=login,key,site,agent,interactive",
                "-Dapplication.security.http.authenticators.login.type=IN_MEMORY",
                "-Dapplication.security.http.authenticators.login.username=reader",
                "-Dapplication.security.http.authenticators.login.password=" + runtime.publicPassword,
                "-Dapplication.security.http.authenticators.key.type=IN_MEMORY",
                "-Dapplication.security.http.authenticators.key.username=partner",
                "-Dapplication.security.http.authenticators.key.password=" + runtime.consolePassword,
                "-Dapplication.security.http.authenticators.key.apiKeyHeader=X-Customer-Key",
                "-Dapplication.security.http.authenticators.site.type=IN_MEMORY",
                "-Dapplication.security.http.authenticators.site.username=site",
                "-Dapplication.security.http.authenticators.site.password=" + runtime.sitePassword,
                "-Dservlet.tunnelApi.authenticator=site",
                "-Dservlet.tunnelApi.securityRoles=Site",
                "-Dapplication.security.http.authenticators.agent.type=IN_MEMORY",
                "-Dapplication.security.http.authenticators.agent.username=local-agent",
                "-Dapplication.security.http.authenticators.agent.password=" + runtime.consolePassword,
                "-Dservlet.agentApi.authenticator=agent",
                "-Dservlet.agentApi.securityRoles=TenantAgent",
                "-Dapplication.security.http.authenticators.interactive.type=OAUTH2",
                "-Dapplication.security.http.authenticators.interactive.provider=google",
                "-Dapplication.security.http.authenticators.interactive.clientId=local-test",
                "-Dapplication.security.http.authenticators.interactive.clientSecret=" + runtime.consolePassword,
                "-Dapplication.security.http.authenticators.interactive.roleMappingFile=oauth-role-mapping.properties",
                "-Dservlet.ApiListenerServlet.group.browser.authenticator=interactive",
                "-Dservlet.ApiListenerServlet.group.browser.securityRoles=BrowserUser",
                "-Dservlet.ApiListenerServlet.authenticator=GROUPED",
                "-Dservlet.ApiListenerServlet.authenticationGroups=webhooks,hospital,partner,browser",
                "-Dservlet.ApiListenerServlet.group.webhooks.authenticator=NONE",
                "-Dservlet.ApiListenerServlet.group.hospital.authenticator=login",
                "-Dservlet.ApiListenerServlet.group.hospital.securityRoles=Hospital",
                "-Dservlet.ApiListenerServlet.group.partner.authenticator=key",
                "-Dservlet.ApiListenerServlet.group.partner.securityRoles=Partner")));
        runtime.awaitReady(() -> get("webhooks/ping", false), "public listener");
    }

    private int get(String path, boolean basic) throws Exception {
        return runtime.send(runtime.http, TunnelApiLauncher.get(runtime.open(API + path),
                basic ? "reader" : null, basic ? runtime.publicPassword : null)).statusCode();
    }

    @Test
    void publicGroupEnforcesOwnership() throws Exception {
        runtime.assertLogLacks("registered servlet [api-webhooks]");
        assertEquals(200, runtime.send(runtime.http, TunnelApiLauncher.get(
                runtime.open("/viscolink/api/webhooks/ping"), null, null)).statusCode());
        // Unmapped runtime URLs can be challenged by the container before it returns a 404.
        int unmapped = runtime.send(runtime.http, TunnelApiLauncher.get(
                runtime.open("/runtime/unmapped-servlet/ping"), null, null)).statusCode();
        assertTrue(unmapped >= 400);
        assertEquals(unmapped, runtime.send(runtime.http, TunnelApiLauncher.get(
                runtime.open("/runtime/api-group/webhooks/ping"), null, null)).statusCode());
        assertEquals(403, get("ping", true));
        assertEquals(403, get("webhooks-extra/ping", false));
        assertEquals(404, get("webhooks/secret", false));
        var doc = runtime.send(runtime.http, TunnelApiLauncher.get(runtime.open(API + "webhooks/openapi.json"), null, null));
        assertEquals(200, doc.statusCode());
        assertTrue(doc.body().contains("/webhooks/ping"));
        assertFalse(doc.body().contains("{tenant}"));
    }

    @Test
    void protectedGroupsUseTheirOwnCredentials() throws Exception {
        assertEquals(401, get("hospital/ping", false));
        assertEquals(200, get("hospital/ping", true));
        assertEquals(401, get("partner/ping", true));
        var request = HttpRequest.newBuilder(runtime.open(API + "partner/ping"))
                .header("X-Customer-Key", runtime.consolePassword).build();
        assertEquals(200, runtime.send(runtime.http, request).statusCode());
    }

    @Test
    void consoleMetadataUsesRegisteredGroups() throws Exception {
        var uri = runtime.open("/runtime/iaf/api/visco-api-groups");
        assertEquals(401, runtime.send(runtime.http, TunnelApiLauncher.get(uri, null, null)).statusCode());
        var request = TunnelApiLauncher.get(uri,
                TunnelApiLauncher.CONSOLE_USER, runtime.consolePassword);
        var response = runtime.send(runtime.http, request);
        assertEquals(200, response.statusCode());
        assertEquals("[\"browser\",\"hospital\",\"partner\",\"webhooks\"]", response.body());
    }

    @Test
    void tunnelKeepsItsOwnPolicy() throws Exception {
        var uri = runtime.tunnel(API + "hospital/ping");
        assertEquals(401, runtime.send(runtime.https, TunnelApiLauncher.get(uri, "reader", runtime.publicPassword)).statusCode());
        assertEquals(200, runtime.send(runtime.https, TunnelApiLauncher.get(uri, "site", runtime.sitePassword)).statusCode());
    }

    @Test
    void authorizedGatewayCanForwardToGroup() throws Exception {
        var uri = runtime.open("/runtime/api-service/api/partner/ping");
        assertEquals(401, runtime.send(runtime.http, TunnelApiLauncher.get(uri, null, null)).statusCode());
        assertEquals(200, runtime.send(runtime.http, TunnelApiLauncher.get(uri, "local-agent", runtime.consolePassword)).statusCode());
    }

    @Test
    void oidcRedirectNamesTheExternalGroupPath() throws Exception {
        var uri = runtime.open(API + "browser/oauth2/authorization/google");
        var response = runtime.send(runtime.http, TunnelApiLauncher.get(uri, null, null));
        assertEquals(302, response.statusCode());
        var redirect = java.net.URLDecoder.decode(response.headers().firstValue("Location").orElseThrow(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(redirect.contains("/runtime/api/browser/oauth2/code/google"), redirect);
    }

    @AfterAll
    void stop() { runtime.stop(); }
}
