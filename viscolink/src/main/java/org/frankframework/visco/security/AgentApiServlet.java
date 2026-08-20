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

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frankframework.lifecycle.IbisInitializer;

/**
 * Bearer-JWT gateway to the instance's OWN ApiListener endpoints (and their
 * {@code openapi.json}): {@code /api-service/api/**} forwards within the webapp to
 * {@code /api/**}, so a tenant service-account token can invoke the REST endpoints the
 * tenant's own configuration exposes. See viscoFoundry's
 * docs/superpowers/specs/2026-08-20-agent-api-gateway-design.md.
 *
 * <p>Why a forward works where a direct Bearer call does not: the console's OAuth2
 * security chain guards {@code /api/*} and (in this F!F version) accepts only the
 * browser authorization-code flow -- a Bearer call 302s to the Keycloak login
 * (live-verified 2026-08-20). Spring Security registers its filter chain for the
 * REQUEST/ERROR/ASYNC dispatch types only, so a {@link RequestDispatcher#forward}
 * from this (bearer-authenticated) servlet reaches
 * {@code org.frankframework.http.rest.ApiListenerServlet} without re-entering that
 * chain. Method, headers, body, and query string all travel with the forward.</p>
 *
 * <p>Trust boundary: unchanged. The tenant's service-account token (checked against
 * {@code servlet.agentApi.securityRoles}, fail-closed) reaches exactly the endpoints
 * the tenant's own configuration serves on its own instance.</p>
 */
@IbisInitializer
public class AgentApiServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	static final String SECURITY_ROLES_PROPERTY = "servlet.agentApi.securityRoles";

	@Override
	public String getName() {
		return "agentApi";
	}

	@Override
	public String getUrlMapping() {
		return "/api-service/api/*";
	}

	@Override
	protected String securityRolesProperty() {
		return SECURITY_ROLES_PROPERTY;
	}

	@Override
	protected String[] elevatedRoles() {
		// No management-bus call happens here -- the forward target does its own work
		// under the servlet-container dispatch, so no SecurityContext elevation exists.
		return new String[0];
	}

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		if (rejectUnauthorized(resp)) {
			return;
		}
		// pathInfo carries everything after /api-service/api, e.g. "/random-cocktail" or
		// "/openapi.json"; null when the bare mapping root was requested.
		String pathInfo = req.getPathInfo() == null ? "/" : req.getPathInfo();
		String query = req.getQueryString();
		String target = "/api" + pathInfo + (query == null ? "" : "?" + query);
		RequestDispatcher dispatcher = req.getRequestDispatcher(target);
		if (dispatcher == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND, "no dispatcher for " + target);
			return;
		}
		dispatcher.forward(req, resp);
	}
}
