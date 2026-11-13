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

import static com.viscosiety.viscorunner.it.TunnelApiLauncher.SITE_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The servlet's own port check: like {@code TunnelApiIT} but without {@code servlet.tunnelApi.transportGuarantee},
 * so a request on the plain port reaches {@code TunnelApiServlet} itself and must be answered 404 with no body.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TunnelApiPortCheckIT {

    private final TunnelApiLauncher l = new TunnelApiLauncher("TunnelApiPortCheckIT", true);

    @BeforeAll
    void start() throws Exception {
        l.start(l.bothLogins(false));
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
    void plainPortAnswersGetWithSiteLoginWith404AndNoBody() throws Exception {
        HttpResponse<String> r = l.send(l.http, TunnelApiLauncher.get(l.open("/viscolink/tunnel-api/open"), SITE_USER, l.sitePassword));
        l.log("plain port GET /tunnel-api/open site-user: " + r.statusCode());
        assertEquals(404, r.statusCode());
        assertEquals("", r.body());
    }

    @Test
    void plainPortAnswersPostWithSiteLoginWith404AndNoBody() throws Exception {
        HttpResponse<String> r = l.send(l.http, TunnelApiLauncher.post(l.open("/viscolink/tunnel-api/feed"), SITE_USER, l.sitePassword));
        l.log("plain port POST /tunnel-api/feed site-user: " + r.statusCode());
        assertEquals(404, r.statusCode());
        assertEquals("", r.body());
    }

    @Test
    void tunnelPortStillAnswers() throws Exception {
        HttpResponse<String> r = l.send(l.https, TunnelApiLauncher.get(l.tunnel("/viscolink/tunnel-api/open"), SITE_USER, l.sitePassword));
        assertEquals(200, r.statusCode());
        assertEquals("tunnel-test-open-reply", r.body().trim());
    }
}
