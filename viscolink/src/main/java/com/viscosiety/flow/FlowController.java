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

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ReadListener;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes ViscoFlow frontend requests to the embedded Ladybug and F!F APIs via
 * in-process {@link RequestDispatcher#forward}. No network round-trip.
 *
 * Mapped at {@code /flow-api/*} — outside F!F's {@code /api/*} ownership —
 * so requests are not intercepted by {@code ApiListenerServlet}.
 *
 * Future mutation endpoints (replay, delete) will be added here as POST/DELETE
 * handlers once the Ladybug mutation API is wired up.
 */
@WebServlet("/flow-api/*")
public class FlowController extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ServletException {
        String path = req.getPathInfo();
        if (path == null) path = "/";
        String qs = req.getQueryString() != null ? req.getQueryString() : "";

        if (path.startsWith("/rerun/")) {
            String storageId = path.substring("/rerun/".length());
            String storage   = param(qs, "storage", "DatabaseDebugStorage");
            String target    = "/iaf/ladybug/api/runner/run/" + enc(storage) + "/" + enc(storageId);
            getServletContext().getRequestDispatcher(target).forward(req, resp);
            return;
        }

        if (path.startsWith("/copy-to-test/")) {
            String storageId = path.substring("/copy-to-test/".length());
            String storage   = param(qs, "storage", "DatabaseDebugStorage");
            byte[] body      = ("{\"" + storage + "\": [" + storageId + "]}")
                    .getBytes(StandardCharsets.UTF_8);
            // In-process forward to Ladybug's store API (PUT) — stays inside the authenticated
            // request, so no credential-less localhost loopback (which 401s on secured stages).
            forwardWithBody(req, resp, "PUT", "/iaf/ladybug/api/report/store/Test", body);
            return;
        }

        if ("/stubbed-run".equals(path)) {
            handleStubbedRun(req, resp, qs);
            return;
        }

        resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown flow path: " + path);
    }

    /**
     * Generic, product-neutral "run with stubbed senders" endpoint.
     *
     * <p>{@code POST /flow-api/stubbed-run?config=..&adapter=..} with the input message as the
     * request body runs that adapter in-process with every sender stubbed to a no-op (see
     * {@link com.viscosiety.ladybug.StubbedRunner} / {@link com.viscosiety.ladybug.StubbingDebugger}).
     * All transform pipes execute; the run is captured as a Ladybug report under the returned
     * {@code correlationId}, with zero outbound side effects. A general testing aid — not a
     * share-specific path.</p>
     */
    private void handleStubbedRun(HttpServletRequest req, HttpServletResponse resp, String qs)
            throws IOException {
        String configuration = param(qs, "config", "");
        String adapter       = param(qs, "adapter", "");
        // Optional provenance: the source report this run was derived from, carried through as a session
        // key so it lands on the produced report (surfaced as `originId` in the Ladybug Shareable view).
        String originId = param(qs, "originId", "");
        // Optional caller-chosen correlation-id prefix (blank -> StubbedRunner.DEFAULT_CID_PREFIX).
        String cidPrefix = param(qs, "cidPrefix", "");
        if (adapter.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "adapter query param is required");
            return;
        }
        com.viscosiety.ladybug.StubbedRunner runner = com.viscosiety.ladybug.StubbedRunner.getInstance();
        if (runner == null) {
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "stubbed runner not initialised");
            return;
        }
        byte[] input = req.getInputStream().readAllBytes();
        try {
            com.viscosiety.ladybug.StubbedRunner.Result result = runner.runStubbed(configuration, adapter, input, originId, cidPrefix);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json");
            MAPPER.writeValue(resp.getWriter(), new LinkedHashMap<>(Map.of(
                    "correlationId", result.correlationId(),
                    "configuration", configuration,
                    "adapter", adapter,
                    "state", result.state())));
        } catch (IllegalArgumentException e) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
        }
    }

    /**
     * In-process forward to an embedded API endpoint with a synthesized HTTP method and JSON body.
     * Unlike a localhost HTTP loopback this stays within the original authenticated request, so the
     * forwarded call inherits its credentials/security context (works on auth-enabled stages).
     */
    private void forwardWithBody(HttpServletRequest req, HttpServletResponse resp,
            String method, String target, byte[] body) throws ServletException, IOException {
        HttpServletRequest wrapped = new HttpServletRequestWrapper(req) {
            @Override public String getMethod()            { return method; }
            @Override public String getContentType()       { return "application/json"; }
            @Override public int    getContentLength()     { return body.length; }
            @Override public long   getContentLengthLong() { return body.length; }
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream in = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read()                          { return in.read(); }
                    @Override public boolean isFinished()                { return in.available() == 0; }
                    @Override public boolean isReady()                   { return true; }
                    @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        };
        getServletContext().getRequestDispatcher(target).forward(wrapped, resp);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int COMBINED_BATCH = 200;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ServletException {
        String path = req.getPathInfo();
        if (path == null) path = "/";
        String qs = req.getQueryString() != null ? req.getQueryString() : "";

        if ("/traces".equals(path)
                && !param(qs, "patientFilter", "").isEmpty()
                && !param(qs, "flowFilter", "").isEmpty()) {
            handleCombinedTraces(req, resp, qs);
            return;
        }

        String target = resolveTarget(path, qs);
        if (target == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown flow path: " + path);
            return;
        }

        // Build a clean parameter map from the original QS (minus "storage") so that
        // Tomcat's RequestDispatcher param-merging does not duplicate values.
        // Forward to a path WITHOUT a query string; all params come from the wrapper.
        final Map<String, String[]> cleanParams = parseQueryString(dropParam(qs, "storage"));
        HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(req) {
            @Override public Map<String, String[]> getParameterMap()          { return cleanParams; }
            @Override public String   getParameter(String n)                  { String[] v = cleanParams.get(n); return v != null && v.length > 0 ? v[0] : null; }
            @Override public String[] getParameterValues(String n)            { return cleanParams.get(n); }
            @Override public Enumeration<String> getParameterNames()          { return Collections.enumeration(cleanParams.keySet()); }
        };
        RequestDispatcher dispatcher = getServletContext().getRequestDispatcher(target);
        dispatcher.forward(wrapped, resp);
    }

    @SuppressWarnings("unchecked")
    private void handleCombinedTraces(HttpServletRequest req, HttpServletResponse resp, String qs)
            throws IOException {
        String storage       = param(qs, "storage",       "DatabaseDebugStorage");
        String patientFilter = param(qs, "patientFilter", "");
        String flowFilter    = param(qs, "flowFilter",    "");
        int    limit         = parseIntParam(qs, "limit",  50);
        int    offset        = parseIntParam(qs, "offset",  0);
        List<String> metadataNames = paramValues(qs, "metadataNames");

        int need = offset + limit;
        List<Map<String, Object>> matching = new ArrayList<>();
        int ladybugOffset = 0;
        boolean exhausted = false;

        while (matching.size() < need && !exhausted) {
            String url = buildLadybugMetadataUrl(req, storage, "patientId", patientFilter,
                    metadataNames, COMBINED_BATCH, ladybugOffset);
            List<Map<String, Object>> page = fetchJsonList(url, req.getHeader("Authorization"));
            if (page.isEmpty()) { exhausted = true; break; }
            for (Map<String, Object> record : page) {
                if (flowFilter.equals(record.get("flow"))) matching.add(record);
            }
            if (page.size() < COMBINED_BATCH) exhausted = true;
            ladybugOffset += page.size();
        }

        int from = Math.min(offset, matching.size());
        int to   = Math.min(need,   matching.size());
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(resp.getWriter(), matching.subList(from, to));
    }

    private String buildLadybugMetadataUrl(HttpServletRequest req, String storage,
            String filterHeader, String filter, List<String> metadataNames, int limit, int offset) {
        StringBuilder sb = new StringBuilder();
        sb.append("http://localhost:").append(req.getLocalPort())
          .append(req.getContextPath())
          .append("/iaf/ladybug/api/metadata/").append(enc(storage))
          .append("?filterHeader=").append(enc(filterHeader))
          .append("&filter=").append(enc(filter))
          .append("&limit=").append(limit)
          .append("&offset=").append(offset);
        for (String name : metadataNames) {
            sb.append("&metadataNames=").append(enc(name));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchJsonList(String urlStr, String authHeader) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        // Re-use the caller's credentials so this localhost loopback passes auth on secured stages.
        if (authHeader != null) conn.setRequestProperty("Authorization", authHeader);
        int status = conn.getResponseCode();
        if (status >= 400) return Collections.emptyList();
        try (var is = conn.getInputStream()) {
            return MAPPER.readValue(is, List.class);
        }
    }

    private static int parseIntParam(String qs, String name, int def) {
        String v = param(qs, name, null);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    private static List<String> paramValues(String qs, String name) {
        List<String> result = new ArrayList<>();
        if (qs == null || qs.isEmpty()) return result;
        String prefix = name + "=";
        for (String kv : qs.split("&")) {
            if (kv.startsWith(prefix)) {
                try { result.add(URLDecoder.decode(kv.substring(prefix.length()), StandardCharsets.UTF_8)); }
                catch (Exception e) { result.add(kv.substring(prefix.length())); }
            }
        }
        return result;
    }

    private String resolveTarget(String path, String qs) {
        String storage  = param(qs, "storage", "DatabaseDebugStorage");
        String storageE = enc(storage);

        return switch (path) {
            case "/storage" ->
                "/iaf/ladybug/api/testtool/views";

            case "/traces" ->
                "/iaf/ladybug/api/metadata/" + storageE;

            case "/trace-count" ->
                "/iaf/ladybug/api/metadata/" + storageE + "/count";

            case "/adapter-flow" -> {
                String config  = param(qs, "config", "");
                String adapter = param(qs, "adapter", "");
                if (config.isEmpty() || adapter.isEmpty()) yield null;
                yield "/iaf/api/configurations/" + enc(config)
                        + "/adapters/" + enc(adapter) + "/flow";
            }

            case "/config-xml" -> {
                String config = param(qs, "config", "");
                if (config.isEmpty()) yield null;
                yield "/iaf/api/configurations/" + enc(config);
            }

            default -> {
                if (path.startsWith("/trace/")) {
                    String storageId = path.substring("/trace/".length());
                    yield "/iaf/ladybug/api/report/" + storageE + "/" + enc(storageId);
                }
                yield null;
            }
        };
    }

    private static String param(String qs, String name, String def) {
        if (qs == null || qs.isEmpty()) return def;
        for (String kv : qs.split("&")) {
            if (kv.startsWith(name + "=")) {
                try { return URLDecoder.decode(kv.substring(name.length() + 1), StandardCharsets.UTF_8); }
                catch (Exception e) { return def; }
            }
        }
        return def;
    }

    private static String dropParam(String qs, String name) {
        if (qs == null || qs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String kv : qs.split("&")) {
            if (kv.isEmpty() || kv.equals(name) || kv.startsWith(name + "=")) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(kv);
        }
        return sb.toString();
    }

    private static Map<String, String[]> parseQueryString(String qs) {
        Map<String, List<String>> multi = new LinkedHashMap<>();
        if (qs != null && !qs.isEmpty()) {
            for (String kv : qs.split("&")) {
                if (kv.isEmpty()) continue;
                int eq = kv.indexOf('=');
                String key = eq >= 0 ? kv.substring(0, eq) : kv;
                String val = eq >= 0 ? kv.substring(eq + 1) : "";
                try {
                    key = URLDecoder.decode(key, StandardCharsets.UTF_8);
                    val = URLDecoder.decode(val, StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
                multi.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
            }
        }
        Map<String, String[]> result = new LinkedHashMap<>();
        multi.forEach((k, v) -> result.put(k, v.toArray(new String[0])));
        return result;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
