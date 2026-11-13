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


package org.frankframework.visco.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The port check, which TunnelApiIT cannot reach on the public port (CONFIDENTIAL redirects there first). */
class TunnelApiServletTest {

    private static final String PORTS_PROPERTY = "visco.tunnel.api.ports";

    @AfterEach
    void clear() {
        System.clearProperty(PORTS_PROPERTY);
    }

    @Test
    void nameAndMappingAreTheTunnelOnes() {
        TunnelApiServlet servlet = new TunnelApiServlet();
        assertEquals("tunnelApi", servlet.getName());
        assertEquals("/tunnel-api/*", servlet.getUrlMapping());
    }

    @Test
    void requestOffATunnelPortIsNotFoundWithAnEmptyBody() throws Exception {
        System.setProperty(PORTS_PROPERTY, "8443");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/tunnel-api/open");
        request.setLocalPort(8080);
        request.setPathInfo("/open");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TunnelApiServlet().service(request, response);

        assertEquals(404, response.getStatus());
        assertEquals("", response.getContentAsString());
    }

    @Test
    void noTunnelPortsMeansEveryRequestIsNotFound() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/tunnel-api/feed");
        request.setLocalPort(8443);
        request.setPathInfo("/feed");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TunnelApiServlet().service(request, response);

        assertEquals(404, response.getStatus());
    }
}
