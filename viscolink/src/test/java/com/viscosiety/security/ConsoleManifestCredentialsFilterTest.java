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

package com.viscosiety.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleManifestCredentialsFilterTest {

    /** Verbatim shape of the link tag in F!F console-frontend 10.x's index.html. */
    private static final String CONSOLE_INDEX_HTML =
            "<!doctype html><html><head>"
                    + "<link rel=\"manifest\" href=\"assets/favicon/site.webmanifest?v=2\"/>"
                    + "</head><body></body></html>";

    private ConsoleManifestCredentialsFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ConsoleManifestCredentialsFilter();
    }

    private MockHttpServletRequest indexRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/viscolink" + path);
        request.setContextPath("/viscolink");
        return request;
    }

    private FilterChain chainServing(String body, String contentType, int status) {
        return (req, res) -> {
            res.setContentType(contentType);
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(status);
            writeBody(res, body);
        };
    }

    private static void writeBody(ServletResponse res, String body) throws IOException {
        res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void addsUseCredentialsToManifestLinkOnGuiIndex() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(indexRequest("/iaf/gui/"), response, chainServing(CONSOLE_INDEX_HTML, "text/html;charset=UTF-8", 200));

        String out = response.getContentAsString();
        assertTrue(out.contains("<link rel=\"manifest\" crossorigin=\"use-credentials\" href=\"assets/favicon/site.webmanifest?v=2\"/>"),
                "manifest link must gain crossorigin=\"use-credentials\", got: " + out);
        assertEquals(out.getBytes(StandardCharsets.UTF_8).length, response.getContentLength(),
                "Content-Length must match the rewritten body");
    }

    @Test
    void rewritesIndexHtmlPathToo() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(indexRequest("/iaf/gui/index.html"), response, chainServing(CONSOLE_INDEX_HTML, "text/html", 200));

        assertTrue(response.getContentAsString().contains("crossorigin=\"use-credentials\""));
    }

    @Test
    void leavesOtherPathsUntouched() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(indexRequest("/iaf/gui/assets/main.js"), response, chainServing(CONSOLE_INDEX_HTML, "text/html", 200));

        // Chain ran against the REAL response (no buffering wrapper): body identical, untouched.
        assertEquals(CONSOLE_INDEX_HTML, response.getContentAsString());
    }

    @Test
    void leavesNonHtmlResponsesUntouched() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String json = "{\"name\":\"Frank\"}";

        filter.doFilter(indexRequest("/iaf/gui/"), response, chainServing(json, "application/json", 200));

        assertEquals(json, response.getContentAsString());
    }

    @Test
    void leavesNon200ResponsesUntouched() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(indexRequest("/iaf/gui/"), response, chainServing("redirecting", "text/html", 302));

        assertEquals("redirecting", response.getContentAsString());
    }

    @Test
    void isIdempotentWhenAttributeAlreadyPresent() throws Exception {
        String already = CONSOLE_INDEX_HTML.replace("<link rel=\"manifest\"",
                "<link rel=\"manifest\" crossorigin=\"use-credentials\"");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(indexRequest("/iaf/gui/"), response, chainServing(already, "text/html", 200));

        String out = response.getContentAsString();
        assertEquals(already, out, "an already-rewritten document must pass through unchanged");
    }

    @Test
    void stripsConditionalHeadersSoIndexIsNeverServedFromA304() throws Exception {
        // The rewrite must apply on every load: a 304 keeps the browser's cached, UN-rewritten
        // body, so conditional request headers may not reach the underlying servlet.
        MockHttpServletRequest request = indexRequest("/iaf/gui/");
        request.addHeader("If-None-Match", "\"abc\"");
        request.addHeader("If-Modified-Since", "Sun, 09 Aug 2026 00:00:00 GMT");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletRequest inner = (HttpServletRequest) req;
            assertNull(inner.getHeader("If-None-Match"));
            assertNull(inner.getHeader("If-Modified-Since"));
            assertEquals(-1L, inner.getDateHeader("If-Modified-Since"));
            res.setContentType("text/html");
            writeBody(res, CONSOLE_INDEX_HTML);
        });

        assertTrue(response.getContentAsString().contains("crossorigin=\"use-credentials\""));
    }

    @Test
    void writerBasedResponsesAreRewrittenToo() throws Exception {
        // Servlets may use getWriter() instead of getOutputStream(); the buffering wrapper must
        // support both (IllegalStateException lurks if the wrapper only implements one).
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(indexRequest("/iaf/gui/"), response, (req, res) -> {
            res.setContentType("text/html;charset=UTF-8");
            res.getWriter().write(CONSOLE_INDEX_HTML);
        });

        assertTrue(response.getContentAsString().contains("crossorigin=\"use-credentials\""));
    }
}
