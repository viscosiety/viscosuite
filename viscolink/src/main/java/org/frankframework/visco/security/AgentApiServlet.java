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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

import org.frankframework.lifecycle.IbisInitializer;
import org.frankframework.util.AppConstants;

/**
 * Bearer-JWT gateway to the instance's OWN ApiListener endpoints (and their
 * {@code openapi.json}): {@code /api-service/api/**} forwards within the webapp to
 * {@code /api/**}, so a tenant service-account token can invoke the REST endpoints the
 * tenant's own configuration exposes. See viscoFoundry's
 * docs/superpowers/specs/2026-08-20-agent-api-gateway-design.md.
 *
 * <p>Why a forward works where a direct Bearer call does not: whatever authenticator
 * guards {@code /api/*} owns direct calls. Since F!F's allowBearerAuthentication
 * (frankframework/frankframework#11542) a direct Bearer call passes when that guard is
 * the OAuth2 chain -- but a deployment can hand {@code servlet.ApiListenerServlet} to a
 * tenant-facing HTTP-Basic authenticator (ViscoForge's API-exposure / API-users modes),
 * and then a direct Bearer call gets that chain's 401 Basic challenge (realm "Frank",
 * live-observed 2026-09-04 after this servlet was briefly removed). This gateway is what
 * keeps PLATFORM access independent of the TENANT-FACING exposure mode, which is why it
 * survives the 2026-09 /api-service cleanup alongside ConfigRefServlet. A {@link RequestDispatcher#forward} from this
 * (bearer-authenticated) servlet reaches
 * {@code org.frankframework.http.rest.ApiListenerServlet} as an already-authenticated
 * request instead. NOTE (corrected 2026-08-26): the Spring Security filter chain DOES
 * run again on the FORWARD dispatch on this Spring Security version (its initializer
 * registers REQUEST/ERROR/ASYNC/FORWARD/INCLUDE) -- the forward passes because the
 * outer chain's saved SecurityContext rides along, not because the chain is skipped.
 * Method, headers, body, and query string all travel with the forward.</p>
 *
 * <p>That re-entered chain is also why AUTHROLE listeners need the SecurityContext
 * elevation below and not just a request wrapper: the inner chain re-wraps the request
 * with Spring's SecurityContextHolderAwareRequestWrapper, whose {@code isUserInRole}
 * answers from the SecurityContextHolder Authentication's authorities and never
 * consults the wrapper chain underneath (live-debugged 2026-08-26 -- the wrapper-only
 * fix shipped that morning was a no-op against a real Tomcat+Spring stack).</p>
 *
 * <p>Trust boundary: unchanged. The tenant's service-account token (checked against
 * {@code servlet.agentApi.securityRoles}, fail-closed) reaches exactly the endpoints
 * the tenant's own configuration serves on its own instance -- AUTHROLE narrows which
 * API user may call an endpoint, and the agent is not an API user.</p>
 */
@IbisInitializer
public class AgentApiServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	static final String SECURITY_ROLES_PROPERTY = "servlet.agentApi.securityRoles";

	/**
	 * The platform-rendered list of API-user names (= the only role names AUTHROLE listeners
	 * may reference). Granted to the forward's elevated SecurityContext so per-listener
	 * AUTHROLE checks pass for the agent.
	 */
	static final String API_LISTENER_ROLES_PROPERTY = "servlet.ApiListenerServlet.securityRoles";

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

		// See class javadoc: the FORWARD-dispatched security chain re-wraps the request and
		// answers isUserInRole from the SecurityContextHolder authorities, so the AUTHROLE
		// grant must live in the SecurityContext -- both in the holder (in case no filter
		// re-reads it) and in the request-attribute repository slot the inner chain's
		// SecurityContextHolderFilter actually loads from. Restored in the finally: this
		// thread returns to the container pool.
		SecurityContext callerContext = SecurityContextHolder.getContext();
		Object callerRepoAttribute = req.getAttribute(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME);
		SecurityContext elevated = SecurityContextHolder.createEmptyContext();
		elevated.setAuthentication(new PreAuthenticatedAuthenticationToken(
				callerContext.getAuthentication().getName(), "n/a", authRoleAuthorities()));
		try {
			SecurityContextHolder.setContext(elevated);
			req.setAttribute(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME, elevated);
			dispatcher.forward(new AuthRoleGrantingRequest(req), resp);
		} finally {
			SecurityContextHolder.setContext(callerContext);
			req.setAttribute(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME, callerRepoAttribute);
		}
	}

	/**
	 * The caller's own tenant role plus every platform-defined API-user name -- the complete
	 * set of role names a tenant AUTHROLE listener can legitimately reference.
	 */
	private List<SimpleGrantedAuthority> authRoleAuthorities() {
		Stream<String> apiUserRoles = Arrays.stream(
						AppConstants.getInstance().getProperty(API_LISTENER_ROLES_PROPERTY, "").split(","))
				.map(String::trim)
				.filter(role -> !role.isEmpty());
		return Stream.concat(Stream.of(requiredRole()), apiUserRoles)
				.map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
				.toList();
	}

	/**
	 * ApiListenerServlet's per-listener AUTHROLE check runs inside the forward target (not in the
	 * Spring filter chain the forward bypasses) and matches {@code isUserInRole} against
	 * tenant-defined API-user names the service-account principal never carries -- without this
	 * grant every {@code authenticationMethod="AUTHROLE"} listener answers the agent 401. Granting
	 * every role keeps the trust boundary from the class javadoc unchanged: past the tenant-role
	 * gate above, the caller may reach every endpoint the tenant's own configuration serves on its
	 * own instance -- AUTHROLE narrows *which API user* may call an endpoint, and the agent is not
	 * an API user.
	 */
	private static final class AuthRoleGrantingRequest extends HttpServletRequestWrapper {
		private AuthRoleGrantingRequest(HttpServletRequest request) {
			super(request);
		}

		@Override
		public boolean isUserInRole(String role) {
			return true;
		}
	}
}
