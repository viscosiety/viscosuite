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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.viscosiety.classloaders.GitClassLoader;
import com.viscosiety.classloaders.TempGitRepo;

import org.frankframework.configuration.IbisContext;
import org.frankframework.management.Action;
import org.frankframework.management.bus.BusMessageUtils;
import org.frankframework.management.bus.OutboundGateway;
import org.frankframework.util.AppConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigRefServletTest {

	private static final String REQUIRED_ROLE = "viscoforge-tenant:bo-demo";

	@TempDir Path tmp;

	@Mock ServletContext servletContext;
	@Mock WebApplicationContext webApplicationContext;
	@Mock OutboundGateway gateway;

	private ConfigRefServlet servlet;
	private GitClassLoader loader;
	private HttpServletRequest request;
	private HttpServletResponse response;
	private ByteArrayOutputStream responseBody;

	@BeforeEach
	void setUp() throws Exception {
		servlet = new ConfigRefServlet();
		AppConstants.getInstance().setProperty(ConfigRefServlet.SECURITY_ROLES_PROPERTY, REQUIRED_ROLE);
		loader = TempGitRepo.configuredLoader(tmp, mock(IbisContext.class), "tenant");
		request = mock(HttpServletRequest.class);
		response = mock(HttpServletResponse.class);
		responseBody = new ByteArrayOutputStream();
		// Only the happy-path / 409 tests actually write a JSON body; sendError-only tests never
		// touch getOutputStream() at all, so this stub must be lenient under strict-stub checking.
		lenient().when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
			@Override public boolean isReady() { return true; }
			@Override public void setWriteListener(WriteListener l) {}
			@Override public void write(int b) { responseBody.write(b); }
		});
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
				"svc", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + REQUIRED_ROLE))));
	}

	@AfterEach
	void tearDown() {
		loader.destroy();
		SecurityContextHolder.clearContext();
		AppConstants.getInstance().remove(ConfigRefServlet.SECURITY_ROLES_PROPERTY);
	}

	private void givenBody(String json) throws IOException {
		// putWithoutRoleIs401 calls this but never reaches the body-reading code (the role check
		// short-circuits first) -- lenient so that test doesn't trip strict-stub checking.
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		lenient().when(request.getContentLengthLong()).thenReturn((long) bytes.length);
		ByteArrayInputStream in = new ByteArrayInputStream(bytes);
		lenient().when(request.getInputStream()).thenReturn(new ServletInputStream() {
			@Override public boolean isFinished() { return in.available() == 0; }
			@Override public boolean isReady() { return true; }
			@Override public void setReadListener(ReadListener l) {}
			@Override public int read() { return in.read(); }
		});
	}

	private JsonNode json() throws IOException {
		return new ObjectMapper().readTree(responseBody.toByteArray());
	}

	/** ServletContext -> WebApplicationContext -> OutboundGateway mock, the chain lookupConsoleBean walks. */
	private void givenConsoleContextWithGateway() {
		when(request.getServletContext()).thenReturn(servletContext);
		when(servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE))
				.thenReturn(webApplicationContext);
		when(webApplicationContext.getBean(OutboundGateway.class)).thenReturn(gateway);
	}

	/**
	 * Records every message the servlet's reload worker sends. The dispatch is asynchronous
	 * (see {@link ConfigRefServlet}'s javadoc: an inline bus reload would blow past the 60s nginx
	 * timeout), so tests take the message off this queue with a timeout rather than asserting
	 * straight after {@code doPut}.
	 */
	private BlockingQueue<Message<?>> recordDispatches() {
		BlockingQueue<Message<?>> sent = new LinkedBlockingQueue<>();
		doAnswer(invocation -> sent.add(invocation.getArgument(0))).when(gateway).sendAsyncMessage(any());
		return sent;
	}

	@Test
	void getReportsCurrentRef() throws Exception {
		when(request.getParameter("configuration")).thenReturn("tenant");
		servlet.doGet(request, response);
		JsonNode body = json();
		assertEquals("tenant", body.get("configuration").asText());
		assertEquals("main", body.get("ref").asText());
		assertTrue(body.get("isDefault").asBoolean());
	}

	/**
	 * 409, not 404: on this endpoint 404 means "no such endpoint" (an instance whose image
	 * predates it), and the portal has to be able to tell that apart from a configuration that is
	 * merely between destroy() and configure() -- the {@code code} is what it keys on.
	 */
	@Test
	void getUnknownConfigurationIs409() throws Exception {
		when(request.getParameter("configuration")).thenReturn("nope");
		servlet.doGet(request, response);
		verify(response).setStatus(HttpServletResponse.SC_CONFLICT);
		JsonNode body = json();
		assertEquals("configuration-not-registered", body.get("code").asText());
		assertTrue(body.get("error").asText().contains("nope"));
	}

	@Test
	void putUnknownConfigurationIs409() throws Exception {
		givenBody("{\"configuration\":\"nope\",\"ref\":\"main\"}");
		servlet.doPut(request, response);
		verify(response).setStatus(HttpServletResponse.SC_CONFLICT);
		assertEquals("configuration-not-registered", json().get("code").asText());
	}

	@Test
	void putChecksOutBranchAndDispatchesNamedReloadAsynchronously() throws Exception {
		givenConsoleContextWithGateway();
		BlockingQueue<Message<?>> sent = recordDispatches();
		givenBody("{\"configuration\":\"tenant\",\"ref\":\"assistant/demo/draft-abc123\"}");

		servlet.doPut(request, response);

		// 202 + "reloading": the checkout is done and the reload is queued, but nothing about the
		// reload itself has been observed -- the response must not pretend otherwise.
		verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
		JsonNode body = json();
		assertEquals("assistant/demo/draft-abc123", body.get("ref").asText());
		assertFalse(body.get("isDefault").asBoolean());
		assertTrue(body.get("reloading").asBoolean());
		assertEquals("assistant/demo/draft-abc123", loader.currentRef());

		// Moving HEAD alone reloads nothing: the servlet must dispatch a NAMED RELOAD so
		// IbisContext.reload("tenant") runs (unload + load). *ALL* would be the silent no-op.
		// It arrives on the reload worker, hence the wait rather than a bare verify().
		Message<?> message = sent.poll(5, TimeUnit.SECONDS);
		assertNotNull(message, "reload was not dispatched within 5s");
		assertEquals(Action.RELOAD.name(), message.getHeaders().get(BusMessageUtils.HEADER_PREFIX + "action"));
		assertEquals("tenant", message.getHeaders().get(BusMessageUtils.HEADER_PREFIX + BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY));
	}

	@Test
	void putWithoutConsoleContextRevertsAndIs503() throws Exception {
		// A real ServletContext (HttpServletRequest.getServletContext() is never null in a
		// container) but no root WebApplicationContext registered on it yet -- getAttribute(...)
		// defaults to null on the mock, exercising AbstractBearerServiceServlet.lookupConsoleBean's
		// existing null-context branch. Nothing will reload this instance, so leaving it on the
		// draft branch would strand it running a configuration nobody confirmed had loaded: HEAD
		// must go back, and the 503 body must say where it ended up.
		when(request.getServletContext()).thenReturn(servletContext);
		givenBody("{\"configuration\":\"tenant\",\"ref\":\"assistant/demo/draft-abc123\"}");

		servlet.doPut(request, response);

		verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
		JsonNode body = json();
		assertTrue(body.get("error").asText().contains("retry"));
		assertEquals("main", body.get("ref").asText());
		assertEquals("main", loader.currentRef());
	}

	@Test
	void putGatewayFailureIsLoggedAndLeavesTheAlreadySentResponseAlone() throws Exception {
		// The response is written and gone before the worker ever touches the bus, so a bus
		// failure has no one left to tell -- it must be logged and dropped, never turned into a
		// second write on a committed response (which is an IllegalStateException in a container).
		givenConsoleContextWithGateway();
		givenBody("{\"configuration\":\"tenant\",\"ref\":\"assistant/demo/draft-abc123\"}");
		CountDownLatch dispatched = new CountDownLatch(1);
		doAnswer(invocation -> {
			dispatched.countDown();
			throw new RuntimeException("bus offline");
		}).when(gateway).sendAsyncMessage(any());

		servlet.doPut(request, response);

		verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
		assertTrue(json().get("reloading").asBoolean());
		assertTrue(dispatched.await(5, TimeUnit.SECONDS), "reload was not dispatched within 5s");
		verify(response, never()).sendError(anyInt(), anyString());
		verify(response, never()).setStatus(HttpServletResponse.SC_BAD_GATEWAY);
		// The checkout stands: it succeeded, and only the reload after it failed.
		assertEquals("assistant/demo/draft-abc123", loader.currentRef());
	}

	@Test
	void putUnknownBranchIs409AndLeavesRef() throws Exception {
		givenBody("{\"configuration\":\"tenant\",\"ref\":\"assistant/demo/missing\"}");
		servlet.doPut(request, response);
		verify(response).setStatus(HttpServletResponse.SC_CONFLICT);
		assertTrue(json().get("error").asText().contains("missing"));
		assertEquals("main", loader.currentRef());
	}

	@Test
	void putBranchWithoutSubdirRevertsAndIs409() throws Exception {
		String branch = TempGitRepo.addBranchWithoutSubdir(tmp);
		givenBody("{\"configuration\":\"tenant\",\"ref\":\"" + branch + "\"}");

		servlet.doPut(request, response);

		verify(response).setStatus(HttpServletResponse.SC_CONFLICT);
		JsonNode body = json();
		assertTrue(body.get("error").asText().contains(branch));
		assertTrue(body.get("error").asText().contains("ff-configurations/demo"));
		// Checkout succeeded before the subdir check failed -- the revert must put HEAD back, and
		// the body's own "ref" must say so (visible even if a revert ever failed).
		assertEquals("main", loader.currentRef());
		assertEquals("main", body.get("ref").asText());
		verifyNoInteractions(gateway);
	}

	@Test
	void putInvalidRefIs400() throws Exception {
		givenBody("{\"configuration\":\"tenant\",\"ref\":\"../x\"}");
		servlet.doPut(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
	}

	/**
	 * A non-string "ref" must be a missing field, not a coerced one: {@code asText(null)} alone
	 * would turn {@code 5} into the branch name "5" and an object into "".
	 */
	@Test
	void putNonStringRefIs400() throws Exception {
		String[] values = { "null", "5", "true", "{}" };
		for (String value : values) {
			givenBody("{\"configuration\":\"tenant\",\"ref\":" + value + "}");
			servlet.doPut(request, response);
			assertEquals("main", loader.currentRef(), "ref [" + value + "] must not have moved HEAD");
		}
		verify(response, times(values.length)).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
	}

	@Test
	void putWithoutRoleIs401() throws Exception {
		SecurityContextHolder.clearContext();
		givenBody("{\"configuration\":\"tenant\",\"ref\":\"main\"}");
		servlet.doPut(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
	}

	@Test
	void putOversizedBodyIs413() throws Exception {
		when(request.getContentLengthLong()).thenReturn(ConfigRefServlet.MAX_BODY_BYTES + 1L);
		servlet.doPut(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE), anyString());
	}
}
