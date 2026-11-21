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

import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * {@code allowAllParams} defaults to false on a real runtime: an ApiListener that sets neither
 * {@code allowedParameters} nor {@code allowAllParams} copies no request parameter into the session,
 * while the XML attributes still win (the digester sets them after the default) and path parameters
 * still arrive.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiListenerParamsDefaultIT {

    private final TunnelApiLauncher l = new TunnelApiLauncher("ApiListenerParamsDefaultIT", false,
            "src/test/resources/api-params", "apiparams");

    @BeforeAll
    void start() throws Exception {
        l.start(List.of("-Dapplication.security.http.authentication=false"));
        l.awaitReady(() -> l.send(l.http, TunnelApiLauncher.get(l.open("/viscolink/api/listed"), null, null)).statusCode(),
                "api");
    }

    @AfterAll
    void stop() {
        l.stop();
    }

    private String answer(String path) throws Exception {
        HttpResponse<String> r = l.send(l.http, TunnelApiLauncher.get(l.open("/viscolink/api/" + path), null, null));
        l.log(path + ": " + r.statusCode() + " " + r.body().trim());
        assertEquals(200, r.statusCode(), "status of " + path);
        return r.body().trim();
    }

    @Test
    void aListenerThatSaysNothingGetsNoParameters() throws Exception {
        assertEquals("none", answer("bare?x=1"));
    }

    @Test
    void theFrameworksSecurityRiskWarningIsGone() throws Exception {
        l.assertLogLacks("ApiListener [bare] SECURITY RISK");
    }

    @Test
    void aListedParameterStillArrives() throws Exception {
        assertEquals("1", answer("listed?x=1"));
    }

    @Test
    void allowAllParamsTrueStillCopiesEverything() throws Exception {
        assertEquals("1", answer("allow-all?x=1"));
    }

    @Test
    void pathParametersStillArrive() throws Exception {
        assertEquals("42/none", answer("path/42?x=1"));
    }
}
