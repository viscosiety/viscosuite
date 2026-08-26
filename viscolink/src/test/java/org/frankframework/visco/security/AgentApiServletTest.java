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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

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
		AppConstants.getInstance().setProperty(AgentApiServlet.API_LISTENER_ROLES_PROPERTY, "test, other-user");
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
				"svc", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + REQUIRED_ROLE))));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		AppConstants.getInstance().remove(AgentApiServlet.SECURITY_ROLES_PROPERTY);
		AppConstants.getInstance().remove(AgentApiServlet.API_LISTENER_ROLES_PROPERTY);
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

	/**
	 * Live-found 2026-08-26: on this Spring Security version the filter chain ALSO runs on
	 * FORWARD dispatch, and its SecurityContextHolderAwareRequestWrapper answers isUserInRole
	 * from the SecurityContextHolder Authentication's authorities -- never consulting the
	 * request-wrapper chain below it. So the request wrapper alone is not enough: during the
	 * forward the SecurityContext (holder AND the request-attribute repository the inner
	 * chain's SecurityContextHolderFilter reads) must carry the API-user role names from
	 * servlet.ApiListenerServlet.securityRoles, or AUTHROLE listeners still answer 401.
	 */
	@Test
	void forwardRunsUnderAuthRoleElevatedSecurityContext() throws Exception {
		when(request.getPathInfo()).thenReturn("/orders");
		when(request.getQueryString()).thenReturn(null);
		when(request.getRequestDispatcher("/api/orders")).thenReturn(dispatcher);
		List<String> authoritiesDuringForward = new java.util.ArrayList<>();
		doAnswer(invocation -> {
			Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			auth.getAuthorities().forEach(a -> authoritiesDuringForward.add(a.getAuthority()));
			return null;
		}).when(dispatcher).forward(any(), any());

		servlet.service(request, response);

		assertTrue(authoritiesDuringForward.contains("ROLE_test"), "got " + authoritiesDuringForward);
		assertTrue(authoritiesDuringForward.contains("ROLE_other-user"), "got " + authoritiesDuringForward);
		// The elevated context must also land in the request-attribute repository slot (that is
		// what the FORWARD-dispatched chain's SecurityContextHolderFilter loads from), and the
		// original value (null on this mock) must be put back afterwards.
		ArgumentCaptor<Object> attrValues = ArgumentCaptor.forClass(Object.class);
		verify(request, times(2)).setAttribute(
				eq(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME), attrValues.capture());
		SecurityContext repoContext = (SecurityContext) attrValues.getAllValues().get(0);
		assertTrue(repoContext.getAuthentication().getAuthorities().stream()
				.anyMatch(a -> "ROLE_test".equals(a.getAuthority())));
		// Audit identity: still the real caller, never a synthetic admin.
		assertEquals("svc", repoContext.getAuthentication().getName());
		assertNull(attrValues.getAllValues().get(1), "caller's repo-slot value must be restored");
		// Restored after the forward: the caller's own context, without the granted roles.
		Authentication after = SecurityContextHolder.getContext().getAuthentication();
		assertEquals("svc", after.getName());
		assertTrue(after.getAuthorities().stream().noneMatch(a -> "ROLE_test".equals(a.getAuthority())));
	}
}
