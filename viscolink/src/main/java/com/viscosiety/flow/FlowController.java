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

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
            String body      = "{\"" + storage + "\": [" + storageId + "]}";
            String url       = "http://localhost:" + req.getLocalPort()
                    + req.getContextPath() + "/iaf/ladybug/api/report/store/Test";
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (var os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            resp.setStatus(status);
            resp.setContentType("application/json");
            java.io.InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            try (is; var os = resp.getOutputStream()) {
                is.transferTo(os);
            }
            return;
        }

        resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown flow path: " + path);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ServletException {
        String path = req.getPathInfo();
        if (path == null) path = "/";
        String qs = req.getQueryString() != null ? req.getQueryString() : "";

        String target = resolveTarget(path, qs);
        if (target == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown flow path: " + path);
            return;
        }

        RequestDispatcher dispatcher = getServletContext().getRequestDispatcher(target);
        dispatcher.forward(req, resp);
    }

    private String resolveTarget(String path, String qs) {
        String storage  = param(qs, "storage", "DatabaseDebugStorage");
        String storageE = enc(storage);
        String fwdQs    = dropParam(qs, "storage");

        return switch (path) {
            case "/storage" ->
                "/iaf/ladybug/api/testtool/views";

            case "/traces" ->
                "/iaf/ladybug/api/metadata/" + storageE
                        + (fwdQs.isEmpty() ? "" : "?" + fwdQs);

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

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
