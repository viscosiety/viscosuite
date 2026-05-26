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

import ca.uhn.fhir.context.FhirContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FhirMetadataBuilderTest {

    private static final FhirContext R4_CTX = FhirContext.forR4();

    @Mock HttpServletRequest  req;
    @Mock HttpServletResponse resp;
    @Mock HttpClient          httpClient;
    @Mock FhirListener        proxyListener;

    private StringWriter responseBody;

    @BeforeEach
    void setup() throws Exception {
        FhirOperationRegistry.clearForTesting();
        responseBody = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(responseBody));
        lenient().when(req.getParameter("_format")).thenReturn(null);
        lenient().when(req.getHeader("Accept")).thenReturn(null);
    }

    // ── Content-type negotiation ──────────────────────────────────────────────

    @Test
    void defaultContentTypeIsJson() throws Exception {
        FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", null, httpClient);
        verify(resp).setContentType("application/fhir+json; charset=UTF-8");
    }

    @Test
    void formatParamXmlSelectsXml() throws Exception {
        when(req.getParameter("_format")).thenReturn("xml");
        FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", null, httpClient);
        verify(resp).setContentType("application/fhir+xml; charset=UTF-8");
    }

    @Test
    void formatParamFhirXmlSelectsXml() throws Exception {
        when(req.getParameter("_format")).thenReturn("application/fhir+xml");
        FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", null, httpClient);
        verify(resp).setContentType("application/fhir+xml; charset=UTF-8");
    }

    @Test
    void acceptHeaderXmlSelectsXml() throws Exception {
        when(req.getHeader("Accept")).thenReturn("application/fhir+xml");
        FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", null, httpClient);
        verify(resp).setContentType("application/fhir+xml; charset=UTF-8");
    }

    @Test
    void formatParamJsonOverridesAcceptXml() throws Exception {
        // _format short-circuits before Accept is read, so only stub _format
        when(req.getParameter("_format")).thenReturn("json");
        FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", null, httpClient);
        verify(resp).setContentType("application/fhir+json; charset=UTF-8");
    }

    // ── Fresh (non-proxy) capability statement ────────────────────────────────

    @Test
    void freshR4StatementHasCorrectPublisherKindAndFormats() throws Exception {
        FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", null, httpClient);

        CapabilityStatement cs = parseR4();
        assertEquals("Viscosiety ViscoLink", cs.getPublisher());
        assertEquals(CapabilityStatement.CapabilityStatementKind.INSTANCE, cs.getKind());
        assertTrue(cs.getFormat().stream().anyMatch(f -> "application/fhir+json".equals(f.getValue())));
        assertTrue(cs.getFormat().stream().anyMatch(f -> "application/fhir+xml".equals(f.getValue())));
    }

    @Test
    void freshR4StatementListsInteractionsForRegisteredOperations() throws Exception {
        FhirOperationRegistry.register(new FhirOperation("R4", "facade", "Patient",     "read"),   mock(FhirListener.class));
        FhirOperationRegistry.register(new FhirOperation("R4", "facade", "Observation", "search"), mock(FhirListener.class));

        FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", null, httpClient);
        CapabilityStatement cs = parseR4();
        var rest = cs.getRestFirstRep();

        assertTrue(rest.getResource().stream()
                .anyMatch(r -> "Patient".equals(r.getType())
                        && r.getInteraction().stream().anyMatch(
                                i -> i.getCode() == CapabilityStatement.TypeRestfulInteraction.READ)));
        assertTrue(rest.getResource().stream()
                .anyMatch(r -> "Observation".equals(r.getType())
                        && r.getInteraction().stream().anyMatch(
                                i -> i.getCode() == CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE)));
    }

    @Test
    void freshStatementExcludesProxyAndMetadataOperations() throws Exception {
        FhirOperationRegistry.register(new FhirOperation("R4", "facade", "*",         "proxy"),    mock(FhirListener.class));
        FhirOperationRegistry.register(new FhirOperation("R4", "facade", "$metadata", "metadata"), mock(FhirListener.class));

        FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", null, httpClient);
        CapabilityStatement cs = parseR4();

        assertTrue(cs.getRestFirstRep().getResource().isEmpty(),
                "proxy and metadata sentinels must not appear as REST resources");
    }

    @Test
    void freshStatementMapsBundleTransactionToSystemInteraction() throws Exception {
        FhirOperationRegistry.register(new FhirOperation("R4", "facade", "Bundle", "bundle-transaction"), mock(FhirListener.class));

        FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", null, httpClient);
        CapabilityStatement cs = parseR4();

        assertTrue(cs.getRestFirstRep().getInteraction().stream()
                .anyMatch(i -> i.getCode() == CapabilityStatement.SystemRestfulInteraction.TRANSACTION),
                "bundle-transaction should map to a system-level TRANSACTION interaction");
        assertTrue(cs.getRestFirstRep().getResource().isEmpty(),
                "bundle-transaction should NOT produce a REST resource entry");
    }

    // ── Proxy augmentation ────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void proxyStatementAddsExtensionAndPrefixesDescription() throws Exception {
        String cdrBase = "http://cdr.example.com/fhir";
        stubCdrResponse(minimalCdrJson("CDR description"));
        when(proxyListener.getProxyCdrBaseUrl()).thenReturn(cdrBase);
        when(proxyListener.getProxyCdrCredentialAlias()).thenReturn(null);

        FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", proxyListener, httpClient);
        CapabilityStatement cs = parseR4();

        assertTrue(cs.getDescription().startsWith("Proxied by ViscoLink from " + cdrBase),
                "description should be prefixed with the proxy note");
        assertTrue(cs.getDescription().contains("CDR description"),
                "original CDR description should be retained");
        assertTrue(cs.getExtension().stream().anyMatch(
                e -> "http://viscosiety.com/fhir/StructureDefinition/proxied-by".equals(e.getUrl())),
                "proxied-by extension must be added");
    }

    @Test
    @SuppressWarnings("unchecked")
    void proxyStatementAnnotatesInterceptedButNotPassthroughResources() throws Exception {
        // Observation/search is handled by ViscoLink; Patient is fully proxied to CDR
        FhirOperationRegistry.register(new FhirOperation("R4", "facade", "Observation", "search"), mock(FhirListener.class));

        stubCdrResponse(cdrJsonWithResources());
        when(proxyListener.getProxyCdrBaseUrl()).thenReturn("http://cdr.example.com/fhir");
        when(proxyListener.getProxyCdrCredentialAlias()).thenReturn(null);

        FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", proxyListener, httpClient);
        CapabilityStatement cs = parseR4();

        var obsRes = cs.getRestFirstRep().getResource().stream()
                .filter(r -> "Observation".equals(r.getType())).findFirst().orElseThrow();
        assertTrue(obsRes.getDocumentation() != null
                        && obsRes.getDocumentation().contains("Intercepted by ViscoLink"),
                "intercepted resource should be annotated");

        var patRes = cs.getRestFirstRep().getResource().stream()
                .filter(r -> "Patient".equals(r.getType())).findFirst().orElseThrow();
        assertFalse(patRes.getDocumentation() != null
                        && patRes.getDocumentation().contains("Intercepted by ViscoLink"),
                "non-intercepted resource must not be annotated");
    }

    @Test
    @SuppressWarnings("unchecked")
    void proxyFallsBackToFreshStatementOnNon200() throws Exception {
        HttpResponse<String> cdrResp = mock(HttpResponse.class);
        when(cdrResp.statusCode()).thenReturn(503);
        doReturn(cdrResp).when(httpClient).send(any(HttpRequest.class), any());
        when(proxyListener.getProxyCdrBaseUrl()).thenReturn("http://cdr.example.com/fhir");
        when(proxyListener.getProxyCdrCredentialAlias()).thenReturn(null);

        FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", proxyListener, httpClient);
        CapabilityStatement cs = parseR4();
        // Fresh fallback has Viscosiety as publisher
        assertEquals("Viscosiety ViscoLink", cs.getPublisher());
    }

    @Test
    @SuppressWarnings("unchecked")
    void proxyFallsBackToFreshStatementOnNetworkError() throws Exception {
        doThrow(new IOException("Connection refused")).when(httpClient).send(any(), any());
        when(proxyListener.getProxyCdrBaseUrl()).thenReturn("http://cdr.example.com/fhir");
        when(proxyListener.getProxyCdrCredentialAlias()).thenReturn(null);

        assertDoesNotThrow(() ->
                FhirMetadataBuilder.handle(req, resp, R4_CTX, "R4", "facade", proxyListener, httpClient));
        CapabilityStatement cs = parseR4();
        assertEquals("Viscosiety ViscoLink", cs.getPublisher());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CapabilityStatement parseR4() {
        return (CapabilityStatement) R4_CTX.newJsonParser().parseResource(responseBody.toString());
    }

    @SuppressWarnings("unchecked")
    private void stubCdrResponse(String json) throws IOException, InterruptedException {
        HttpResponse<String> cdrResp = mock(HttpResponse.class);
        when(cdrResp.statusCode()).thenReturn(200);
        when(cdrResp.body()).thenReturn(json);
        doReturn(cdrResp).when(httpClient).send(any(HttpRequest.class), any());
    }

    private static String minimalCdrJson(String description) {
        var cs = new CapabilityStatement();
        cs.setStatus(org.hl7.fhir.r4.model.Enumerations.PublicationStatus.ACTIVE);
        cs.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        cs.setFhirVersion(org.hl7.fhir.r4.model.Enumerations.FHIRVersion._4_0_1);
        cs.setDescription(description);
        return R4_CTX.newJsonParser().encodeResourceToString(cs);
    }

    private static String cdrJsonWithResources() {
        var cs = new CapabilityStatement();
        cs.setStatus(org.hl7.fhir.r4.model.Enumerations.PublicationStatus.ACTIVE);
        cs.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        cs.setFhirVersion(org.hl7.fhir.r4.model.Enumerations.FHIRVersion._4_0_1);
        var rest = cs.addRest().setMode(CapabilityStatement.RestfulCapabilityMode.SERVER);
        rest.addResource().setType("Patient")
                .addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.READ);
        rest.addResource().setType("Observation")
                .addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);
        return R4_CTX.newJsonParser().encodeResourceToString(cs);
    }
}
