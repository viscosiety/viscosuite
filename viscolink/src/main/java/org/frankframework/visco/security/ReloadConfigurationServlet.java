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

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import org.frankframework.console.controllers.FrankApiService;
import org.frankframework.lifecycle.DynamicRegistration;
import org.frankframework.lifecycle.IbisInitializer;
import org.frankframework.management.Action;
import org.frankframework.management.bus.BusMessageUtils;
import org.frankframework.management.bus.BusTopic;
import org.frankframework.management.bus.message.RequestMessageBuilder;
import org.frankframework.util.AppConstants;

/**
 * Bearer-JWT-only endpoint that triggers an all-configurations reload -- the API counterpart to
 * F!F's own {@code Configurations.fullAction()} (PUT /iaf/api/configurations, action=reload), but
 * reachable with a stateless service-account token instead of a browser session. See
 * docs/superpowers/specs/2026-08-08-console-bearer-reload-endpoint-design.md for why this can't
 * just be a Bearer call against the console's own /iaf/api/configurations path: that chain only
 * supports OAUTH2 browser-session login (see {@code OAuth2Authenticator}), and Spring's CSRF filter
 * rejects any bare PUT before authentication is even evaluated.
 *
 * <p>Registered via F!F's {@code application.security.http.authenticators} property-driven SPI
 * (see {@code ServletManager}) with a {@code BEARER_ONLY} authenticator on its own
 * {@code SecurityFilterChain} -- entirely env-var-injected (issuerUri, this servlet's required
 * role), no static properties file entries, mirroring exactly how the console's own OAUTH2
 * authenticator is configured today.</p>
 *
 * <p><b>Defense in depth:</b> {@code ServletManager.register()} silently skips ALL security
 * registration for a servlet whose resolved authenticator name is empty -- it does not fail loud.
 * If the env vars this depends on are ever missing on a real deploy, the properties-based wiring
 * alone would leave this endpoint completely open. This class independently re-checks
 * {@link SecurityContextHolder} before doing anything, so a missing/broken properties wire-up still
 * fails closed (this class's own 401), never open.</p>
 */
@IbisInitializer
public class ReloadConfigurationServlet extends HttpServlet implements DynamicRegistration.Servlet {

	@Serial
	private static final long serialVersionUID = 1L;

	/** Per-instance required role, e.g. {@code viscoforge-tenant:<accountSlug>-<instanceSlug>}. */
	static final String SECURITY_ROLES_PROPERTY = "servlet.reload.securityRoles";

	/**
	 * Fallback when {@link #SECURITY_ROLES_PROPERTY} is unset -- deliberately a role string no real
	 * Keycloak-issued token could ever carry, so an unconfigured deploy fails closed rather than
	 * granting access to whatever the Java-level default happened to be.
	 */
	static final String UNCONFIGURED_ROLE = "viscoforge-tenant:UNCONFIGURED";

	/** {@code BearerOnlyAuthenticator} prefixes every mapped JWT role with this (F!F convention). */
	private static final String ROLE_PREFIX = "ROLE_";

	private static final Logger log = LogManager.getLogger(ReloadConfigurationServlet.class);

	@Override
	public String getName() {
		return "reload";
	}

	@Override
	public String getUrlMapping() {
		return "/api-service/configurations";
	}

	@Override
	public String[] getAccessGrantingRoles() {
		return new String[] { requiredRole() };
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		String required = requiredRole();
		if (!callerHasRole(required)) {
			log.warn("rejected reload request: caller does not hold required role [{}]", required);
			resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
			return;
		}

		FrankApiService frankApiService = lookupFrankApiService(req);
		if (frankApiService == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "console services not initialised");
			return;
		}

		RequestMessageBuilder builder = RequestMessageBuilder.create(BusTopic.IBISACTION);
		builder.addHeader("action", Action.RELOAD.name());
		builder.addHeader(BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY, BusMessageUtils.ALL_CONFIGS_KEY);
		frankApiService.callAsyncGateway(builder);

		resp.setStatus(HttpServletResponse.SC_ACCEPTED);
	}

	private static String requiredRole() {
		return AppConstants.getInstance().getProperty(SECURITY_ROLES_PROPERTY, UNCONFIGURED_ROLE);
	}

	private static boolean callerHasRole(String required) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		boolean authenticated = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
		if (!authenticated) {
			return false;
		}
		String expectedAuthority = ROLE_PREFIX + required;
		return auth.getAuthorities().stream().anyMatch(a -> expectedAuthority.equals(a.getAuthority()));
	}

	/**
	 * The console's Spring context is a CHILD of this servlet's own context (see the design doc's
	 * context table), so {@code FrankApiService} can't be {@code @Autowired} directly -- resolve it
	 * live off the shared {@code ServletContext} instead, which by request time always has the
	 * fully-booted console context registered as root, regardless of exact boot ordering.
	 */
	private FrankApiService lookupFrankApiService(HttpServletRequest req) {
		WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(req.getServletContext());
		if (ctx == null) {
			log.error("no root WebApplicationContext available -- console not initialised yet");
			return null;
		}
		try {
			return ctx.getBean(FrankApiService.class);
		} catch (NoSuchBeanDefinitionException e) {
			log.error("FrankApiService bean not found in console context", e);
			return null;
		}
	}
}
