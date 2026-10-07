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

package com.viscosiety.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.viscosiety.pack.CorePack;
import com.viscosiety.pack.DistinctSubjectPack;
import com.viscosiety.pack.HealthValuesPack;
import com.viscosiety.pack.PackJson;
import com.viscosiety.pack.PackRegistryTestSupport;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ViscoFlow takes the subject identifier's key from the pack descriptor and serves the descriptor itself. */
class FlowControllerPackTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The Ladybug metadata URLs the controller asked for; the fetch is stubbed out, so no HTTP happens. */
    private final List<String> ladybugUrls = new ArrayList<>();
    private final StringWriter responseBody = new StringWriter();

    private FlowController controller;
    private ServletContext servletContext;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() throws Exception {
        controller = new FlowController() {
            @Override
            List<Map<String, Object>> fetchJsonList(String urlStr, String authHeader) {
                ladybugUrls.add(urlStr);
                return List.of();
            }
        };
        servletContext = mock(ServletContext.class);
        // A request that is not the combined query is forwarded; the dispatcher is a no-op here.
        when(servletContext.getRequestDispatcher(anyString())).thenReturn(mock(RequestDispatcher.class));
        ServletConfig config = mock(ServletConfig.class);
        when(config.getServletContext()).thenReturn(servletContext);
        controller.init(config);

        response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    @AfterEach
    void clearOverride() {
        PackRegistryTestSupport.reset();
    }

    @Test
    void packEndpointServesTheDescriptorOfTheResolvedPack() throws Exception {
        PackRegistryTestSupport.override(new HealthValuesPack());

        controller.doGet(request("/pack", ""), response);

        verify(response).setContentType("application/json");
        verify(response).setCharacterEncoding("UTF-8");
        // Same rule as /api-service/pack: an authenticated answer must not outlive the caller's session in a cache.
        verify(response).setHeader("Cache-Control", "no-store");
        JsonNode body = JSON.readTree(responseBody.toString());
        assertEquals("health", body.path("id").asText());
        assertEquals("patientId", body.path("subject").path("metadataName").asText());
        assertEquals("Patient", body.path("subject").path("label").asText());
        assertEquals(PackJson.of(new HealthValuesPack()), responseBody.toString(), "the same JSON the descriptor endpoints serve");
        verify(response, never()).sendError(anyInt(), anyString());
        verify(servletContext, never()).getRequestDispatcher(anyString());
    }

    @Test
    void packEndpointFollowsThePackNotAnyConstant() throws Exception {
        PackRegistryTestSupport.override(new CorePack());

        controller.doGet(request("/pack", ""), response);

        JsonNode body = JSON.readTree(responseBody.toString());
        assertEquals("core", body.path("id").asText());
        assertEquals("Subject", body.path("subject").path("label").asText());
    }

    @Test
    void packEndpointServesTheMetadataNameUnderItsOwnKey() throws Exception {
        PackRegistryTestSupport.override(new DistinctSubjectPack());

        controller.doGet(request("/pack", ""), response);

        JsonNode subject = JSON.readTree(responseBody.toString()).path("subject");
        assertEquals("sk", subject.path("sessionKey").asText());
        assertEquals("mn", subject.path("metadataName").asText());
        assertEquals("DL", subject.path("label").asText());
    }

    @Test
    void subjectFilterUsesTheHealthPacksMetadataNameAsTheLadybugFilterHeader() throws Exception {
        PackRegistryTestSupport.override(new HealthValuesPack());

        controller.doGet(request("/traces", "subjectFilter=x&flowFilter=f"), response);

        assertEquals(1, ladybugUrls.size(), "the combined subject+flow query must run, not the plain forward");
        assertTrue(ladybugUrls.get(0).contains("?filterHeader=patientId&filter=x&"), ladybugUrls.get(0));
    }

    @Test
    void patientFilterIsStillReadAsAnAliasForOneRelease() throws Exception {
        PackRegistryTestSupport.override(new HealthValuesPack());

        controller.doGet(request("/traces", "patientFilter=x&flowFilter=f"), response);

        assertEquals(1, ladybugUrls.size());
        assertTrue(ladybugUrls.get(0).contains("?filterHeader=patientId&filter=x&"), ladybugUrls.get(0));
    }

    @Test
    void subjectFilterWinsWhenBothParametersAreGiven() throws Exception {
        PackRegistryTestSupport.override(new HealthValuesPack());

        controller.doGet(request("/traces", "patientFilter=old&subjectFilter=new&flowFilter=f"), response);

        assertEquals(1, ladybugUrls.size());
        assertTrue(ladybugUrls.get(0).contains("&filter=new&"), ladybugUrls.get(0));
    }

    @Test
    void theFilterHeaderIsTheMetadataNameNotTheSessionKey() throws Exception {
        // The shipped packs use one value for both, so only a pack that tells them apart can show which one
        // Ladybug is asked to filter on (Ladybug filters on a metadata column, not on a session key).
        PackRegistryTestSupport.override(new DistinctSubjectPack());

        controller.doGet(request("/traces", "subjectFilter=x&flowFilter=f"), response);

        assertEquals(1, ladybugUrls.size());
        assertTrue(ladybugUrls.get(0).contains("?filterHeader=mn&filter=x&"), ladybugUrls.get(0));
    }

    @Test
    void theCombinedQueryPassesTheMetadataNamesThroughUnchanged() throws Exception {
        // The page sends the view's column list; the controller must not add, drop or reorder names, and
        // must not substitute the pack's subject for the "patientId" entry (D8: the health query is as before).
        PackRegistryTestSupport.override(new HealthValuesPack());

        controller.doGet(request("/traces", "subjectFilter=x&flowFilter=f"
                + "&metadataNames=storageId&metadataNames=patientId&metadataNames=flow"), response);

        assertEquals(List.of("http://localhost:8080/viscolink/iaf/ladybug/api/metadata/DatabaseDebugStorage"
                + "?filterHeader=patientId&filter=x&limit=200&offset=0"
                + "&metadataNames=storageId&metadataNames=patientId&metadataNames=flow"), ladybugUrls);
    }

    @Test
    void corePackFiltersOnItsOwnMetadataName() throws Exception {
        PackRegistryTestSupport.override(new CorePack());

        controller.doGet(request("/traces", "subjectFilter=x&flowFilter=f"), response);

        assertEquals(1, ladybugUrls.size());
        assertTrue(ladybugUrls.get(0).contains("?filterHeader=subjectId&filter=x&"), ladybugUrls.get(0));
    }

    @Test
    void corePackKeepsTheAliasButNotTheHealthHeader() throws Exception {
        PackRegistryTestSupport.override(new CorePack());

        controller.doGet(request("/traces", "patientFilter=x&flowFilter=f"), response);

        assertEquals(1, ladybugUrls.size());
        assertTrue(ladybugUrls.get(0).contains("?filterHeader=subjectId&filter=x&"), ladybugUrls.get(0));
    }

    private static HttpServletRequest request(String pathInfo, String queryString) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getPathInfo()).thenReturn(pathInfo);
        when(req.getQueryString()).thenReturn(queryString.isEmpty() ? null : queryString);
        when(req.getLocalPort()).thenReturn(8080);
        when(req.getContextPath()).thenReturn("/viscolink");
        return req;
    }
}
