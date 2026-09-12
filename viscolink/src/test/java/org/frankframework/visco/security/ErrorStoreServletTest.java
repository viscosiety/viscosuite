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
import java.util.Base64;
import java.util.List;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.frankframework.management.bus.BusAction;
import org.frankframework.management.bus.BusMessageUtils;
import org.frankframework.management.bus.BusTopic;
import org.frankframework.management.bus.OutboundGateway;
import org.frankframework.util.AppConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ErrorStoreServletTest {

	private static final String REQUIRED_ROLE = "api-service:tester";

	@Mock ServletContext servletContext;
	@Mock WebApplicationContext webApplicationContext;
	@Mock OutboundGateway gateway;
	@Mock HttpServletRequest request;
	@Mock HttpServletResponse response;

	private ErrorStoreServlet servlet;
	private ByteArrayOutputStream responseBody;

	@BeforeEach
	void setUp() throws Exception {
		servlet = new ErrorStoreServlet();
		AppConstants.getInstance().setProperty(ErrorStoreServlet.SECURITY_ROLES_PROPERTY, REQUIRED_ROLE);
		responseBody = new ByteArrayOutputStream();
		lenient().when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
			@Override public boolean isReady() { return true; }
			@Override public void setWriteListener(WriteListener l) {}
			@Override public void write(int b) { responseBody.write(b); }
		});
		authenticateAs(REQUIRED_ROLE);
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		AppConstants.getInstance().remove(ErrorStoreServlet.SECURITY_ROLES_PROPERTY);
	}

	private void authenticateAs(String rawRole) {
		Authentication auth = new TestingAuthenticationToken(
				"svc", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + rawRole)));
		auth.setAuthenticated(true);
		SecurityContextHolder.getContext().setAuthentication(auth);
	}

	/** ServletContext -> WebApplicationContext -> OutboundGateway, the chain lookupConsoleBean walks. */
	private void givenConsoleContextWithGateway() {
		when(request.getServletContext()).thenReturn(servletContext);
		when(servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE))
				.thenReturn(webApplicationContext);
		when(webApplicationContext.getBean(OutboundGateway.class)).thenReturn(gateway);
	}

	private JsonNode json() throws IOException {
		return new ObjectMapper().readTree(responseBody.toByteArray());
	}

	private static String base64(String raw) {
		return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	// --- browse (GET, FIND) ---------------------------------------------------------------------

	@Test
	void browseSendsFindWithReceiverHeadersAndErrorProcessState() throws Exception {
		givenConsoleContextWithGateway();
		when(request.getPathInfo()).thenReturn("/tenant/adapters/MyAdapter/receivers/MyReceiver/stores/Error");
		when(request.getParameter("max")).thenReturn("50");
		when(gateway.sendSyncMessage(any())).thenReturn(new GenericMessage<>("{\"messageCount\":2,\"messages\":[]}"));

		servlet.doGet(request, response);

		ArgumentCaptor<Message<?>> captor = messageCaptor();
		verify(gateway).sendSyncMessage(captor.capture());
		Message<?> sent = captor.getValue();
		assertEquals(BusTopic.MESSAGE_BROWSER.name(), sent.getHeaders().get(BusTopic.TOPIC_HEADER_NAME));
		assertEquals(BusAction.FIND.name(), sent.getHeaders().get(BusAction.ACTION_HEADER_NAME));
		assertEquals("tenant", meta(sent, BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY));
		assertEquals("MyAdapter", meta(sent, BusMessageUtils.HEADER_ADAPTER_NAME_KEY));
		assertEquals("MyReceiver", meta(sent, BusMessageUtils.HEADER_RECEIVER_NAME_KEY));
		assertEquals("Error", meta(sent, "processState"));
		assertEquals(50, sent.getHeaders().get(BusMessageUtils.HEADER_PREFIX + "max"));

		// Payload passed through verbatim.
		assertEquals(2, json().get("messageCount").asInt());
	}

	@Test
	void browseWithoutMaxUsesDefault() throws Exception {
		givenConsoleContextWithGateway();
		when(request.getPathInfo()).thenReturn("/tenant/adapters/A/receivers/R/stores/Error");
		when(request.getParameter("max")).thenReturn(null);
		when(gateway.sendSyncMessage(any())).thenReturn(new GenericMessage<>("{}"));

		servlet.doGet(request, response);

		ArgumentCaptor<Message<?>> captor = messageCaptor();
		verify(gateway).sendSyncMessage(captor.capture());
		assertEquals(ErrorStoreServlet.DEFAULT_MAX, captor.getValue().getHeaders().get(BusMessageUtils.HEADER_PREFIX + "max"));
	}

	// --- get one message (GET, GET) -------------------------------------------------------------

	@Test
	void getMessageDecodesBase64IdAndSendsGetWithErrorProcessState() throws Exception {
		givenConsoleContextWithGateway();
		String rawId = "abc/def=ghi"; // contains '/' -- the reason ids are base64-encoded
		when(request.getPathInfo()).thenReturn("/tenant/adapters/A/receivers/R/stores/Error/messages/" + base64(rawId));
		when(gateway.sendSyncMessage(any())).thenReturn(new GenericMessage<>("{\"id\":\"x\",\"message\":\"boom\"}"));

		servlet.doGet(request, response);

		ArgumentCaptor<Message<?>> captor = messageCaptor();
		verify(gateway).sendSyncMessage(captor.capture());
		Message<?> sent = captor.getValue();
		assertEquals(BusTopic.MESSAGE_BROWSER.name(), sent.getHeaders().get(BusTopic.TOPIC_HEADER_NAME));
		assertEquals(BusAction.GET.name(), sent.getHeaders().get(BusAction.ACTION_HEADER_NAME));
		assertEquals("tenant", meta(sent, BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY));
		assertEquals("A", meta(sent, BusMessageUtils.HEADER_ADAPTER_NAME_KEY));
		assertEquals("R", meta(sent, BusMessageUtils.HEADER_RECEIVER_NAME_KEY));
		assertEquals("Error", meta(sent, "processState"));
		assertEquals(rawId, meta(sent, "messageId"));
		assertEquals("boom", json().get("message").asText());
	}

	@Test
	void getMessageWithInvalidBase64Is400() throws Exception {
		when(request.getPathInfo()).thenReturn("/tenant/adapters/A/receivers/R/stores/Error/messages/!!!not-base64!!!");
		servlet.doGet(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
		verifyNoInteractions(gateway);
	}

	// --- retry (PUT, UPLOAD, async) -------------------------------------------------------------

	@Test
	void retrySendsUploadAsyncWithoutProcessStateAndReturns200() throws Exception {
		givenConsoleContextWithGateway();
		String rawId = "42";
		when(request.getPathInfo()).thenReturn("/tenant/adapters/A/receivers/R/stores/Error/messages/" + base64(rawId));

		servlet.doPut(request, response);

		ArgumentCaptor<Message<?>> captor = messageCaptor();
		verify(gateway).sendAsyncMessage(captor.capture());
		verify(gateway, never()).sendSyncMessage(any());
		Message<?> sent = captor.getValue();
		assertEquals(BusTopic.MESSAGE_BROWSER.name(), sent.getHeaders().get(BusTopic.TOPIC_HEADER_NAME));
		assertEquals(BusAction.UPLOAD.name(), sent.getHeaders().get(BusAction.ACTION_HEADER_NAME));
		assertEquals("tenant", meta(sent, BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY));
		assertEquals("A", meta(sent, BusMessageUtils.HEADER_ADAPTER_NAME_KEY));
		assertEquals("R", meta(sent, BusMessageUtils.HEADER_RECEIVER_NAME_KEY));
		assertEquals(rawId, meta(sent, "messageId"));
		// UPLOAD/DELETE mirror the console: no processState header.
		assertNull(meta(sent, "processState"));
		verify(response).setStatus(HttpServletResponse.SC_OK);
	}

	@Test
	void retryWithoutMessageIdIs404() throws Exception {
		when(request.getPathInfo()).thenReturn("/tenant/adapters/A/receivers/R/stores/Error");
		servlet.doPut(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_NOT_FOUND), anyString());
	}

	// --- resolve (DELETE, DELETE, async) --------------------------------------------------------

	@Test
	void resolveSendsDeleteAsyncWithoutProcessStateAndReturns200() throws Exception {
		givenConsoleContextWithGateway();
		String rawId = "id-9";
		when(request.getPathInfo()).thenReturn("/tenant/adapters/A/receivers/R/stores/Error/messages/" + base64(rawId));

		servlet.doDelete(request, response);

		ArgumentCaptor<Message<?>> captor = messageCaptor();
		verify(gateway).sendAsyncMessage(captor.capture());
		Message<?> sent = captor.getValue();
		assertEquals(BusAction.DELETE.name(), sent.getHeaders().get(BusAction.ACTION_HEADER_NAME));
		assertEquals("tenant", meta(sent, BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY));
		assertEquals("R", meta(sent, BusMessageUtils.HEADER_RECEIVER_NAME_KEY));
		assertEquals(rawId, meta(sent, "messageId"));
		assertNull(meta(sent, "processState"));
		verify(response).setStatus(HttpServletResponse.SC_OK);
	}

	// --- fail-closed / errors -------------------------------------------------------------------

	@Test
	void noAuthenticationIs401AndNeverCallsBus() throws Exception {
		SecurityContextHolder.clearContext();
		// The role check short-circuits before the path is read -- lenient so strict-stub checking is happy.
		lenient().when(request.getPathInfo()).thenReturn("/tenant/adapters/A/receivers/R/stores/Error");
		servlet.doGet(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
		verifyNoInteractions(gateway);
	}

	@Test
	void wrongRoleIs401AndNeverCallsBus() throws Exception {
		authenticateAs("api-service:other");
		lenient().when(request.getPathInfo()).thenReturn("/tenant/adapters/A/receivers/R/stores/Error");
		servlet.doPut(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
		verifyNoInteractions(gateway);
	}

	@Test
	void unconfiguredSecurityRolesPropertyRejectsEveryCaller() throws Exception {
		AppConstants.getInstance().remove(ErrorStoreServlet.SECURITY_ROLES_PROPERTY);
		lenient().when(request.getPathInfo()).thenReturn("/tenant/adapters/A/receivers/R/stores/Error");
		servlet.doGet(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
		verifyNoInteractions(gateway);
	}

	@Test
	void unknownPathIs404() throws Exception {
		when(request.getPathInfo()).thenReturn("/tenant/adapters/A/pipes/P/stores/Error");
		servlet.doGet(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_NOT_FOUND), anyString());
		verifyNoInteractions(gateway);
	}

	@Test
	void missingConsoleBeanIs503() throws Exception {
		// Real ServletContext but no root WebApplicationContext registered yet.
		when(request.getServletContext()).thenReturn(servletContext);
		when(request.getPathInfo()).thenReturn("/tenant/adapters/A/receivers/R/stores/Error");
		servlet.doGet(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_SERVICE_UNAVAILABLE), anyString());
	}

	@Test
	void busFailureIsMappedTo502() throws Exception {
		givenConsoleContextWithGateway();
		when(request.getPathInfo()).thenReturn("/tenant/adapters/A/receivers/R/stores/Error");
		when(gateway.sendSyncMessage(any())).thenThrow(new RuntimeException("bus offline"));
		servlet.doGet(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_BAD_GATEWAY), anyString());
	}

	@Test
	void getNameAndUrlMapping() {
		assertEquals("errorStore", servlet.getName());
		assertEquals("/api-service/configurations/*", servlet.getUrlMapping());
	}

	@SuppressWarnings("unchecked")
	private static ArgumentCaptor<Message<?>> messageCaptor() {
		return ArgumentCaptor.forClass(Message.class);
	}

	private static Object meta(Message<?> message, String key) {
		return message.getHeaders().get(BusMessageUtils.HEADER_PREFIX + key);
	}
}
