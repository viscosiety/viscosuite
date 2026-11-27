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

import static com.viscosiety.viscorunner.it.TunnelApiLauncher.PUBLIC_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Endpoint groups in a real runtime: group "webhooks" with no login next to a Basic default login, through the image's own rewrite rules.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiGroupsIT {

    private static final Path REWRITE_RULES = Paths.get("conf/Catalina/localhost/rewrite.config").toAbsolutePath();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TunnelApiLauncher l = new TunnelApiLauncher("ApiGroupsIT", false,
            "src/test/resources/api-groups", "apigroups");

    @BeforeAll
    void start() throws Exception {
        l.start(List.of(
                "-Dviscolink.context.path=/runtime",
                "-Dviscolink.rewrite.rules=" + REWRITE_RULES,
                "-Dvisco.api.groups=webhooks",
                "-Dapplication.security.http.authentication=true",
                "-Dapplication.security.http.transportGuarantee=NONE",
                "-Dapplication.security.http.authenticators=publicBasic",
                "-Dapplication.security.http.authenticators.publicBasic.type=IN_MEMORY",
                "-Dapplication.security.http.authenticators.publicBasic.username=" + PUBLIC_USER,
                "-Dapplication.security.http.authenticators.publicBasic.password=" + l.publicPassword,
                "-Dservlet.ApiListenerServlet.authenticator=publicBasic",
                "-Dservlet.ApiListenerServlet.securityRoles=IbisTester",
                "-Dservlet.api-webhooks.authenticator=NONE"));
        l.awaitReady(() -> l.send(l.http, TunnelApiLauncher.get(l.open("/runtime/api/ping"),
                PUBLIC_USER, l.publicPassword)).statusCode(), "default endpoint");
    }

    @AfterAll
    void stop() {
        l.stop();
    }

    private HttpResponse<String> get(String path, boolean login) throws Exception {
        return l.send(l.http, TunnelApiLauncher.get(l.open(path), login ? PUBLIC_USER : null, login ? l.publicPassword : null));
    }

    private static List<String> paths(HttpResponse<String> r) throws Exception {
        List<String> names = new java.util.ArrayList<>();
        JSON.readTree(r.body()).path("paths").fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void groupServletIsRegistered() throws Exception {
        l.assertLogContains("api-groups: group [webhooks] serves /api/webhooks/ as servlet [api-webhooks]");
    }

    @Test
    void groupEndpointAnswersAnonymousOnBothPrefixes() throws Exception {
        for (String prefix : new String[] {"/viscolink", "/runtime"}) {
            HttpResponse<String> r = get(prefix + "/api/webhooks/ping", false);
            assertEquals(200, r.statusCode(), prefix);
            assertEquals("webhooks-ping", r.body().trim());
        }
    }

    @Test
    void groupPostAnswersAnonymous() throws Exception {
        HttpResponse<String> r = l.send(l.http, TunnelApiLauncher.post(l.open("/runtime/api/webhooks/feed"), null, null));
        assertEquals(200, r.statusCode());
        assertEquals("webhooks-feed", r.body().trim());
    }

    @Test
    void defaultEndpointKeepsTheBasicLogin() throws Exception {
        assertEquals(401, get("/runtime/api/ping", false).statusCode());
        HttpResponse<String> r = get("/runtime/api/ping", true);
        assertEquals(200, r.statusCode());
        assertEquals("default-ping", r.body().trim());
    }

    @Test
    void defaultOpenApiLeavesTheGroupOut() throws Exception {
        assertEquals(401, get("/runtime/api/openapi.json", false).statusCode());
        HttpResponse<String> r = get("/runtime/api/openapi.json", true);
        assertEquals(200, r.statusCode());
        List<String> paths = paths(r);
        assertTrue(paths.contains("/ping"), paths.toString());
        assertFalse(paths.contains("/webhooks/ping"), paths.toString());
    }

    @Test
    void groupOpenApiListsOnlyItsEndpoints() throws Exception {
        HttpResponse<String> r = get("/runtime/api/webhooks/openapi.json", false);
        assertEquals(200, r.statusCode());
        List<String> paths = paths(r);
        assertTrue(paths.contains("/webhooks/ping"), paths.toString());
        assertTrue(paths.contains("/webhooks/feed"), paths.toString());
        assertFalse(paths.contains("/ping"), paths.toString());
        String server = JSON.readTree(r.body()).path("servers").path(0).path("url").asText();
        assertTrue(server.endsWith("/runtime/api"), server);
    }
}
