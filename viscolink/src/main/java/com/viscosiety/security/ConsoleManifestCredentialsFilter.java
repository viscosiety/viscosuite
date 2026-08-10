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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Rewrites the Frank!Console's {@code index.html} so its web-app-manifest link carries
 * {@code crossorigin="use-credentials"}.
 *
 * <p><b>Why:</b> browsers fetch {@code <link rel="manifest">} WITHOUT credentials by default (a
 * spec quirk unique to manifest links -- unlike stylesheets or scripts, the request is made in
 * CORS mode with credentials omitted even for same-origin URLs). With the console behind OAuth2
 * login, the cookie-less manifest request never carries the JSESSIONID, so Spring Security 302s it
 * to Keycloak -- and F!F's own {@code CspFilter} policy ({@code default-src 'self'}, no
 * {@code manifest-src}) then makes the browser block the cross-origin redirect with a loud console
 * error on every console page load. {@code crossorigin="use-credentials"} switches the manifest
 * fetch to include cookies, so a logged-in session serves it same-origin like any other asset.</p>
 *
 * <p>The markup lives in F!F's {@code frankframework-console-frontend} jar, which this WAR cannot
 * patch, hence a response-rewriting filter scoped to exactly the console index document (the
 * {@code /iaf/gui/} welcome path and {@code /iaf/gui/index.html}). Conditional request headers are
 * stripped for those two paths so the underlying servlet always answers a full 200 -- a 304 would
 * revalidate the browser's cached, UN-rewritten copy. The index is ~3KB; losing 304s on it is
 * noise. Registered in web.xml; Spring Security's programmatically-registered filter (isMatchAfter
 * = false) still runs first, so authentication is untouched.</p>
 */
public class ConsoleManifestCredentialsFilter implements Filter {

    private static final String MANIFEST_LINK = "<link rel=\"manifest\"";
    private static final String MANIFEST_LINK_WITH_CREDENTIALS = "<link rel=\"manifest\" crossorigin=\"use-credentials\"";
    private static final Set<String> CONDITIONAL_HEADERS = Set.of("if-none-match", "if-modified-since");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        if (!isConsoleIndexRequest(req)) {
            chain.doFilter(request, response);
            return;
        }

        BufferingResponseWrapper buffered = new BufferingResponseWrapper(resp);
        chain.doFilter(new UnconditionalRequestWrapper(req), buffered);

        byte[] body = buffered.getBody();
        if (buffered.getStatus() == HttpServletResponse.SC_OK && isHtml(buffered.getContentType())) {
            Charset charset = charsetOf(buffered);
            String html = new String(body, charset);
            if (html.contains(MANIFEST_LINK) && !html.contains(MANIFEST_LINK_WITH_CREDENTIALS)) {
                body = html.replace(MANIFEST_LINK, MANIFEST_LINK_WITH_CREDENTIALS).getBytes(charset);
            }
        }
        resp.setContentLength(body.length);
        resp.getOutputStream().write(body);
    }

    private static boolean isConsoleIndexRequest(HttpServletRequest req) {
        if (!"GET".equals(req.getMethod())) {
            return false;
        }
        String path = req.getRequestURI().substring(req.getContextPath().length());
        return "/iaf/gui/".equals(path) || "/iaf/gui/index.html".equals(path);
    }

    private static boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html");
    }

    private static Charset charsetOf(HttpServletResponse response) {
        try {
            String encoding = response.getCharacterEncoding();
            return encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** Hides If-None-Match/If-Modified-Since so the index is always served as a full 200. */
    private static final class UnconditionalRequestWrapper extends HttpServletRequestWrapper {
        UnconditionalRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        private static boolean isConditional(String name) {
            return name != null && CONDITIONAL_HEADERS.contains(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String getHeader(String name) {
            return isConditional(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isConditional(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public long getDateHeader(String name) {
            return isConditional(name) ? -1L : super.getDateHeader(name);
        }
    }

    /** Buffers the body (stream- or writer-based) so it can be rewritten before hitting the wire. */
    private static final class BufferingResponseWrapper extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
        private ServletOutputStream stream;
        private PrintWriter writer;

        BufferingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        byte[] getBody() {
            if (writer != null) {
                writer.flush();
            }
            return buffer.toByteArray();
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (writer != null) {
                throw new IllegalStateException("getWriter() has already been called");
            }
            if (stream == null) {
                stream = new ServletOutputStream() {
                    @Override
                    public void write(int b) {
                        buffer.write(b);
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener writeListener) {
                        // Buffered in memory; async write notifications are meaningless here.
                    }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() {
            if (stream != null) {
                throw new IllegalStateException("getOutputStream() has already been called");
            }
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(buffer, charsetOf(this)));
            }
            return writer;
        }

        @Override
        public void setContentLength(int len) {
            // Suppressed: the rewritten body's length is set by the filter after buffering.
        }

        @Override
        public void setContentLengthLong(long len) {
            // Suppressed: see setContentLength.
        }
    }
}
