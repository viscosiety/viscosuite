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

import java.util.List;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frankframework.util.AppConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentApiServletTest {

	private static final String REQUIRED_ROLE = "viscoforge-tenant:bo-demo";

	@Mock HttpServletRequest request;
	@Mock HttpServletResponse response;
	@Mock RequestDispatcher dispatcher;

	private AgentApiServlet servlet;

	@BeforeEach
	void setUp() {
		servlet = new AgentApiServlet();
		AppConstants.getInstance().setProperty(AgentApiServlet.SECURITY_ROLES_PROPERTY, REQUIRED_ROLE);
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
				"svc", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + REQUIRED_ROLE))));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		AppConstants.getInstance().remove(AgentApiServlet.SECURITY_ROLES_PROPERTY);
	}

	private HttpServletRequest forwardedRequest(String pathInfo, String expectedTarget) throws Exception {
		when(request.getPathInfo()).thenReturn(pathInfo);
		when(request.getQueryString()).thenReturn(null);
		when(request.getRequestDispatcher(expectedTarget)).thenReturn(dispatcher);

		servlet.service(request, response);

		ArgumentCaptor<HttpServletRequest> forwarded = ArgumentCaptor.forClass(HttpServletRequest.class);
		verify(dispatcher).forward(forwarded.capture(), same(response));
		return forwarded.getValue();
	}

	/**
	 * ApiListenerServlet's AUTHROLE check runs INSIDE the forward target (not in the Spring filter
	 * chain the forward bypasses), and matches request.isUserInRole against tenant-defined API-user
	 * names the service-account principal never carries. Without the grant every
	 * authenticationMethod="AUTHROLE" listener answers the agent 401 with no way to succeed.
	 */
	@Test
	void forwardedRequestSatisfiesAnyAuthRole() throws Exception {
		HttpServletRequest forwarded = forwardedRequest("/orders", "/api/orders");
		assertTrue(forwarded.isUserInRole("some-api-user"), "AUTHROLE role names must be granted on the forward");
		assertTrue(forwarded.isUserInRole("another-role"));
	}

	/** The grant exists only past the bearer gate -- an unauthenticated caller still gets this servlet's own 401. */
	@Test
	void withoutRoleNoForwardHappens() throws Exception {
		SecurityContextHolder.clearContext();
		servlet.service(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
		verify(request, never()).getRequestDispatcher(anyString());
	}
}
