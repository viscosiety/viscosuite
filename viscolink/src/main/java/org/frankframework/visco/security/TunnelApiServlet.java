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
import jakarta.servlet.http.HttpServletResponse;

import org.frankframework.http.rest.ApiListenerServlet;
import org.frankframework.lifecycle.IbisInitializer;

import com.viscosiety.tunnel.TunnelPorts;

/**
 * The REST endpoints on the tunnel side: the same listeners as {@code /api/*}, served at
 * {@code /tunnel-api/*} under their own login ({@code servlet.tunnelApi.authenticator},
 * {@code servlet.tunnelApi.securityRoles}). Tomcat's rewrite rules send {@code /api/**} on a tunnel port
 * here; this servlet answers 404 to anything that did not arrive on a tunnel port, so the public port can
 * never reach a tunnel-only endpoint through it. It dispatches itself: no forward to {@code /api}, whose
 * security chain belongs to the public side.
 */
@IbisInitializer
public class TunnelApiServlet extends ApiListenerServlet {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return "tunnelApi";
    }

    @Override
    public String getUrlMapping() {
        return "/tunnel-api/*";
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!TunnelPorts.isTunnelPort(request.getLocalPort())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        super.service(request, response);
    }

    /** The parent clears the listener table it shares with the public servlet; that one clears it at shutdown. */
    @Override
    public void destroy() {
        // nothing to release: this servlet owns no state of its own
    }
}
