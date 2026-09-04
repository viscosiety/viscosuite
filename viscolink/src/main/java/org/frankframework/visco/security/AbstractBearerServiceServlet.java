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
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.WebApplicationContextUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.frankframework.lifecycle.DynamicRegistration;
import org.frankframework.util.AppConstants;

/**
 * Shared base for the BEARER_ONLY {@code /api-service/*} servlet family. Historically nine
 * servlets bridging programmatic bearer callers to console capabilities the browser-session-only
 * {@code /iaf/api} chain could not serve (this repo's 2026-08-08 console-bearer-reload design
 * doc). Since the Frank!Framework gained {@code allowBearerAuthentication} on
 * {@code OAuth2Authenticator} (frankframework/frankframework#11542), bearer callers reach the
 * console's own endpoints directly, so the proxy-shaped members were removed; only
 * {@link ConfigRefServlet} remains -- it talks to {@code GitClassLoader} directly and has no
 * {@code /iaf/api} equivalent.
 *
 * <p>Carries the three hard-won mechanics from the (since-removed) reload servlet verbatim:</p>
 * <ul>
 * <li><b>Fail-closed role check</b> independent of {@code ServletManager} registration: a
 * missing/broken properties wire-up yields this class's own 401, never an open endpoint.</li>
 * <li><b>Request-context binding</b>: these servlets run outside Spring's DispatcherServlet, so
 * the console's session-scoped {@code clientSession} bean needs {@link RequestContextHolder}
 * bound manually or every call dies with ScopeNotActiveException.</li>
 * <li><b>SecurityContext elevation</b>: management-bus endpoints are annotated with F!F console
 * roles ({@code @RolesAllowed}) a tenant service-account token never carries; the tenant-role
 * check here is the real authorization gate, past it the bus call runs elevated while keeping
 * the caller's principal name for the bus's own audit logging.</li>
 * </ul>
 */
abstract class AbstractBearerServiceServlet extends HttpServlet implements DynamicRegistration.Servlet {

	/** {@code BearerOnlyAuthenticator} prefixes every mapped JWT role with this (F!F convention). */
	protected static final String ROLE_PREFIX = "ROLE_";

	/**
	 * Fallback when the servlet's securityRoles property is unset -- deliberately a role string no
	 * real Keycloak-issued token could ever carry, so an unconfigured deploy fails closed.
	 */
	static final String UNCONFIGURED_ROLE = "viscoforge-tenant:UNCONFIGURED";

	protected static final ObjectMapper JSON = new ObjectMapper();

	private final Logger log = LogManager.getLogger(getClass());

	/** Property holding this servlet's required tenant role, e.g. {@code servlet.adapters.securityRoles}. */
	protected abstract String securityRolesProperty();

	/** F!F console roles the elevated bus call runs with (e.g. IbisAdmin, IbisTester). */
	protected abstract String[] elevatedRoles();

	@Override
	public String[] getAccessGrantingRoles() {
		return new String[] { requiredRole() };
	}

	protected final String requiredRole() {
		return AppConstants.getInstance().getProperty(securityRolesProperty(), UNCONFIGURED_ROLE);
	}

	protected final boolean rejectUnauthorized(HttpServletResponse resp) throws IOException {
		String required = requiredRole();
		if (callerHasRole(required)) {
			return false;
		}
		log.warn("rejected {} request: caller does not hold required role [{}]", getName(), required);
		resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
		return true;
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
	 * Runs {@code body} with the request bound to {@link RequestContextHolder} and the
	 * SecurityContext elevated to {@link #elevatedRoles()} -- see the class javadoc. Previous
	 * attributes/context are restored, not cleared.
	 */
	protected final <T> T callElevated(HttpServletRequest req, HttpServletResponse resp, ElevatedCall<T> body)
			throws IOException {
		RequestAttributes previousAttributes = RequestContextHolder.getRequestAttributes();
		SecurityContext callerContext = SecurityContextHolder.getContext();
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req, resp));
		try {
			SecurityContext elevated = SecurityContextHolder.createEmptyContext();
			List<SimpleGrantedAuthority> authorities = Arrays.stream(elevatedRoles())
					.map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
					.toList();
			elevated.setAuthentication(new PreAuthenticatedAuthenticationToken(
					callerContext.getAuthentication().getName(), "n/a", authorities));
			SecurityContextHolder.setContext(elevated);
			return body.call();
		} finally {
			SecurityContextHolder.setContext(callerContext);
			RequestContextHolder.setRequestAttributes(previousAttributes);
		}
	}

	@FunctionalInterface
	protected interface ElevatedCall<T> {
		T call() throws IOException;
	}

	/**
	 * Resolves a bean from the console's (root) WebApplicationContext -- a CHILD of this servlet's
	 * own context, so {@code @Autowired} can't reach it; by request time the fully-booted console
	 * context is always registered on the shared ServletContext. Returns null (never throws) so
	 * callers can 503 cleanly while the console is still booting.
	 */
	protected final <T> T lookupConsoleBean(HttpServletRequest req, Class<T> type) {
		WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(req.getServletContext());
		if (ctx == null) {
			log.error("no root WebApplicationContext available -- console not initialised yet");
			return null;
		}
		try {
			return ctx.getBean(type);
		} catch (NoSuchBeanDefinitionException e) {
			log.error("bean [{}] not found in console context", type.getSimpleName(), e);
			return null;
		}
	}

	protected final void writeJson(HttpServletResponse resp, Object body) throws IOException {
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		JSON.writeValue(resp.getOutputStream(), body);
	}

	/** Truncates {@code text} to {@code max} chars, appending a note when something was cut. */
	static String truncate(String text, int max) {
		if (text == null || text.length() <= max) {
			return text;
		}
		return text.substring(0, max) + " ...[truncated]";
	}

	/** Full failure detail belongs in the log; {@link #sanitizedReason} is what callers may see. */
	protected final void logBusFailure(String operation, Exception e) {
		log.warn("{} call failed", operation, e);
	}

	/**
	 * First line of the exception message, capped -- enough for an agent/user to act on
	 * ("adapter [X] does not exist") without relaying stack detail or nested causes.
	 */
	protected static String sanitizedReason(Exception e) {
		String message = e.getMessage();
		if (message == null || message.isBlank()) {
			return e.getClass().getSimpleName();
		}
		String firstLine = message.lines().findFirst().orElse(message);
		return truncate(firstLine, 300);
	}
}
