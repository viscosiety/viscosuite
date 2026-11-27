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

package org.frankframework.visco.security;

import java.io.IOException;
import java.io.Serial;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.frankframework.http.rest.ApiListenerServlet;

/**
 * The REST endpoints of one group ({@code /runtime/api/<group>/...}, rewritten in place by the image's routing to
 * this servlet's mapping {@code /api-group/<group>/*}) under the group's own login
 * ({@code servlet.api-<group>.authenticator}, {@code .securityRoles}). It serves the same listeners as {@code /api/*}:
 * the dispatcher sees the full path {@code /<group>/...}, so a listener keeps its own uriPattern. Not an
 * {@code @IbisInitializer}: {@link ApiGroupServletRegistrar} creates one per listed group.
 */
public class ApiGroupServlet extends ApiListenerServlet {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String group;

    public ApiGroupServlet(String group) {
        this.group = group;
    }

    @Override
    public String getName() {
        return "api-" + group;
    }

    @Override
    public String getUrlMapping() {
        return "/api-group/" + group + "/*";
    }

    static String pathFor(String group, String pathInfo) {
        if (pathInfo == null || pathInfo.equals("/")) {
            return "/" + group;
        }
        return "/" + group + pathInfo;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String full = pathFor(group, request.getPathInfo());
        super.service(new HttpServletRequestWrapper(request) {
            @Override
            public String getPathInfo() {
                return full;
            }
        }, response);
    }

    /** The parent clears the listener table it shares with the public servlet; that one clears it at shutdown. */
    @Override
    public void destroy() {
        // nothing of its own to release
    }
}
