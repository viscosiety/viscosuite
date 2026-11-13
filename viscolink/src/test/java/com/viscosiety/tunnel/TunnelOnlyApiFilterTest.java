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

package com.viscosiety.tunnel;

import static com.viscosiety.tunnel.TunnelOnlyApiFilter.Decision.NOT_FOUND;
import static com.viscosiety.tunnel.TunnelOnlyApiFilter.Decision.PASS;
import static com.viscosiety.tunnel.TunnelOnlyApiFilter.Decision.PUBLIC_OPENAPI;
import static com.viscosiety.tunnel.TunnelOnlyApiFilter.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.JsonObject;

import org.frankframework.http.rest.ApiListener;
import org.frankframework.http.rest.ApiListener.HttpMethod;
import org.frankframework.http.rest.ApiServiceDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The public-side decision against the real {@link ApiServiceDispatcher}. Listeners are registered directly
 * ({@code setUriPattern} + {@code setMethod}, then {@code registerServiceClient}); no {@code configure()} or receiver
 * is needed for the dispatcher's pattern table.
 */
class TunnelOnlyApiFilterTest {

    @BeforeEach
    @AfterEach
    void clear() {
        ApiServiceDispatcher.getInstance().clear();
    }

    private static void register(ApiListener listener, String pattern, HttpMethod method) {
        listener.setUriPattern(pattern);
        listener.setMethod(method);
        ApiServiceDispatcher.getInstance().registerServiceClient(listener);
    }

    /** open: GET public; feed: POST tunnel-only; mixed: GET public + POST tunnel-only. */
    private static void registerAll() {
        register(new ApiListener(), "open", HttpMethod.GET);
        register(new TunnelApiListener(), "feed", HttpMethod.POST);
        register(new ApiListener(), "mixed", HttpMethod.GET);
        register(new TunnelApiListener(), "mixed", HttpMethod.POST);
    }

    @Test
    void requestsAreDecidedByTheirListener() {
        registerAll();
        assertEquals(PASS, decide("GET", "/open", null));
        assertEquals(NOT_FOUND, decide("POST", "/feed", null));
        assertEquals(NOT_FOUND, decide("GET", "/feed", null), "the pattern is tunnel-only: no 405 hint");
        assertEquals(NOT_FOUND, decide("OPTIONS", "/feed", null));
        assertEquals(NOT_FOUND, decide("POST", "/feed/", null));
        assertEquals(NOT_FOUND, decide("post", "/feed", null), "method parsing is case-insensitive, as in the servlet");
        assertEquals(PASS, decide("GET", "/mixed", null));
        assertEquals(NOT_FOUND, decide("POST", "/mixed", null));
        assertEquals(PASS, decide("PUT", "/mixed", null));
        assertEquals(PASS, decide("GET", "/nothing", null));
        assertEquals(PASS, decide("BREW", "/feed", null));
        assertEquals(PASS, decide("GET", null, null));
        assertEquals(PASS, decide("GET", "", null));
        assertEquals(PASS, decide("GET", "/", null));
    }

    @Test
    void theOpenApiDocumentIsAnsweredHereOnlyWhenThereIsATunnelListener() {
        register(new ApiListener(), "open", HttpMethod.GET);
        assertEquals(PASS, decide("GET", "/openapi.json", null));
        assertEquals(PASS, decide("GET", "/OpenApi.JSON", null));

        register(new TunnelApiListener(), "feed", HttpMethod.POST);
        assertEquals(PUBLIC_OPENAPI, decide("GET", "/openapi.json", null));
        assertEquals(PUBLIC_OPENAPI, decide("GET", "/OpenApi.JSON", null));
        assertEquals(PUBLIC_OPENAPI, decide("GET", "/openapi.json/", null));
    }

    @Test
    void thePerPatternOpenApiFormsAreNotFoundForATunnelPattern() {
        registerAll();
        assertEquals(NOT_FOUND, decide("GET", "/openapi.json", "/feed"));
        assertEquals(NOT_FOUND, decide("GET", "/openapi.json", "/mixed"));
        assertEquals(PASS, decide("GET", "/openapi.json", "/open"));
        assertEquals(NOT_FOUND, decide("GET", "/feed/openapi.json", null));
        assertEquals(NOT_FOUND, decide("GET", "/mixed/openapi.json", null));
        assertEquals(PASS, decide("GET", "/open/openapi.json", null));
    }

    @Test
    void thePublicDocumentLeavesOutEveryPatternWithATunnelListener() {
        registerAll();
        JsonObject paths = TunnelOnlyApiFilter.publicOpenApi("http://localhost/api").getJsonObject("paths");
        assertTrue(paths.containsKey("/open"), paths.toString());
        assertFalse(paths.containsKey("/feed"), paths.toString());
        assertFalse(paths.containsKey("/mixed"), paths.toString());
    }

    @Test
    void theFilterAnswers404WithoutBodyAndDoesNotCallTheChain() throws Exception {
        registerAll();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/viscolink/api/feed");
        request.setContextPath("/viscolink");
        request.setServletPath("/api");
        request.setPathInfo("/feed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new TunnelOnlyApiFilter().doFilter(request, response, chain);

        assertEquals(404, response.getStatus());
        assertEquals("", response.getContentAsString());
        assertEquals(null, chain.getRequest(), "the chain must not run");
    }

    @Test
    void theFilterServesThePublicDocument() throws Exception {
        registerAll();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/viscolink/api/openapi.json");
        request.setContextPath("/viscolink");
        request.setServletPath("/api");
        request.setPathInfo("/openapi.json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new TunnelOnlyApiFilter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("application/json", response.getHeader("Content-Type"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"/open\""), body);
        assertFalse(body.contains("/feed"), body);
        assertFalse(body.contains("/mixed"), body);
        assertTrue(body.contains("http://localhost/viscolink/api"), "server URL as the servlet computes it: " + body);
        assertEquals(null, chain.getRequest());
    }

    @Test
    void thePublicListenerPassesThroughTheChain() throws Exception {
        registerAll();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/viscolink/api/open");
        request.setPathInfo("/open");
        MockFilterChain chain = new MockFilterChain();

        new TunnelOnlyApiFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(request, chain.getRequest());
    }
}
