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
import static com.viscosiety.viscorunner.it.TunnelApiLauncher.PUBLIC_USER;
import static com.viscosiety.viscorunner.it.TunnelApiLauncher.SITE_USER;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;


/**
 * The tunnel side on a real Frank!Framework: {@code TunnelApiServlet} at {@code /tunnel-api/*} under its own
 * {@code servlet.tunnelApi.*} login, served on an HTTPS tunnel port only, next to the public
 * {@code ApiListenerServlet} at {@code /api/*} with a different login. The launcher has no rewrite rules, so the
 * tunnel side is called at {@code /viscolink/tunnel-api/...} directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TunnelApiIT {

    private final TunnelApiLauncher l = new TunnelApiLauncher("TunnelApiIT", true);

    @BeforeAll
    void start() throws Exception {
        l.start(l.bothLogins(true));
        l.awaitReady(() -> l.send(l.https, TunnelApiLauncher.get(l.tunnel("/viscolink/tunnel-api/open"),
                SITE_USER, l.sitePassword)).statusCode(), "tunnel side");
    }

    @AfterAll
    void stop() {
        l.stop();
    }

    @Test
    void tunnelOnlyListenerAnswersOnTheTunnelSide() throws Exception {
        HttpResponse<String> response = l.https.send(TunnelApiLauncher.post(tunnel("/viscolink/tunnel-api/feed"), SITE_USER, l.sitePassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("tunnel-test-feed-reply", response.body().trim());
    }

    @Test
    void plainApiListenerAnswersOnTheTunnelSideToo() throws Exception {
        HttpResponse<String> response = l.https.send(TunnelApiLauncher.get(tunnel("/viscolink/tunnel-api/open"), SITE_USER, l.sitePassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("tunnel-test-open-reply", response.body().trim());
    }

    @Test
    void tunnelSideRefusesAnonymousCallers() throws Exception {
        HttpResponse<String> response = l.https.send(TunnelApiLauncher.get(tunnel("/viscolink/tunnel-api/open"), null, null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void tunnelSideRefusesThePublicLogin() throws Exception {
        HttpResponse<String> response = l.https.send(TunnelApiLauncher.get(tunnel("/viscolink/tunnel-api/open"), PUBLIC_USER, l.publicPassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void publicSideAnswersThePublicLogin() throws Exception {
        HttpResponse<String> response = l.http.send(TunnelApiLauncher.get(open("/viscolink/api/open"), PUBLIC_USER, l.publicPassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("tunnel-test-open-reply", response.body().trim());
    }

    @Test
    void publicSideRefusesTheSiteLogin() throws Exception {
        HttpResponse<String> response = l.http.send(TunnelApiLauncher.get(open("/viscolink/api/open"), SITE_USER, l.sitePassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void tunnelServletIsNotReachableOnThePublicPort() throws Exception {
        HttpResponse<String> response = l.http.send(TunnelApiLauncher.get(open("/viscolink/tunnel-api/open"), SITE_USER, l.sitePassword),
                HttpResponse.BodyHandlers.ofString());
        log("public port, /tunnel-api/open: " + response.statusCode());
        assertTrue(response.statusCode() == 302 || response.statusCode() == 404, "status " + response.statusCode());
        assertFalse(response.body().contains("tunnel-test-open-reply"), "body must not carry the adapter's text");
    }

    @Test
    void unknownPathOnTheTunnelSideIsNotFound() throws Exception {
        HttpResponse<String> response = l.https.send(TunnelApiLauncher.get(tunnel("/viscolink/tunnel-api/nothing-here"), SITE_USER, l.sitePassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }

    @Test
    void mixedPatternAnswersBothMethodsOnTheTunnelSide() throws Exception {
        HttpResponse<String> posted = l.https.send(TunnelApiLauncher.post(tunnel("/viscolink/tunnel-api/mixed"), SITE_USER, l.sitePassword),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> got = l.https.send(TunnelApiLauncher.get(tunnel("/viscolink/tunnel-api/mixed"), SITE_USER, l.sitePassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, posted.statusCode());
        assertEquals("tunnel-test-mixed-post-reply", posted.body().trim());
        assertEquals(200, got.statusCode());
        assertEquals("tunnel-test-mixed-get-reply", got.body().trim());
    }

    @Test
    void tunnelOnlyListenerIsNotFoundOnThePublicSide() throws Exception {
        HttpResponse<String> response = l.http.send(TunnelApiLauncher.post(open("/viscolink/api/feed"), PUBLIC_USER, l.publicPassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertEquals("", response.body());
    }

    @Test
    void tunnelOnlyListenerAsksAnonymousCallersToLogInLikeAnyPath() throws Exception {
        HttpResponse<String> response = l.http.send(TunnelApiLauncher.post(open("/viscolink/api/feed"), null, null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void unknownPathAsksAnonymousCallersToLogIn() throws Exception {
        HttpResponse<String> response = l.http.send(TunnelApiLauncher.post(open("/viscolink/api/nothing-here"), null, null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void mixedPatternHidesOnlyItsTunnelMethodOnThePublicSide() throws Exception {
        HttpResponse<String> posted = l.http.send(TunnelApiLauncher.post(open("/viscolink/api/mixed"), PUBLIC_USER, l.publicPassword),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> got = l.http.send(TunnelApiLauncher.get(open("/viscolink/api/mixed"), PUBLIC_USER, l.publicPassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, posted.statusCode());
        assertEquals("", posted.body());
        assertEquals(200, got.statusCode());
        assertEquals("tunnel-test-mixed-get-reply", got.body().trim());
        String allow = got.headers().firstValue("Allow").orElse("");
        log("public GET /api/mixed Allow: " + allow);
        assertTrue(allow.contains("GET"), allow);
        assertFalse(allow.contains("POST"), allow);

        HttpResponse<String> withOrigin = l.http.send(TunnelApiLauncher.withLogin(HttpRequest.newBuilder(open("/viscolink/api/mixed"))
                .timeout(Duration.ofSeconds(30)).header("Origin", "https://elsewhere.example").GET(),
                PUBLIC_USER, l.publicPassword).build(), HttpResponse.BodyHandlers.ofString());
        String corsMethods = withOrigin.headers().firstValue("Access-Control-Allow-Methods").orElse("");
        log("public GET /api/mixed with Origin, Access-Control-Allow-Methods: " + corsMethods);
        assertEquals(200, withOrigin.statusCode());
        assertTrue(corsMethods.contains("GET"), corsMethods);
        assertFalse(corsMethods.contains("POST"), corsMethods);
    }

    @Test
    void publicPreflightOfAMixedPatternLeavesOutItsTunnelMethods() throws Exception {
        HttpResponse<String> response = l.http.send(TunnelApiLauncher.options(open("/viscolink/api/mixed"), PUBLIC_USER, l.publicPassword),
                HttpResponse.BodyHandlers.ofString());
        String methods = response.headers().firstValue("Access-Control-Allow-Methods").orElse("");
        log("public OPTIONS /api/mixed: " + response.statusCode() + ", Access-Control-Allow-Methods: " + methods);
        assertEquals(200, response.statusCode());
        assertTrue(methods.contains("GET"), methods);
        assertFalse(methods.contains("POST"), methods);
    }

    @Test
    void tunnelSideStillListsEveryMethodOfAMixedPattern() throws Exception {
        HttpResponse<String> preflight = l.https.send(TunnelApiLauncher.options(tunnel("/viscolink/tunnel-api/mixed"), SITE_USER, l.sitePassword),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> got = l.https.send(TunnelApiLauncher.get(tunnel("/viscolink/tunnel-api/mixed"), SITE_USER, l.sitePassword),
                HttpResponse.BodyHandlers.ofString());
        String methods = preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("");
        String allow = got.headers().firstValue("Allow").orElse("");
        log("tunnel OPTIONS /mixed Access-Control-Allow-Methods: " + methods + "; GET Allow: " + allow);
        assertTrue(methods.contains("POST"), methods);
        assertTrue(allow.contains("POST"), allow);
    }

    @Test
    void perPatternOpenApiOfATunnelPatternLooksLikeOneThatDoesNotExist() throws Exception {
        HttpResponse<String> tunnelPattern = l.http.send(TunnelApiLauncher.get(open("/viscolink/api/feed/openapi.json"), PUBLIC_USER, l.publicPassword),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> missing = l.http.send(TunnelApiLauncher.get(open("/viscolink/api/nothing-here/openapi.json"), PUBLIC_USER, l.publicPassword),
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
        HttpResponse<String> response = l.http.send(TunnelApiLauncher.get(open("/viscolink/api/openapi.json"), PUBLIC_USER, l.publicPassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"/open\""), body);
        assertFalse(body.contains("\"/feed\""), body);
        assertFalse(body.contains("\"/mixed\""), body);
    }

    @Test
    void tunnelOpenApiDocumentListsEveryPattern() throws Exception {
        HttpResponse<String> response = l.https.send(TunnelApiLauncher.get(tunnel("/viscolink/tunnel-api/openapi.json"), SITE_USER, l.sitePassword),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"/open\""), body);
        assertTrue(body.contains("\"/feed\""), body);
        assertTrue(body.contains("\"/mixed\""), body);
    }

    @Test
    void encodedAndTrailingSlashFormsOfATunnelPathAreNotFound() throws Exception {
        int encoded = rawPublicStatus("POST", "/viscolink/api/%66eed", PUBLIC_USER, l.publicPassword);
        int trailing = rawPublicStatus("POST", "/viscolink/api/feed/", PUBLIC_USER, l.publicPassword);
        log("public POST /api/%66eed: " + encoded + ", /api/feed/: " + trailing);
        assertEquals(404, encoded);
        assertEquals(404, trailing);
    }

    @Test
    void dotSegmentFormOfATunnelPathDoesNotReachIt() throws Exception {
        int status = rawPublicStatus("POST", "/viscolink/api/x/../feed", PUBLIC_USER, l.publicPassword);
        log("public POST /api/x/../feed: " + status);
        assertNotEquals(200, status);
    }

    private int rawPublicStatus(String method, String rawPath, String user, String password) throws Exception {
        return l.rawPublicStatus(method, rawPath, user, password);
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return l.send(client, request);
    }

    private URI tunnel(String path) {
        return l.tunnel(path);
    }

    private URI open(String path) {
        return l.open(path);
    }

    private void log(String message) {
        l.log(message);
    }
}
