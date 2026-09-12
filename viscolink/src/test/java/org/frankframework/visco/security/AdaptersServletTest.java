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
import java.util.List;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.frankframework.management.bus.BusMessageUtils;
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
class AdaptersServletTest {

	private static final String REQUIRED_ROLE = "viscoforge-tenant:bo-demo";

	@Mock ServletContext servletContext;
	@Mock WebApplicationContext webApplicationContext;
	@Mock OutboundGateway gateway;
	@Mock HttpServletRequest request;
	@Mock HttpServletResponse response;

	private AdaptersServlet servlet;
	private ByteArrayOutputStream responseBody;

	@BeforeEach
	void setUp() throws Exception {
		servlet = new AdaptersServlet();
		AppConstants.getInstance().setProperty(AdaptersServlet.SECURITY_ROLES_PROPERTY, REQUIRED_ROLE);
		responseBody = new ByteArrayOutputStream();
		lenient().when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
			@Override public boolean isReady() { return true; }
			@Override public void setWriteListener(WriteListener l) {}
			@Override public void write(int b) { responseBody.write(b); }
		});
		Authentication auth = new TestingAuthenticationToken(
				"svc", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + REQUIRED_ROLE)));
		auth.setAuthenticated(true);
		SecurityContextHolder.getContext().setAuthentication(auth);
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		AppConstants.getInstance().remove(AdaptersServlet.SECURITY_ROLES_PROPERTY);
	}

	private void givenConsoleContextWithGateway() {
		when(request.getServletContext()).thenReturn(servletContext);
		when(servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE))
				.thenReturn(webApplicationContext);
		when(webApplicationContext.getBean(OutboundGateway.class)).thenReturn(gateway);
	}

	private JsonNode json() throws IOException {
		return new ObjectMapper().readTree(responseBody.toByteArray());
	}

	@Test
	void leanProjectionUnchangedAndSendsNoExpandedHeaders() throws Exception {
		givenConsoleContextWithGateway();
		when(request.getParameter("expanded")).thenReturn(null);
		when(gateway.sendSyncMessage(any())).thenReturn(new GenericMessage<>(
				"{\"tenant/Rcv\":{\"name\":\"Rcv\",\"configuration\":\"tenant\",\"state\":\"started\",\"receivers\":[{\"name\":\"R1\"}]}}"));

		servlet.doGet(request, response);

		ArgumentCaptor<Message<?>> captor = messageCaptor();
		verify(gateway).sendSyncMessage(captor.capture());
		Message<?> sent = captor.getValue();
		// The lean projection must NOT ask the bus to expand.
		assertNull(sent.getHeaders().get(BusMessageUtils.HEADER_PREFIX + "expanded"));
		assertNull(sent.getHeaders().get(BusMessageUtils.HEADER_PREFIX + "showPendingMsgCount"));

		JsonNode body = json();
		assertEquals(1, body.size());
		JsonNode row = body.get(0);
		assertEquals("tenant", row.get("configuration").asText());
		assertEquals("Rcv", row.get("adapter").asText());
		assertEquals("started", row.get("state").asText());
		// Lean projection carries exactly the three keys, never receivers.
		assertFalse(row.has("receivers"));
		assertFalse(row.has("messagesProcessed"));
	}

	@Test
	void expandedProjectionSendsExpandedHeadersAndCarriesReceiversAndErrorCounts() throws Exception {
		givenConsoleContextWithGateway();
		when(request.getParameter("expanded")).thenReturn("all");
		when(gateway.sendSyncMessage(any())).thenReturn(new GenericMessage<>(
				"{\"tenant/Rcv\":{"
						+ "\"name\":\"Rcv\",\"configuration\":\"tenant\",\"state\":\"started\","
						+ "\"messagesProcessed\":10,\"lastMessage\":123456789,"
						+ "\"receivers\":[{"
						+ "  \"name\":\"R1\","
						+ "  \"messages\":{\"received\":7,\"retried\":0,\"rejected\":0},"
						+ "  \"transactionalStores\":{\"ERROR\":{\"name\":\"Error\",\"numberOfMessages\":3}}"
						+ "}]}}"));

		servlet.doGet(request, response);

		ArgumentCaptor<Message<?>> captor = messageCaptor();
		verify(gateway).sendSyncMessage(captor.capture());
		Message<?> sent = captor.getValue();
		assertEquals("all", sent.getHeaders().get(BusMessageUtils.HEADER_PREFIX + "expanded"));
		assertEquals(Boolean.TRUE, sent.getHeaders().get(BusMessageUtils.HEADER_PREFIX + "showPendingMsgCount"));

		JsonNode body = json();
		JsonNode row = body.get(0);
		assertEquals("tenant", row.get("configuration").asText());
		assertEquals("Rcv", row.get("adapter").asText());
		assertEquals("started", row.get("state").asText());
		assertEquals(10, row.get("messagesProcessed").asLong());
		assertEquals(123456789L, row.get("lastMessage").asLong());
		JsonNode receiver = row.get("receivers").get(0);
		assertEquals("R1", receiver.get("name").asText());
		assertEquals(7, receiver.get("messages").get("received").asInt());
		assertEquals(3, receiver.get("transactionalStores").get("ERROR").get("numberOfMessages").asInt());
	}

	@Test
	void expandedProjectionToleratesReceiverWithoutErrorStore() throws Exception {
		givenConsoleContextWithGateway();
		when(request.getParameter("expanded")).thenReturn("all");
		when(gateway.sendSyncMessage(any())).thenReturn(new GenericMessage<>(
				"{\"tenant/Rcv\":{\"name\":\"Rcv\",\"configuration\":\"tenant\",\"state\":\"started\","
						+ "\"receivers\":[{\"name\":\"R1\"}]}}"));

		servlet.doGet(request, response);

		JsonNode receiver = json().get(0).get("receivers").get(0);
		assertEquals("R1", receiver.get("name").asText());
		// Missing counts serialize as null, never crash.
		assertTrue(receiver.get("transactionalStores").get("ERROR").get("numberOfMessages").isNull());
		assertTrue(receiver.get("messages").get("received").isNull());
	}

	@Test
	void noAuthenticationIs401() throws Exception {
		SecurityContextHolder.clearContext();
		servlet.doGet(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
		verifyNoInteractions(gateway);
	}

	@SuppressWarnings("unchecked")
	private static ArgumentCaptor<Message<?>> messageCaptor() {
		return ArgumentCaptor.forClass(Message.class);
	}
}
