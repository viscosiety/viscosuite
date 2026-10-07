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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.viscosiety.pack.CorePack;
import com.viscosiety.pack.PackJson;
import com.viscosiety.pack.PackRegistry;
import com.viscosiety.pack.PackRegistryTestSupport;

import org.frankframework.lifecycle.DynamicRegistration;
import org.frankframework.lifecycle.IbisInitializer;
import org.frankframework.util.AppConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The descriptor endpoint is not secret but it is not public either: every test here is either a
 * case of the bearer gate staying in force, or of what an authorised caller gets.
 */
class PackServletTest {

	private static final String REQUIRED_ROLE = "viscoforge-tenant:bo-demo";

	private PackServlet servlet;
	private HttpServletRequest request;
	private HttpServletResponse response;
	private ByteArrayOutputStream responseBody;

	@BeforeEach
	void setUp() throws Exception {
		servlet = new PackServlet();
		AppConstants.getInstance().setProperty(PackServlet.SECURITY_ROLES_PROPERTY, REQUIRED_ROLE);
		request = mock(HttpServletRequest.class);
		responseBody = new ByteArrayOutputStream();
		response = mock(HttpServletResponse.class);
		// Only the 200 path writes a body; the rejected paths never touch getOutputStream().
		lenient().when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
			@Override public boolean isReady() { return true; }
			@Override public void setWriteListener(WriteListener l) {}
			@Override public void write(int b) { responseBody.write(b); }
		});
		givenCallerWithRole(REQUIRED_ROLE);
		PackRegistryTestSupport.reset();
	}

	@AfterEach
	void tearDown() {
		PackRegistryTestSupport.reset();
		SecurityContextHolder.clearContext();
		AppConstants.getInstance().remove(PackServlet.SECURITY_ROLES_PROPERTY);
	}

	private static void givenCallerWithRole(String role) {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
				"svc", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
	}

	private JsonNode json() throws IOException {
		return new ObjectMapper().readTree(responseBody.toByteArray());
	}

	@Test
	void isRegisteredLikeTheOtherBearerServlets() {
		// ServletManager finds a servlet by this annotation and maps it by getUrlMapping().
		assertTrue(PackServlet.class.isAnnotationPresent(IbisInitializer.class));
		assertInstanceOf(DynamicRegistration.Servlet.class, servlet);
		assertEquals("/api-service/pack", servlet.getUrlMapping());
		assertArrayEquals(new String[] { REQUIRED_ROLE }, servlet.getAccessGrantingRoles());
	}

	/**
	 * ServletManager reads the servlet's own {@code servlet.<name>.authenticator/securityRoles/...}
	 * settings by {@link PackServlet#getName()}, while the fail-closed check here reads the roles
	 * from the property {@code securityRolesProperty()} names. A deploy that sets
	 * {@code servlet.pack.authenticator=bearer} only binds if the two agree.
	 */
	@Test
	void nameAndRolesPropertyAgreeSoOneSetOfDeploymentPropertiesConfiguresBoth() {
		assertEquals("servlet." + servlet.getName() + ".securityRoles", PackServlet.SECURITY_ROLES_PROPERTY);
		assertEquals(PackServlet.SECURITY_ROLES_PROPERTY, servlet.securityRolesProperty());
	}

	/** No bus call happens here, so there is nothing to elevate to; an elevation set would only be a standing grant. */
	@Test
	void neverElevates() {
		assertEquals(0, servlet.elevatedRoles().length);
	}

	@Test
	void noAuthenticationIs401() throws Exception {
		SecurityContextHolder.clearContext();
		servlet.doGet(request, response);
		verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
		assertEquals(0, responseBody.size());
	}

	/**
	 * 401 and not 403: the shared base class has one answer for "not authenticated" and "does not
	 * hold the role" (it must not tell a caller which of the two it was). The 403 an
	 * authenticated-but-unauthorised caller sees in a deployment comes from the Frank!Framework
	 * security chain in front of the servlet, which this test does not run.
	 */
	@Test
	void callerWithoutTheRoleIs401() throws Exception {
		givenCallerWithRole("viscoforge-tenant:someone-else");
		servlet.doGet(request, response);
		verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
		assertEquals(0, responseBody.size());
	}

	@Test
	void anonymousCallerIs401EvenWithTheRoleAuthority() throws Exception {
		SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
				"key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_" + REQUIRED_ROLE))));
		servlet.doGet(request, response);
		verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
		assertEquals(0, responseBody.size());
	}

	/** An unconfigured deploy must fail closed, not serve everyone. */
	@Test
	void unsetRolesPropertyIs401() throws Exception {
		AppConstants.getInstance().remove(PackServlet.SECURITY_ROLES_PROPERTY);
		servlet.doGet(request, response);
		verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
		assertEquals(0, responseBody.size());
	}

	@Test
	void callerWithTheRoleGetsTheDescriptorAsJson() throws Exception {
		servlet.doGet(request, response);

		verify(response).setContentType("application/json");
		verify(response).setCharacterEncoding("UTF-8");
		verify(response).setHeader("Cache-Control", "no-store");
		verify(response, never()).sendError(anyInt());
		verify(response, never()).sendError(anyInt(), anyString());
		JsonNode body = json();
		assertEquals("health", body.get("id").asText());
		assertEquals("patientId", body.get("subject").get("sessionKey").asText());
		// Not a re-implementation of the rendering: exactly what PackJson makes of the registry's pack.
		assertEquals(PackJson.of(PackRegistry.get()), responseBody.toString(StandardCharsets.UTF_8));
	}

	/** The registry is read per request, so what is served always is what the console security registrar resolved. */
	@Test
	void servesWhicheverPackTheRegistryResolved() throws Exception {
		PackRegistryTestSupport.override(new CorePack());
		servlet.doGet(request, response);
		assertEquals("core", json().get("id").asText());
	}

	@Test
	void everyOtherMethodIs405() throws Exception {
		// The base class overrides GET only; HttpServlet's own defaults must answer the rest.
		// A real PUT/POST/DELETE reaches those through service(), which reads the method and protocol.
		lenient().when(request.getProtocol()).thenReturn("HTTP/1.1");
		for (String method : List.of("PUT", "POST", "DELETE")) {
			reset(response);
			when(request.getMethod()).thenReturn(method);

			servlet.service((jakarta.servlet.ServletRequest) request, (jakarta.servlet.ServletResponse) response);

			verify(response).sendError(eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED), anyString());
		}
		assertEquals(0, responseBody.size());
	}
}
