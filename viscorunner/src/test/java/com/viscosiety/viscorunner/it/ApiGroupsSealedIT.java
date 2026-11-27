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
 * Endpoint group on a SEALED login: neither anonymous callers nor the default Basic user get in (a group's login is its own).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiGroupsSealedIT {

    private static final Path REWRITE_RULES = Paths.get("conf/Catalina/localhost/rewrite.config").toAbsolutePath();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TunnelApiLauncher l = new TunnelApiLauncher("ApiGroupsSealedIT", false,
            "src/test/resources/api-groups", "apigroups");

    @BeforeAll
    void start() throws Exception {
        l.start(List.of(
                "-Dviscolink.context.path=/runtime",
                "-Dviscolink.rewrite.rules=" + REWRITE_RULES,
                "-Dvisco.api.groups=webhooks",
                "-Dapplication.security.http.authentication=true",
                "-Dapplication.security.http.transportGuarantee=NONE",
                "-Dapplication.security.http.authenticators=publicBasic,off",
                "-Dapplication.security.http.authenticators.publicBasic.type=IN_MEMORY",
                "-Dapplication.security.http.authenticators.publicBasic.username=" + PUBLIC_USER,
                "-Dapplication.security.http.authenticators.publicBasic.password=" + l.publicPassword,
                "-Dapplication.security.http.authenticators.off.type=SEALED",
                "-Dservlet.ApiListenerServlet.authenticator=publicBasic",
                "-Dservlet.ApiListenerServlet.securityRoles=IbisTester",
                "-Dservlet.api-webhooks.authenticator=off",
                "-Dservlet.api-webhooks.securityRoles=IbisTester"));
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
    void groupLoginIsItsOwn() throws Exception {
        assertEquals(401, get("/runtime/api/webhooks/ping", false).statusCode());
        HttpResponse<String> r = get("/runtime/api/webhooks/ping", true);
        assertEquals(401, r.statusCode());
        assertFalse(r.body().contains("webhooks-ping"));
    }

    @Test
    void defaultEndpointStillAnswersItsLogin() throws Exception {
        assertEquals(200, get("/runtime/api/ping", true).statusCode());
    }
}
