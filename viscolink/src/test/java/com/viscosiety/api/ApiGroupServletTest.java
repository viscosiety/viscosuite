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

package com.viscosiety.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.viscosiety.tunnel.TunnelApiListener;
import org.frankframework.core.Adapter;
import org.frankframework.core.PipeLine;
import org.frankframework.core.PipeLineSession;
import org.frankframework.http.HttpEntityType;
import org.frankframework.http.mime.HttpEntityFactory;
import org.frankframework.http.rest.ApiListener;
import org.frankframework.http.rest.ApiListener.HttpMethod;
import org.frankframework.http.rest.ApiServiceDispatcher;
import org.frankframework.stream.Message;
import org.frankframework.util.AppConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;

/** The group servlet against the real {@link ApiServiceDispatcher}, parent servlet included. */
class ApiGroupServletTest {

    private boolean stageSet;

    /**
     * The parent's ETag cache key reads {@code dtap.stage} once, when {@code ApiCacheManager} is loaded; another test
     * in this JVM may have removed it, so it is set here when absent (and removed again afterwards).
     */
    @BeforeEach
    void before() {
        clear();
        if (AppConstants.getInstance().getProperty("dtap.stage") == null) {
            AppConstants.getInstance().setProperty("dtap.stage", "DEV");
            stageSet = true;
        }
    }

    @AfterEach
    void after() {
        clear();
        if (stageSet) {
            AppConstants.getInstance().remove("dtap.stage");
        }
    }

    private static void clear() {
        ApiServiceDispatcher.getInstance().clear();
        System.clearProperty(ApiGroups.PROPERTY);
        ApiGroupsTestSupport.reset();
        RAN.clear();
    }

    /** Names of the listeners the servlet ran, so a test sees which pattern was served. */
    private static final List<String> RAN = new ArrayList<>();

    /** A listener that records that it ran and answers an empty body. */
    static class Answering extends ApiListener {
        @Override
        public Message processRequest(Message rawMessage, PipeLineSession session) {
            RAN.add(getName());
            return Message.nullMessage();
        }

        /** What {@code configure()} would build; the test registers the listener without configuring it. */
        @Override
        public HttpEntityFactory getResponseEntityBuilder() {
            return HttpEntityFactory.Builder.create().entityType(HttpEntityType.BINARY).build();
        }
    }

    private static void register(ApiListener listener, String name, String pattern, HttpMethod method) {
        Adapter adapter = mock(Adapter.class);
        when(adapter.getPipeLine()).thenReturn(mock(PipeLine.class));
        listener.setAdapter(adapter);
        listener.setName(name);
        listener.setUriPattern(pattern);
        listener.setMethod(method);
        ApiServiceDispatcher.getInstance().registerServiceClient(listener);
    }

    private static ApiGroupServlet servlet() throws Exception {
        ApiGroupServlet servlet = new ApiGroupServlet("webhooks");
        servlet.init(new MockServletConfig());
        return servlet;
    }

    /** A request as it arrives after the rewrite: /runtime/api-group/webhooks/<rest>. */
    private static MockHttpServletResponse call(String method, String rest) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/runtime/api-group/webhooks" + rest);
        request.setContextPath("/runtime");
        request.setServletPath("/api-group/webhooks");
        request.setPathInfo(rest);
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet().service(request, response);
        return response;
    }

    @Test
    void nameAndMappingFollowTheGroup() {
        ApiGroupServlet servlet = new ApiGroupServlet("webhooks");
        assertEquals("api-webhooks", servlet.getName());
        assertEquals("/api-group/webhooks/*", servlet.getUrlMapping());
    }

    @Test
    void theDispatcherSeesTheFullPath() {
        assertEquals("/webhooks/github", ApiGroupServlet.pathFor("webhooks", "/github"));
        assertEquals("/webhooks", ApiGroupServlet.pathFor("webhooks", null));
        assertEquals("/webhooks", ApiGroupServlet.pathFor("webhooks", "/"));
    }

    @Test
    void openApiIsTheGroupsOwnDocument() throws Exception {
        MockHttpServletResponse response = call("GET", "/openapi.json");
        assertEquals(200, response.getStatus());
        assertEquals("application/json", response.getHeader("Content-Type"));
        assertEquals(200, call("GET", "/openapi.json/").getStatus(), "one trailing slash, as the parent strips it");
    }

    @Test
    void aGroupListenerIsServed() throws Exception {
        register(new Answering(), "group-orders", "webhooks/orders", HttpMethod.GET);
        MockHttpServletResponse response = call("GET", "/orders");
        assertEquals(200, response.getStatus(), response.getErrorMessage());
        assertEquals(List.of("group-orders"), RAN);
    }

    @Test
    void aDefaultListenerWithAWildcardFirstSegmentIsNotServed() throws Exception {
        register(new Answering(), "default-orders", "{tenant}/orders", HttpMethod.GET);
        MockHttpServletResponse response = call("GET", "/orders");
        assertEquals(404, response.getStatus());
        assertEquals("", response.getContentAsString());
        assertEquals(404, call("OPTIONS", "/orders").getStatus());
        assertEquals(List.of(), RAN);
    }

    @Test
    void aDefaultCatchAllIsNotServed() throws Exception {
        register(new Answering(), "default-all", "**", HttpMethod.GET);
        MockHttpServletResponse response = call("GET", "/anything/at/all");
        assertEquals(404, response.getStatus());
        assertEquals("", response.getContentAsString());
        assertEquals(404, call("GET", "").getStatus(), "the group root");
        assertEquals(List.of(), RAN);
    }

    @Test
    void aBetterScoringDefaultMethodIsNotServed() throws Exception {
        // POST scores the default pattern +10 for the method; it must still not run under the group's login.
        register(new Answering(), "group-orders", "webhooks/orders", HttpMethod.GET);
        register(new Answering(), "default-orders", "{x}/orders", HttpMethod.POST);
        assertEquals(404, call("POST", "/orders").getStatus());
        assertEquals(List.of(), RAN);
        assertEquals(200, call("GET", "/orders").getStatus());
        assertEquals(List.of("group-orders"), RAN);
    }

    @Test
    void aMissingMethodOnTheGroupsPatternKeepsTheParents405() throws Exception {
        register(new Answering(), "group-orders", "webhooks/orders", HttpMethod.GET);
        assertEquals(405, call("PUT", "/orders").getStatus());
        assertEquals(404, call("GET", "/nothing").getStatus());
    }

    @Test
    void thePerPatternOpenApiOfAnotherPatternIsNotFound() throws Exception {
        register(new Answering(), "default-orders", "{tenant}/orders", HttpMethod.GET);
        MockHttpServletResponse response = call("GET", "/orders/openapi.json");
        assertEquals(404, response.getStatus());
        assertEquals("OpenApi specification not found", response.getErrorMessage());
    }

    @Test
    void thePerPatternOpenApiOfAGroupPatternUsesThePublicServerUrl() throws Exception {
        register(new Answering(), "group-orders", "webhooks/orders", HttpMethod.GET);
        MockHttpServletResponse response = call("GET", "/orders/openapi.json");
        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("/webhooks/orders"), body);
        assertTrue(body.contains("/runtime/api\""), body);
        assertFalse(body.contains("api-group"), body);
    }

    @Test
    void aTunnelOnlyGroupEndpointIsNotFoundOnTheGroupServlet() throws Exception {
        register(new TunnelApiListener(), "group-feed", "webhooks/feed", HttpMethod.POST);
        MockHttpServletResponse response = call("POST", "/feed");
        assertEquals(404, response.getStatus());
        assertEquals("", response.getContentAsString());
        assertEquals(List.of(), RAN);
        assertEquals(404, call("GET", "/feed").getStatus(), "tunnel-only pattern: no 405 hint");
        assertEquals(404, call("OPTIONS", "/feed").getStatus());
        MockHttpServletResponse openApi = call("GET", "/feed/openapi.json");
        assertEquals(404, openApi.getStatus());
        assertEquals("OpenApi specification not found", openApi.getErrorMessage());
    }

    @Test
    void tunnelOnlyMethodsAreLeftOutOfTheHeaders() throws Exception {
        register(new Answering(), "group-mixed-get", "webhooks/mixed", HttpMethod.GET);
        register(new TunnelApiListener(), "group-mixed-post", "webhooks/mixed", HttpMethod.POST);
        assertEquals(404, call("POST", "/mixed").getStatus());
        MockHttpServletResponse options = call("OPTIONS", "/mixed");
        assertEquals(200, options.getStatus());
        String allowed = options.getHeader("Access-Control-Allow-Methods");
        assertTrue(allowed.contains("GET"), allowed);
        assertFalse(allowed.contains("POST"), allowed);
        MockHttpServletResponse get = call("GET", "/mixed");
        assertEquals(200, get.getStatus(), get.getErrorMessage());
        assertEquals(List.of("group-mixed-get"), RAN);
        assertFalse(get.getHeader("Allow").contains("POST"), get.getHeader("Allow"));
        assertEquals(404, call("GET", "/mixed/openapi.json").getStatus(), "the pattern has a tunnel listener");
    }

    @Test
    void checkPassesWhatTheParentAnswersItself() {
        assertEquals(ApiGroupServlet.Action.PASS, ApiGroupServlet.check("webhooks", "BREW", "/webhooks/x").action());
        assertEquals(ApiGroupServlet.Action.PASS, ApiGroupServlet.check("webhooks", "GET", "/webhooks/x").action());
        register(new Answering(), "group-mixed-get", "webhooks/mixed", HttpMethod.GET);
        register(new TunnelApiListener(), "group-mixed-post", "webhooks/mixed", HttpMethod.POST);
        assertEquals(Set.of("POST"), ApiGroupServlet.check("webhooks", "GET", "/webhooks/mixed").hiddenMethods());
    }
}
