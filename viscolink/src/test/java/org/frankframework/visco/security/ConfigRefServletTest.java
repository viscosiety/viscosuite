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
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.security.core.Authentication;
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

	/** Messages the reload worker actually sent, and the SecurityContext it held while sending. */
	private final BlockingQueue<Message<?>> dispatched = new LinkedBlockingQueue<>();
	private final AtomicReference<Authentication> dispatchAuth = new AtomicReference<>();

	@BeforeEach
	void setUp() throws Exception {
		servlet = new ConfigRefServlet();
		AppConstants.getInstance().setProperty(ConfigRefServlet.SECURITY_ROLES_PROPERTY, REQUIRED_ROLE);
		loader = TempGitRepo.configuredLoader(tmp, mock(IbisContext.class), "tenant");
		request = mock(HttpServletRequest.class);
		responseBody = new ByteArrayOutputStream();
		response = newResponse(responseBody);
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
				"svc", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + REQUIRED_ROLE))));
	}

	@AfterEach
	void tearDown() throws Exception {
		// The reload gate and its executor are static, so a test that left a task running would get
		// every later test answered 409 reload-in-progress. Every test that blocks the worker
		// releases it itself; this is the backstop that stops one failure from cascading.
		awaitReloadIdle();
		loader.destroy();
		SecurityContextHolder.clearContext();
		AppConstants.getInstance().remove(ConfigRefServlet.SECURITY_ROLES_PROPERTY);
	}

	/** A response mock whose body lands in {@code sink} -- one per call, so a test can drive two requests. */
	private HttpServletResponse newResponse(ByteArrayOutputStream sink) throws IOException {
		HttpServletResponse resp = mock(HttpServletResponse.class);
		// Only the happy-path / 409 tests actually write a JSON body; sendError-only tests never
		// touch getOutputStream() at all, so this stub must be lenient under strict-stub checking.
		lenient().when(resp.getOutputStream()).thenReturn(new ServletOutputStream() {
			@Override public boolean isReady() { return true; }
			@Override public void setWriteListener(WriteListener l) {}
			@Override public void write(int b) { sink.write(b); }
		});
		return resp;
	}

	/** Blocks until no reload dispatched by the servlet is queued or running. */
	private static void awaitReloadIdle() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;
		while (ConfigRefServlet.reloadInProgress() && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertFalse(ConfigRefServlet.reloadInProgress(), "a reload task was still in flight after 5s");
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
	 * Records every message the servlet's reload worker sends, plus the {@link Authentication} the
	 * worker held while sending it. The dispatch is asynchronous (see {@link ConfigRefServlet}'s
	 * javadoc: an inline bus reload would blow past the 60s nginx timeout), so tests take the
	 * message off {@link #dispatched} with a timeout rather than asserting straight after
	 * {@code doPut}. Both are recorded rather than asserted on the worker thread: an assertion
	 * failure there would be swallowed by the servlet's catch-and-log and resurface only as a
	 * missing message.
	 */
	private void recordDispatches() {
		doAnswer(invocation -> {
			dispatchAuth.set(SecurityContextHolder.getContext().getAuthentication());
			return dispatched.add(invocation.getArgument(0));
		}).when(gateway).sendAsyncMessage(any());
	}

	@Test
	void getReportsCurrentRef() throws Exception {
		when(request.getParameter("configuration")).thenReturn("tenant");
		servlet.doGet(request, response);
		JsonNode body = json();
		assertEquals("tenant", body.get("configuration").asText());
		assertEquals("main", body.get("ref").asText());
		assertTrue(body.get("isDefault").asBoolean());
		assertFalse(body.get("reloading").asBoolean());
	}

	/**
	 * 400, not the 409 below: a request with no configuration at all is a caller bug, and
	 * answering it with "no git-backed configuration named [null] registered" would read like a
	 * real -- and retryable -- instance state.
	 */
	@Test
	void getWithoutConfigurationParameterIs400() throws Exception {
		servlet.doGet(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
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
		recordDispatches();
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
		Message<?> message = dispatched.poll(5, TimeUnit.SECONDS);
		assertNotNull(message, "reload was not dispatched within 5s");
		assertEquals(Action.RELOAD.name(), message.getHeaders().get(BusMessageUtils.HEADER_PREFIX + "action"));
		assertEquals("tenant", message.getHeaders().get(BusMessageUtils.HEADER_PREFIX + BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY));

		// The elevation has to reach the bus, not just be built: HandleIbisManagerAction's
		// @RolesAllowed rejects the tenant's own role, and the bus's "on request of [...]" audit
		// logging has to still name the real caller rather than the elevated principal.
		Authentication workerAuth = dispatchAuth.get();
		assertNotNull(workerAuth, "reload worker sent with no SecurityContext at all");
		assertEquals("svc", workerAuth.getName());
		assertTrue(workerAuth.getAuthorities().stream().anyMatch(a -> "ROLE_IbisAdmin".equals(a.getAuthority())),
				"reload worker did not carry ROLE_IbisAdmin, got " + workerAuth.getAuthorities());
	}

	/**
	 * A second switch while the first one's reload is still running would have F!F digest a
	 * working tree changing underneath it -- and blocking the request until the reload finished
	 * would hit the very proxy timeout the async dispatch exists to avoid. So: 409, promptly, with
	 * HEAD untouched.
	 */
	@Test
	void putDuringAnInFlightReloadIs409AndLeavesHeadAlone() throws Exception {
		givenConsoleContextWithGateway();
		CountDownLatch dispatchStarted = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		doAnswer(invocation -> {
			dispatchStarted.countDown();
			assertTrue(releaseWorker.await(5, TimeUnit.SECONDS), "worker was never released");
			return null;
		}).when(gateway).sendAsyncMessage(any());

		givenBody("{\"configuration\":\"tenant\",\"ref\":\"assistant/demo/draft-abc123\"}");
		servlet.doPut(request, response);
		verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
		assertTrue(dispatchStarted.await(5, TimeUnit.SECONDS), "reload was not dispatched within 5s");

		try {
			ByteArrayOutputStream secondBody = new ByteArrayOutputStream();
			HttpServletResponse second = newResponse(secondBody);
			givenBody("{\"configuration\":\"tenant\",\"ref\":\"main\"}");

			servlet.doPut(request, second);

			verify(second).setStatus(HttpServletResponse.SC_CONFLICT);
			JsonNode body = new ObjectMapper().readTree(secondBody.toByteArray());
			assertEquals("reload-in-progress", body.get("code").asText());
			// Still on the branch the first PUT switched to -- the rejected switch touched nothing.
			assertEquals("assistant/demo/draft-abc123", body.get("ref").asText());
			assertEquals("assistant/demo/draft-abc123", loader.currentRef());
		} finally {
			releaseWorker.countDown();
		}
	}

	@Test
	void getReportsReloadingWhileTheReloadIsInFlight() throws Exception {
		givenConsoleContextWithGateway();
		CountDownLatch dispatchStarted = new CountDownLatch(1);
		CountDownLatch releaseWorker = new CountDownLatch(1);
		doAnswer(invocation -> {
			dispatchStarted.countDown();
			assertTrue(releaseWorker.await(5, TimeUnit.SECONDS), "worker was never released");
			return null;
		}).when(gateway).sendAsyncMessage(any());

		givenBody("{\"configuration\":\"tenant\",\"ref\":\"assistant/demo/draft-abc123\"}");
		servlet.doPut(request, response);
		assertTrue(dispatchStarted.await(5, TimeUnit.SECONDS), "reload was not dispatched within 5s");
		when(request.getParameter("configuration")).thenReturn("tenant");

		try {
			ByteArrayOutputStream duringBody = new ByteArrayOutputStream();
			servlet.doGet(request, newResponse(duringBody));
			JsonNode during = new ObjectMapper().readTree(duringBody.toByteArray());
			// The ref has already switched; only "reloading" distinguishes "settling" from "settled".
			assertEquals("assistant/demo/draft-abc123", during.get("ref").asText());
			assertTrue(during.get("reloading").asBoolean());
		} finally {
			releaseWorker.countDown();
		}
		awaitReloadIdle();

		ByteArrayOutputStream afterBody = new ByteArrayOutputStream();
		servlet.doGet(request, newResponse(afterBody));
		assertFalse(new ObjectMapper().readTree(afterBody.toByteArray()).get("reloading").asBoolean());
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
