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

import java.util.List;

import org.frankframework.core.Adapter;
import org.frankframework.core.PipeLine;
import org.frankframework.http.rest.ApiDispatchConfig;
import org.frankframework.http.rest.ApiListener;
import org.frankframework.http.rest.ApiListener.HttpMethod;
import org.frankframework.http.rest.ApiServiceDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiGroupOpenApiTest {

    @BeforeEach
    void before() {
        ApiGroupsTestSupport.reset();
        ApiServiceDispatcher.getInstance().clear();
    }

    @AfterEach
    void after() {
        System.clearProperty(ApiGroups.PROPERTY);
        ApiGroupsTestSupport.reset();
        ApiServiceDispatcher.getInstance().clear();
    }

    private static void register(String pattern) {
        ApiListener listener = new ApiListener();
        Adapter adapter = mock(Adapter.class);
        when(adapter.getPipeLine()).thenReturn(mock(PipeLine.class));
        listener.setAdapter(adapter);
        listener.setUriPattern(pattern);
        listener.setMethod(HttpMethod.POST);
        ApiServiceDispatcher.getInstance().registerServiceClient(listener);
    }

    private static List<ApiDispatchConfig> all() {
        return List.copyOf(ApiServiceDispatcher.getInstance().getPatternClients().values());
    }

    @Test
    void configsOfKeepsTheGroupsPatterns() {
        register("/webhooks/github");
        register("/webhooks");
        register("/orders");
        List<ApiDispatchConfig> mine = ApiGroupOpenApi.configsOf("webhooks", all());
        assertEquals(2, mine.size());
        for (ApiDispatchConfig c : mine) {
            assertTrue(c.getUriPattern().startsWith("/webhooks"));
        }
    }

    @Test
    void inAnyGroupFollowsTheProperty() {
        register("/webhooks/github");
        register("/orders");
        System.setProperty(ApiGroups.PROPERTY, "webhooks");
        for (ApiDispatchConfig c : all()) {
            assertEquals(c.getUriPattern().startsWith("/webhooks"), ApiGroupOpenApi.inAnyGroup(c));
        }
        System.clearProperty(ApiGroups.PROPERTY);
        ApiGroupsTestSupport.reset();
        for (ApiDispatchConfig c : all()) {
            assertFalse(ApiGroupOpenApi.inAnyGroup(c));
        }
    }

    @Test
    void theGroupsDocumentLeavesOutTunnelOnlyEndpoints() throws Exception {
        register("/webhooks/github");
        ApiListener tunnel = new com.viscosiety.tunnel.TunnelApiListener();
        Adapter adapter = mock(Adapter.class);
        when(adapter.getPipeLine()).thenReturn(mock(PipeLine.class));
        tunnel.setAdapter(adapter);
        tunnel.setUriPattern("/webhooks/feed");
        tunnel.setMethod(HttpMethod.POST);
        ApiServiceDispatcher.getInstance().registerServiceClient(tunnel);
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/runtime/api-group/webhooks/openapi.json");
        request.setContextPath("/runtime");
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
        ApiGroupOpenApi.write(response, "webhooks", request);
        String body = response.getContentAsString();
        assertTrue(body.contains("/webhooks/github"), body);
        assertFalse(body.contains("/webhooks/feed"), body);
    }
}
