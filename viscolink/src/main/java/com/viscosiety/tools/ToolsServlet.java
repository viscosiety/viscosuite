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

package com.viscosiety.tools;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.frankframework.util.AppConstants;

import java.io.IOException;

@WebServlet("/tools/*")
public class ToolsServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if (path == null) path = "/";

        switch (path) {
            case "/health"   -> writeJson(resp, 200, "{\"status\":\"UP\"}");
            case "/registry" -> handleRegistry(resp);
            case "/config"   -> handleConfig(resp);
            default          -> writeJson(resp, 404, "{\"error\":\"Not found\",\"path\":\"" + path + "\"}");
        }
    }

    private void handleConfig(HttpServletResponse resp) throws IOException {
        AppConstants props = AppConstants.getInstance();
        String host     = props.getProperty("test-client.host",     "");
        String port     = props.getProperty("test-client.port",     "");
        String user     = props.getProperty("test-client.user",     "");
        String password = props.getProperty("test-client.password", "");
        writeJson(resp, 200,
            "{\"host\":"     + jsonStr(host)     +
            ",\"port\":"     + jsonStr(port)     +
            ",\"user\":"     + jsonStr(user)     +
            ",\"password\":" + jsonStr(password) + "}");
    }

    private void handleRegistry(HttpServletResponse resp) throws IOException {
        AppConstants props = AppConstants.getInstance();
        String names = props.getProperty("viscolink.views.names", "");

        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (String id : names.split(",")) {
            id = id.trim();
            if (id.isEmpty()) continue;
            String name = props.getProperty("viscolink.views." + id + ".name", id);
            String url  = props.getProperty("viscolink.views." + id + ".url",  "");
            String desc = props.getProperty("viscolink.views." + id + ".description", "");
            if (!first) json.append(",");
            json.append("{\"id\":").append(jsonStr(id))
                .append(",\"name\":").append(jsonStr(name))
                .append(",\"url\":").append(jsonStr(url))
                .append(",\"description\":").append(jsonStr(desc))
                .append("}");
            first = false;
        }
        json.append("]");
        writeJson(resp, 200, json.toString());
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
               + "\"";
    }

    private void writeJson(HttpServletResponse resp, int status, String body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(body);
    }
}
