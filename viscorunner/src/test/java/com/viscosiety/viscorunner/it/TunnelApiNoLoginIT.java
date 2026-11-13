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
 * The tunnel side without a login: {@code servlet.tunnelApi.authenticator=NONE}, no {@code securityRoles}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TunnelApiNoLoginIT {

    private final TunnelApiLauncher l = new TunnelApiLauncher("TunnelApiNoLoginIT", true);

    @BeforeAll
    void start() throws Exception {
        List<String> args = new ArrayList<>(l.bothLogins(true));
        args.removeIf(a -> a.startsWith("-Dservlet.tunnelApi.securityRoles")
                || a.startsWith("-Dservlet.tunnelApi.authenticator="));
        args.add("-Dservlet.tunnelApi.authenticator=NONE");
        l.start(args);
        l.awaitReady(() -> l.send(l.https, TunnelApiLauncher.get(l.tunnel("/viscolink/tunnel-api/open"),
                SITE_USER, l.sitePassword)).statusCode(), "tunnel side");
    }

    @AfterAll
    void stop() {
        l.stop();
    }

    @Test
    void publicSideFilterIsRegisteredInThisRuntime() throws Exception {
        l.assertPublicSideFilterRegistered();
    }

    @Test
    void tunnelSideAnswersGetWithoutLogin() throws Exception {
        HttpResponse<String> r = l.send(l.https, TunnelApiLauncher.get(l.tunnel("/viscolink/tunnel-api/open"), null, null));
        assertEquals(200, r.statusCode());
        assertEquals("tunnel-test-open-reply", r.body().trim());
    }

    @Test
    void tunnelSideAnswersPostWithoutLogin() throws Exception {
        HttpResponse<String> r = l.send(l.https, TunnelApiLauncher.post(l.tunnel("/viscolink/tunnel-api/feed"), null, null));
        assertEquals(200, r.statusCode());
        assertEquals("tunnel-test-feed-reply", r.body().trim());
    }

    @Test
    void publicPortDoesNotServeTheTunnelServletWithoutLogin() throws Exception {
        HttpResponse<String> r = l.send(l.http, TunnelApiLauncher.get(l.open("/viscolink/tunnel-api/open"), null, null));
        l.log("public port, /tunnel-api/open without login: " + r.statusCode());
        assertTrue(r.statusCode() != 200, "status " + r.statusCode());
        assertFalse(r.body().contains("tunnel-test-open-reply"));
    }

    @Test
    void tunnelOnlyListenerRefusesARequestThatDidNotArriveOnATunnelPort() throws Exception {
        HttpResponse<String> r = l.send(l.http, TunnelApiLauncher.post(l.open("/viscolink/api/feed"), PUBLIC_USER, l.publicPassword));
        l.log("public port, POST /api/feed with public login: " + r.statusCode());
        assertEquals(404, r.statusCode());
        assertFalse(r.body().contains("tunnel-test-feed-reply"));
    }
}
