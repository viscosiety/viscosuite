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

package com.viscosiety.fhir;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the two authentication-enforcement boundaries of FHIR facades:
 *
 * <ol>
 *   <li>The path predicate used by the permit-all {@code SecurityFilterChain} to decide whether
 *       a {@code /fhir/} request targets a public {@code /metadata} endpoint.</li>
 *   <li>The {@link FhirOperationRegistry} lookup that determines whether a user-defined F!F
 *       pipeline should handle {@code /metadata} instead of the automatic builder.</li>
 * </ol>
 *
 * <p>Full end-to-end auth enforcement (401 vs 200 over HTTP) requires an integration test
 * that runs the Tomcat/Spring-Security stack; those live in {@code viscorunner}.</p>
 */
@ExtendWith(MockitoExtension.class)
class FhirSecurityPathTest {

    @Mock FhirListener listener;

    @BeforeEach
    void clearRegistry() {
        FhirOperationRegistry.clearForTesting();
    }

    // ── isPublicFhirMetadataPath — permit-all predicate ───────────────────────

    @Test
    void metadataPathMatchesWhenServletSplitsUrl() {
        // When a FhirFacadeServlet is registered, Tomcat sets:
        //   servletPath = /fhir/r4/my-facade  (the servlet prefix)
        //   pathInfo    = /metadata
        var req = requestWith("/fhir/r4/my-facade", "/metadata");
        assertTrue(FhirServletRegistrar.isPublicFhirMetadataPath(req));
    }

    @Test
    void metadataPathMatchesWhenDefaultServletHandlesFullPath() {
        // Before a FhirFacadeServlet is registered (or when DefaultServlet handles it):
        //   servletPath = /fhir/r4/my-facade/metadata
        //   pathInfo    = null
        var req = requestWith("/fhir/r4/my-facade/metadata", null);
        assertTrue(FhirServletRegistrar.isPublicFhirMetadataPath(req));
    }

    @Test
    void nonMetadataFhirPathDoesNotMatch() {
        var req = requestWith("/fhir/r4/my-facade", "/Patient/123");
        assertFalse(FhirServletRegistrar.isPublicFhirMetadataPath(req));
    }

    @Test
    void fhirBasePathWithoutTrailingMetadataDoesNotMatch() {
        var req = requestWith("/fhir/r4/my-facade", "/");
        assertFalse(FhirServletRegistrar.isPublicFhirMetadataPath(req));
    }

    @Test
    void nonFhirPathDoesNotMatch() {
        var req = requestWith("/iaf/api/server/health", null);
        assertFalse(FhirServletRegistrar.isPublicFhirMetadataPath(req));
    }

    @Test
    void fhirSearchPathDoesNotMatch() {
        // search: /fhir/r4/my-facade/Observation?code=...
        var req = requestWith("/fhir/r4/my-facade", "/Observation");
        assertFalse(FhirServletRegistrar.isPublicFhirMetadataPath(req));
    }

    // ── FhirOperationRegistry.getMetadataListener — pipeline priority ─────────

    @Test
    void registeredMetadataListenerIsFound() {
        FhirOperationRegistry.register(new FhirOperation("R4", "facade", "$metadata", "metadata"), listener);
        assertSame(listener, FhirOperationRegistry.getMetadataListener("R4", "facade"));
    }

    @Test
    void getMetadataListenerReturnsNullWhenNoneRegistered() {
        assertNull(FhirOperationRegistry.getMetadataListener("R4", "facade"));
    }

    @Test
    void metadataListenerDoesNotConflictWithProxyOrReadListeners() {
        FhirListener proxyListener = mock(FhirListener.class);
        FhirListener readListener  = mock(FhirListener.class);
        FhirOperationRegistry.register(new FhirOperation("R4", "facade", "*",         "proxy"),    proxyListener);
        FhirOperationRegistry.register(new FhirOperation("R4", "facade", "Patient",   "read"),     readListener);
        FhirOperationRegistry.register(new FhirOperation("R4", "facade", "$metadata", "metadata"), listener);

        assertSame(listener,      FhirOperationRegistry.getMetadataListener("R4", "facade"));
        assertSame(proxyListener, FhirOperationRegistry.getProxyListener("R4", "facade"));
        assertSame(readListener,  FhirOperationRegistry.getListener(
                new FhirOperation("R4", "facade", "Patient", "read")));
    }

    @Test
    void metadataListenerIsVersionAndFacadeScoped() {
        FhirListener r4Listener   = mock(FhirListener.class);
        FhirListener r5Listener   = mock(FhirListener.class);
        FhirListener otherFacade  = mock(FhirListener.class);
        FhirOperationRegistry.register(new FhirOperation("R4",    "facade-a", "$metadata", "metadata"), r4Listener);
        FhirOperationRegistry.register(new FhirOperation("R5",    "facade-a", "$metadata", "metadata"), r5Listener);
        FhirOperationRegistry.register(new FhirOperation("R4",    "facade-b", "$metadata", "metadata"), otherFacade);

        assertSame(r4Listener,  FhirOperationRegistry.getMetadataListener("R4", "facade-a"));
        assertSame(r5Listener,  FhirOperationRegistry.getMetadataListener("R5", "facade-a"));
        assertSame(otherFacade, FhirOperationRegistry.getMetadataListener("R4", "facade-b"));
        assertNull(              FhirOperationRegistry.getMetadataListener("DSTU3", "facade-a"));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static HttpServletRequest requestWith(String servletPath, String pathInfo) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getServletPath()).thenReturn(servletPath);
        when(req.getPathInfo()).thenReturn(pathInfo);
        return req;
    }
}
