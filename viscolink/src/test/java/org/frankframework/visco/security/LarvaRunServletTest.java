package org.frankframework.visco.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ReadListener;
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
import org.frankframework.larva.LarvaConfig;
import org.frankframework.larva.LarvaTool;
import org.frankframework.larva.TestRunStatus;
import org.frankframework.util.AppConstants;
import org.frankframework.visco.larva.LarvaRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LarvaRunServletTest {

	private static final String REQUIRED_ROLE = "viscoforge-tenant:bo-demo";

	@TempDir Path tmp;

	private GitClassLoader loader;
	private HttpServletRequest request;
	private HttpServletResponse response;
	private ByteArrayOutputStream responseBody;
	private final AtomicReference<String[]> runnerArgs = new AtomicReference<>();
	private final CountDownLatch release = new CountDownLatch(1);
	private volatile boolean blockRunner;

	private LarvaRunner fakeRunner() {
		return (ctx, root, execute, timeoutMs, observer) -> {
			runnerArgs.set(new String[] { root, execute, String.valueOf(timeoutMs) });
			if (blockRunner) {
				release.await(5, TimeUnit.SECONDS);
			}
			TestRunStatus status = new TestRunStatus(new LarvaConfig(), mock(LarvaTool.class));
			observer.startTestSuiteExecution(status);
			observer.endTestSuiteExecution(status);
			return status;
		};
	}

	private LarvaRunServlet servlet;

	@BeforeEach
	void setUp() throws Exception {
		LarvaRunServlet.resetForTests();
		AppConstants.getInstance().setProperty(LarvaRunServlet.SECURITY_ROLES_PROPERTY, REQUIRED_ROLE);
		AppConstants.getInstance().setProperty("dtap.stage", "DEV");
		loader = TempGitRepo.configuredLoader(tmp, mock(IbisContext.class), "tenant");
		Files.createDirectories(loader.getResourceDir().toPath().resolve("larva/OrdersIn"));
		Files.writeString(loader.getResourceDir().toPath().resolve("larva/OrdersIn/scenario01.properties"), "scenario.description=x\n");
		servlet = new LarvaRunServlet(fakeRunner(), req -> mock(ApplicationContext.class));
		request = mock(HttpServletRequest.class);
		responseBody = new ByteArrayOutputStream();
		response = newResponse(responseBody);
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
				"svc", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + REQUIRED_ROLE))));
	}

	@AfterEach
	void tearDown() throws Exception {
		release.countDown();
		LarvaRunServlet.awaitIdle(5_000);
		loader.destroy();
		SecurityContextHolder.clearContext();
		AppConstants.getInstance().remove(LarvaRunServlet.SECURITY_ROLES_PROPERTY);
		AppConstants.getInstance().remove("dtap.stage");
		LarvaRunServlet.resetForTests();
	}

	private HttpServletResponse newResponse(ByteArrayOutputStream sink) throws IOException {
		HttpServletResponse resp = mock(HttpServletResponse.class);
		lenient().when(resp.getOutputStream()).thenReturn(new ServletOutputStream() {
			@Override public boolean isReady() { return true; }
			@Override public void setWriteListener(WriteListener l) {}
			@Override public void write(int b) { sink.write(b); }
		});
		return resp;
	}

	private void givenBody(String json) throws IOException {
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

	private JsonNode post(String body) throws Exception {
		givenBody(body);
		servlet.doPost(request, response);
		return json();
	}

	private JsonNode get(String runId) throws Exception {
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		HttpServletResponse resp = newResponse(sink);
		HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getPathInfo()).thenReturn(runId == null ? null : "/" + runId);
		servlet.doGet(req, resp);
		return new ObjectMapper().readTree(sink.toByteArray());
	}

	@Test
	void postWithoutRoleIs401() throws Exception {
		SecurityContextHolder.clearContext();
		givenBody("{\"configuration\":\"tenant\"}");
		servlet.doPost(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
	}

	@Test
	void postRunsAllScenariosUnderTheGitRootAndReportsRefAndCommit() throws Exception {
		JsonNode accepted = post("{\"configuration\":\"tenant\"}");
		verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
		String runId = accepted.get("runId").asText();
		assertEquals("running", accepted.get("state").asText());
		LarvaRunServlet.awaitIdle(5_000);

		JsonNode doc = get(runId);
		assertEquals("finished", doc.get("state").asText());
		assertEquals("tenant", doc.get("configuration").asText());
		assertEquals("", doc.get("execute").asText());
		String root = loader.getResourceDir().toPath().resolve("larva").toString();
		assertEquals(root, doc.get("root").asText());
		assertEquals("main", doc.get("ref").asText());
		assertEquals(loader.currentCommit(), doc.get("commit").asText());
		assertNotNull(doc.get("startedAt").asText());
		assertNotNull(doc.get("finishedAt").asText());
		assertTrue(doc.get("durationMs").asLong() >= 0);
		assertEquals(root, runnerArgs.get()[0]);
		assertEquals(root, runnerArgs.get()[1], "empty execute = the root itself");
		assertEquals("120000", runnerArgs.get()[2], "default timeout");
	}

	@Test
	void executeIsResolvedUnderTheRootAndTimeoutIsBounded() throws Exception {
		post("{\"configuration\":\"tenant\",\"execute\":\"OrdersIn/scenario01.properties\",\"timeoutMs\":5}");
		LarvaRunServlet.awaitIdle(5_000);
		String root = loader.getResourceDir().toPath().resolve("larva").toString();
		assertEquals(new File(root, "OrdersIn/scenario01.properties").getPath(), runnerArgs.get()[1]);
		assertEquals("1000", runnerArgs.get()[2], "timeout floor");
	}

	@Test
	void executeEscapingTheRootIs400() throws Exception {
		for (String bad : List.of("../Configuration.xml", "/etc/passwd", "a\\b", "OrdersIn/../../x")) {
			HttpServletResponse resp = newResponse(new ByteArrayOutputStream());
			givenBody("{\"configuration\":\"tenant\",\"execute\":\"" + bad.replace("\\", "\\\\") + "\"}");
			servlet.doPost(request, resp);
			verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
		}
		assertNull(runnerArgs.get(), "no run started");
	}

	@Test
	void unknownConfigurationIs404WithCode() throws Exception {
		JsonNode body = post("{\"configuration\":\"nope\"}");
		verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
		assertEquals("configuration-not-registered", body.get("code").asText());
	}

	@Test
	void missingLarvaRootIs404WithCodeAndRoot() throws Exception {
		Files.walk(loader.getResourceDir().toPath().resolve("larva")).sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		JsonNode body = post("{\"configuration\":\"tenant\"}");
		verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
		assertEquals("no-larva-root", body.get("code").asText());
		assertTrue(body.get("root").asText().endsWith("larva"));
	}

	@Test
	void productionStageIs409() throws Exception {
		AppConstants.getInstance().setProperty("dtap.stage", "prd");
		JsonNode body = post("{\"configuration\":\"tenant\"}");
		verify(response).setStatus(HttpServletResponse.SC_CONFLICT);
		assertEquals("production-instance", body.get("code").asText());
		assertNull(runnerArgs.get());
	}

	@Test
	void secondPostWhileRunningIs409WithTheRunningId() throws Exception {
		blockRunner = true;
		JsonNode first = post("{\"configuration\":\"tenant\"}");
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		HttpServletResponse resp = newResponse(sink);
		givenBody("{\"configuration\":\"tenant\"}");
		servlet.doPost(request, resp);
		verify(resp).setStatus(HttpServletResponse.SC_CONFLICT);
		JsonNode second = new ObjectMapper().readTree(sink.toByteArray());
		assertEquals("run-in-progress", second.get("code").asText());
		assertEquals(first.get("runId").asText(), second.get("runId").asText());
		release.countDown();
	}

	@Test
	void unknownRunIs404AndListingShowsRecentRuns() throws Exception {
		JsonNode missing = get("nope");
		assertEquals("run-not-found", missing.get("code").asText());
		JsonNode accepted = post("{\"configuration\":\"tenant\"}");
		LarvaRunServlet.awaitIdle(5_000);
		JsonNode list = get(null);
		assertEquals(1, list.get("runs").size());
		assertEquals(accepted.get("runId").asText(), list.get("runs").get(0).get("runId").asText());
		assertEquals("finished", list.get("runs").get(0).get("state").asText());
	}

	@Test
	void aRunnerExceptionEndsTheRunAsFailedWithASanitisedError() throws Exception {
		servlet = new LarvaRunServlet((ctx, root, execute, timeoutMs, observer) -> {
			throw new IllegalStateException("boom\nat some.Stack(Frame.java:1)");
		}, req -> mock(ApplicationContext.class));
		JsonNode accepted = post("{\"configuration\":\"tenant\"}");
		LarvaRunServlet.awaitIdle(5_000);
		JsonNode doc = get(accepted.get("runId").asText());
		assertEquals("failed", doc.get("state").asText());
		assertEquals("boom", doc.get("error").asText());
	}

	@Test
	void directoryClassLoaderFallsBackToConfigurationsDirectory() throws Exception {
		Path baked = tmp.resolve("baked");
		Files.createDirectories(baked.resolve("other/larva"));
		AppConstants.getInstance().setProperty("configurations.directory", baked.toString());
		try {
			post("{\"configuration\":\"other\"}");
			LarvaRunServlet.awaitIdle(5_000);
			assertEquals(baked.resolve("other/larva").toString(), runnerArgs.get()[0]);
			JsonNode list = get(null);
			JsonNode doc = get(list.get("runs").get(0).get("runId").asText());
			assertTrue(doc.get("ref").isNull());
			assertTrue(doc.get("commit").isNull());
		} finally {
			AppConstants.getInstance().remove("configurations.directory");
		}
	}
}
