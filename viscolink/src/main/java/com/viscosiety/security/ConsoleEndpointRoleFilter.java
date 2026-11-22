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
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Narrows two Frank!Console API endpoints for callers who hold none of the roles {@code IbisAdmin},
 * {@code IbisTester} and {@code IbisDataAdmin} -- the read-only {@code IbisObserver} above all. Both carry
 * {@code @AllowAllIbisUserRoles} in the Frank!Framework console backend (10.3), which no property narrows.
 *
 * <ul>
 *   <li>{@code POST /iaf/api/jdbc/liquibase} takes an uploaded changelog; in its generate-SQL mode Liquibase
 *       resolves {@code <sqlFile path>} and {@code <include file>}, so the answer can carry files of the server.
 *       Refused with a plain, empty 403 (any method but GET and HEAD). {@code GET /iaf/api/jdbc/liquibase}, the
 *       changelog download, is left alone.</li>
 *   <li>{@code /iaf/api/environmentvariables} shows the container environment, the system properties and every
 *       property unmasked. The console frontend reads it at every page load (only
 *       {@code ["Application Constants"]["Global"]}) and shows a "Forbidden" toast on a 403, so it is NOT refused:
 *       the answer is rewritten to exactly {@code {"Application Constants":{"Global":{...}}}} with only the keys
 *       of {@link #FRONTEND_KEYS} (and {@code customViews.<id>.name/url/target}) that are present. The rewrite
 *       fails closed: an answer that does not parse, does not have that shape, or is larger than
 *       {@link #MAX_BUFFERED_BYTES} becomes {@code {"Application Constants":{"Global":{}}}}. The status code is
 *       kept; {@code ETag}, {@code Content-Length} and conditional request headers do not pass, so a cached copy
 *       of the full answer cannot be revalidated and no hash of it leaks.</li>
 * </ul>
 *
 * <p><b>Where it runs:</b> declared in {@code web.xml}, mapped to {@code /iaf/api/*}. The console's Spring Security
 * filter ({@code springSecurityFilterChain}) is registered programmatically with {@code isMatchAfter=false}, so
 * Tomcat runs it before every filter declared in {@code web.xml}: by the time this filter runs the caller is
 * authenticated (OAuth2 browser login with its role mapping, a bearer JWT, Basic or YML users) and the
 * {@link SecurityContextHolder} holds the authentication. An unauthenticated caller never gets here: the console
 * chain answers 401 first. The Frank!Framework gives every role the authority {@code ROLE_<role>}; the plain role
 * name and {@link HttpServletRequest#isUserInRole} are accepted as well. Nothing found = treated as not allowed, so
 * a chain that ran in another order would narrow everyone, never open the endpoints.</p>
 *
 * <p>The path is matched after the Host's rewrite: {@code /viscolink/iaf/api/...} is served in place at
 * {@code /runtime/iaf/api/...}, and the context-relative path is the same. The check compares the path
 * case-insensitively and without trailing or doubled slashes, so a spelling the console might serve cannot slip
 * past.</p>
 */
public class ConsoleEndpointRoleFilter implements Filter {

    private static final Logger log = LogManager.getLogger(ConsoleEndpointRoleFilter.class);

    /** Roles that get the full endpoints. {@code IbisObserver} and {@code IbisWebService} do not. */
    static final Set<String> ALLOWED_ROLES = Set.of("IbisAdmin", "IbisTester", "IbisDataAdmin");

    /**
     * The {@code Application Constants -> Global} keys the console frontend (10.3.0) reads: version check, server
     * type, status page, poller interval, custom views, default datasource, Larva menu guard, schedules, Ladybug
     * echo, Statistics settings. {@code customViews.<id>.name/url/target} are matched by {@link #CUSTOM_VIEW_KEY}.
     */
    static final Set<String> FRONTEND_KEYS = Set.of(
            "application.version",
            "application.server.type.custom",
            "application.console.status.hide",
            "console.pollerInterval",
            "customViews.names",
            "jdbc.datasource.default",
            "larva.enabled",
            "loadDatabaseSchedules.active",
            "testtool.echo2.enabled",
            "Statistics.boundaries",
            "Statistics.size.boundaries",
            "Statistics.percentiles",
            "Statistics.percentiles.publish",
            "Statistics.histograms.publish");

    static final Pattern CUSTOM_VIEW_KEY = Pattern.compile("customViews\\.[^.]+\\.(name|url|target)");

    /** The full answer can be large (every property, the environment, the system properties). */
    static final int MAX_BUFFERED_BYTES = 8 * 1024 * 1024;

    static final String EMPTY_ANSWER = "{\"Application Constants\":{\"Global\":{}}}";

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String APPLICATION_CONSTANTS = "Application Constants";
    private static final String GLOBAL = "Global";
    private static final String ENVIRONMENT_VARIABLES = "/iaf/api/environmentvariables";
    private static final String LIQUIBASE = "/iaf/api/jdbc/liquibase";
    private static final Set<String> CONDITIONAL_HEADERS = Set.of(
            "if-none-match", "if-modified-since", "if-match", "if-unmodified-since", "if-range", "range");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        Endpoint endpoint = endpointOf(req);
        if (endpoint == Endpoint.OTHER || holdsAllowedRole(req)) {
            chain.doFilter(request, response);
            return;
        }
        if (endpoint == Endpoint.LIQUIBASE_UPLOAD) {
            log.debug("refused {} {}: the caller holds none of {}", req.getMethod(), contextPath(req), ALLOWED_ROLES);
            res.resetBuffer();
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentLength(0);
            res.flushBuffer();
            return;
        }
        BufferingResponse buffered = new BufferingResponse(res);
        try {
            chain.doFilter(new UnconditionalRequest(req), buffered);
        } finally {
            buffered.closeWriter();
        }
        String answer = buffered.overflowed() ? EMPTY_ANSWER : narrowed(buffered.body(), buffered.bodyCharset());
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        res.setContentType("application/json;charset=UTF-8");
        res.setContentLength(bytes.length);
        res.getOutputStream().write(bytes);
        res.flushBuffer();
    }

    enum Endpoint { ENVIRONMENT, LIQUIBASE_UPLOAD, OTHER }

    /** Which of the narrowed endpoints the request reaches (environmentvariables any method; liquibase not GET/HEAD). */
    static Endpoint endpointOf(HttpServletRequest req) {
        String path = normalised(contextPath(req));
        if (ENVIRONMENT_VARIABLES.equals(path)) {
            return Endpoint.ENVIRONMENT;
        }
        if (LIQUIBASE.equals(path)) {
            String method = req.getMethod() == null ? "" : req.getMethod().toUpperCase(Locale.ROOT);
            return "GET".equals(method) || "HEAD".equals(method) ? Endpoint.OTHER : Endpoint.LIQUIBASE_UPLOAD;
        }
        return Endpoint.OTHER;
    }

    static boolean holdsAllowedRole(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getAuthorities() != null) {
            for (GrantedAuthority authority : auth.getAuthorities()) {
                String name = authority == null ? null : authority.getAuthority();
                if (name == null) {
                    continue;
                }
                String role = name.startsWith(ROLE_PREFIX) ? name.substring(ROLE_PREFIX.length()) : name;
                if (ALLOWED_ROLES.contains(role)) {
                    return true;
                }
            }
        }
        for (String role : ALLOWED_ROLES) {
            if (req.isUserInRole(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The console's environment answer reduced to {@code {"Application Constants":{"Global":{<frontend keys>}}}};
     * {@link #EMPTY_ANSWER} for anything that is not that shape.
     */
    static String narrowed(byte[] body, Charset charset) {
        try {
            JsonNode root = JSON.readTree(new String(body, charset));
            JsonNode global = root == null ? null : root.path(APPLICATION_CONSTANTS).path(GLOBAL);
            ObjectNode kept = JSON.createObjectNode();
            if (global != null && global.isObject()) {
                for (Iterator<Map.Entry<String, JsonNode>> it = global.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> field = it.next();
                    if (isFrontendKey(field.getKey()) && field.getValue().isValueNode()) {
                        kept.set(field.getKey(), field.getValue());
                    }
                }
            }
            ObjectNode out = JSON.createObjectNode();
            out.putObject(APPLICATION_CONSTANTS).set(GLOBAL, kept);
            return JSON.writeValueAsString(out);
        } catch (IOException | RuntimeException e) {
            log.debug("environment answer could not be narrowed ({}); answering an empty Global", e.getClass().getSimpleName());
            return EMPTY_ANSWER;
        }
    }

    static boolean isFrontendKey(String key) {
        return FRONTEND_KEYS.contains(key) || CUSTOM_VIEW_KEY.matcher(key).matches();
    }

    /** The path inside the webapp, decoded and without path parameters: servlet path + path info. */
    private static String contextPath(HttpServletRequest req) {
        String servletPath = req.getServletPath() == null ? "" : req.getServletPath();
        String pathInfo = req.getPathInfo() == null ? "" : req.getPathInfo();
        return servletPath + pathInfo;
    }

    private static String normalised(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** Hides conditional request headers, so the console always answers in full (never a 304 for a cached copy). */
    private static final class UnconditionalRequest extends HttpServletRequestWrapper {
        UnconditionalRequest(HttpServletRequest request) {
            super(request);
        }

        private static boolean hidden(String name) {
            return name != null && CONDITIONAL_HEADERS.contains(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public String getHeader(String name) {
            return hidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public long getDateHeader(String name) {
            return hidden(name) ? -1 : super.getDateHeader(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                    .filter(n -> !hidden(n)).toList());
        }
    }

    /**
     * Holds the console's answer in memory, up to {@link #MAX_BUFFERED_BYTES}; past that it drops what it has and
     * remembers the overflow. Nothing reaches the client and nothing commits until the filter writes the narrowed
     * answer; {@code ETag} and {@code Content-Length} of the full answer are not passed on.
     */
    static final class BufferingResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean overflowed;
        private ServletOutputStream stream;
        private PrintWriter writer;

        BufferingResponse(HttpServletResponse response) {
            super(response);
        }

        boolean overflowed() {
            return overflowed;
        }

        byte[] body() {
            return buffer.toByteArray();
        }

        Charset bodyCharset() {
            try {
                String name = getCharacterEncoding();
                return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
            } catch (RuntimeException e) {
                return StandardCharsets.UTF_8;
            }
        }

        void closeWriter() {
            if (writer != null) {
                writer.flush();
            }
        }

        private void append(byte[] b, int off, int len) {
            if (overflowed) {
                return;
            }
            if (buffer.size() + (long) len > MAX_BUFFERED_BYTES) {
                overflowed = true;
                buffer.reset();
                return;
            }
            buffer.write(b, off, len);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (stream == null) {
                stream = new ServletOutputStream() {
                    @Override
                    public void write(int b) {
                        append(new byte[] {(byte) b}, 0, 1);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        append(b, off, len);
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        // the answer is buffered; nothing to wait for
                    }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), bodyCharset()));
            }
            return writer;
        }

        @Override
        public void flushBuffer() {
            if (writer != null) {
                writer.flush();
            }
        }

        @Override
        public void resetBuffer() {
            buffer.reset();
            overflowed = false;
        }

        @Override
        public void reset() {
            super.reset();
            buffer.reset();
            overflowed = false;
        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        /** An error answer keeps its status; its page is not passed on (the filter writes the narrowed answer). */
        @Override
        public void sendError(int sc) {
            buffer.reset();
            setStatus(sc);
        }

        @Override
        public void sendError(int sc, String msg) {
            sendError(sc);
        }

        @Override
        public void setContentLength(int len) {
            // the narrowed answer sets its own
        }

        @Override
        public void setContentLengthLong(long len) {
            // the narrowed answer sets its own
        }

        private static boolean dropped(String name) {
            return "content-length".equalsIgnoreCase(name) || "etag".equalsIgnoreCase(name);
        }

        @Override
        public void setHeader(String name, String value) {
            if (!dropped(name)) {
                super.setHeader(name, value);
            }
        }

        @Override
        public void addHeader(String name, String value) {
            if (!dropped(name)) {
                super.addHeader(name, value);
            }
        }

        @Override
        public void setIntHeader(String name, int value) {
            if (!dropped(name)) {
                super.setIntHeader(name, value);
            }
        }

        @Override
        public void addIntHeader(String name, int value) {
            if (!dropped(name)) {
                super.addIntHeader(name, value);
            }
        }
    }
}
