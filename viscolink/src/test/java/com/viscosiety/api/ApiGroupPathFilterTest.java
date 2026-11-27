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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiGroupPathFilterTest {

    @BeforeEach
    void before() {
        ApiGroupsTestSupport.reset();
    }

    @AfterEach
    void after() {
        System.clearProperty(ApiGroups.PROPERTY);
        ApiGroupsTestSupport.reset();
    }

    @Test
    void aGroupPathOnTheDefaultServletIsNotFound() throws Exception {
        System.setProperty(ApiGroups.PROPERTY, "webhooks");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/github");
        request.setPathInfo("/webhooks/github");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        new ApiGroupPathFilter().doFilter(request, response, chain);
        assertEquals(404, response.getStatus());
        assertEquals("", response.getContentAsString());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void otherPathsPass() throws Exception {
        System.setProperty(ApiGroups.PROPERTY, "webhooks");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.setPathInfo("/orders");
        FilterChain chain = mock(FilterChain.class);
        new ApiGroupPathFilter().doFilter(request, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
    }
}
