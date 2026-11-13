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
import static com.viscosiety.viscorunner.it.TunnelApiLauncher.SITE_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The public side switched Off: {@code ApiListenerServlet} on a SEALED authenticator (HTTP Basic over an empty
 * user store) with {@code securityRoles}; the tunnel side keeps its own login.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TunnelApiPublicOffIT {

    private final TunnelApiLauncher l = new TunnelApiLauncher("TunnelApiPublicOffIT", true);

    @BeforeAll
    void start() throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "-Dapplication.security.http.authentication=true",
                "-Dapplication.security.http.transportGuarantee=NONE",
                "-Dservlet.tunnelApi.transportGuarantee=CONFIDENTIAL",
                "-Dapplication.security.http.authenticators=off,tunnelBasic",
                "-Dapplication.security.http.authenticators.off.type=SEALED",
                "-Dapplication.security.http.authenticators.tunnelBasic.type=IN_MEMORY",
                "-Dapplication.security.http.authenticators.tunnelBasic.username=" + SITE_USER,
                "-Dapplication.security.http.authenticators.tunnelBasic.password=" + l.sitePassword,
                "-Dservlet.ApiListenerServlet.authenticator=off",
                "-Dservlet.ApiListenerServlet.securityRoles=IbisTester",
                "-Dservlet.tunnelApi.authenticator=tunnelBasic",
                "-Dservlet.tunnelApi.securityRoles=IbisTester"));
        l.start(args);
        l.awaitReady(() -> l.send(l.https, TunnelApiLauncher.get(l.tunnel("/viscolink/tunnel-api/open"),
                SITE_USER, l.sitePassword)).statusCode(), "tunnel side");
    }

    @AfterAll
    void stop() {
        l.stop();
    }

    @Test
    void publicSideRefusesAnonymousCallers() throws Exception {
        HttpResponse<String> r = l.send(l.http, TunnelApiLauncher.get(l.open("/viscolink/api/open"), null, null));
        l.log("public anonymous: " + r.statusCode());
        assertEquals(401, r.statusCode());
        assertFalse(r.body().contains("tunnel-test-open-reply"));
    }

    @Test
    void publicSideRefusesTheSiteLogin() throws Exception {
        HttpResponse<String> r = l.send(l.http, TunnelApiLauncher.get(l.open("/viscolink/api/open"), SITE_USER, l.sitePassword));
        assertEquals(401, r.statusCode());
        assertFalse(r.body().contains("tunnel-test-open-reply"));
    }

    @Test
    void publicSideRefusesAMadeUpLogin() throws Exception {
        HttpResponse<String> r = l.send(l.http, TunnelApiLauncher.get(l.open("/viscolink/api/open"), "made-up", "made-up-" + l.publicPassword));
        assertEquals(401, r.statusCode());
        assertFalse(r.body().contains("tunnel-test-open-reply"));
    }

    @Test
    void tunnelSideStillAnswersGet() throws Exception {
        HttpResponse<String> r = l.send(l.https, TunnelApiLauncher.get(l.tunnel("/viscolink/tunnel-api/open"), SITE_USER, l.sitePassword));
        assertEquals(200, r.statusCode());
        assertEquals("tunnel-test-open-reply", r.body().trim());
    }

    @Test
    void tunnelSideStillAnswersPostToTheTunnelOnlyListener() throws Exception {
        HttpResponse<String> r = l.send(l.https, TunnelApiLauncher.post(l.tunnel("/viscolink/tunnel-api/feed"), SITE_USER, l.sitePassword));
        assertEquals(200, r.statusCode());
        assertEquals("tunnel-test-feed-reply", r.body().trim());
    }
}
