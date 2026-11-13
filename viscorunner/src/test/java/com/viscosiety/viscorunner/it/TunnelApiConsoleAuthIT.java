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

import static com.viscosiety.viscorunner.it.TunnelApiLauncher.CONSOLE_USER;
import static com.viscosiety.viscorunner.it.TunnelApiLauncher.PUBLIC_USER;
import static com.viscosiety.viscorunner.it.TunnelApiLauncher.SITE_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The console login on ({@code application.security.console.authentication.type=IN_MEMORY}, its own user) next to
 * the tunnel login: the ViscoLink tool-page filter must leave {@code /tunnel-api/} to the tunnel servlet's own
 * chain, so the site login works on the tunnel side and the console login does not.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TunnelApiConsoleAuthIT {

    private final TunnelApiLauncher l = new TunnelApiLauncher("TunnelApiConsoleAuthIT", true);

    @BeforeAll
    void start() throws Exception {
        l.start(l.withConsoleLogin(l.bothLogins(true)));
        // Ready = the public API answers; the tunnel side is what the tests look at.
        l.awaitReady(() -> l.send(l.http, TunnelApiLauncher.get(l.open("/viscolink/api/open"),
                PUBLIC_USER, l.publicPassword)).statusCode(), "public side");
    }

    @AfterAll
    void stop() {
        l.stop();
    }

    @Test
    void consoleToolPageFilterIsRegisteredInThisRuntime() throws Exception {
        l.assertLogContains("tool pages secured with F!F console authentication");
    }

    @Test
    void tunnelLoginAnswersOnTheTunnelSide() throws Exception {
        HttpResponse<String> r = l.send(l.https, TunnelApiLauncher.get(l.tunnel("/viscolink/tunnel-api/open"),
                SITE_USER, l.sitePassword));
        l.log("tunnel port, site login: " + r.statusCode());
        assertEquals(200, r.statusCode());
        assertEquals("tunnel-test-open-reply", r.body().trim());
    }

    @Test
    void consoleLoginDoesNotWorkOnTheTunnelSide() throws Exception {
        HttpResponse<String> r = l.send(l.https, TunnelApiLauncher.get(l.tunnel("/viscolink/tunnel-api/open"),
                CONSOLE_USER, l.consolePassword));
        l.log("tunnel port, console login: " + r.statusCode());
        assertEquals(401, r.statusCode());
        assertFalse(r.body().contains("tunnel-test-open-reply"));
    }
}
