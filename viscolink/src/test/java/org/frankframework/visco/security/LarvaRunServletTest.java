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

import org.eclipse.jgit.api.Git;
import org.frankframework.configuration.Configuration;
import org.frankframework.configuration.IbisContext;
import org.frankframework.configuration.IbisManager;
import org.frankframework.configuration.classloaders.DirectoryClassLoader;
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
		assertTrue(LarvaRunServlet.awaitIdle(5_000), "worker did not settle before teardown");
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

	/** An application context whose IbisManager knows {@code name} with the given classloader. */
	private static ApplicationContext contextWithConfiguration(String name, ClassLoader classLoader) {
		ApplicationContext ctx = mock(ApplicationContext.class);
		IbisManager manager = mock(IbisManager.class);
		Configuration configuration = mock(Configuration.class);
		when(ctx.getBean(IbisManager.class)).thenReturn(manager);
		when(manager.getConfiguration(name)).thenReturn(configuration);
		when(configuration.getClassLoader()).thenReturn(classLoader);
		return ctx;
	}

	private String rootOfOnlyRun() throws Exception {
		JsonNode list = get(null);
		JsonNode doc = get(list.get("runs").get(0).get("runId").asText());
		assertTrue(doc.get("ref").isNull());
		assertTrue(doc.get("commit").isNull());
		return doc.get("root").asText();
	}

	@Test
	void castingResolvesThroughItsDirectoryClassLoader() throws Exception {
		// A casting: configurations.other.directory=<baked>, the DirectoryClassLoader appends the
		// configuration name itself (basePath) -> <baked>/other. The GLOBAL configurations.directory
		// points elsewhere on runner images and must not be used.
		Path baked = tmp.resolve("baked-configurations");
		Files.createDirectories(baked.resolve("other/larva"));
		DirectoryClassLoader directoryLoader = new DirectoryClassLoader(getClass().getClassLoader());
		directoryLoader.setDirectory(baked.toString());
		directoryLoader.configure(mock(IbisContext.class), "other");
		assertEquals(baked.resolve("other").toFile(), directoryLoader.getDirectory(), "test setup: F!F appended the name");
		AppConstants.getInstance().setProperty("configurations.directory", tmp.resolve("configurations").toString());
		try {
			servlet = new LarvaRunServlet(fakeRunner(), req -> contextWithConfiguration("other", directoryLoader));
			post("{\"configuration\":\"other\"}");
			verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
			LarvaRunServlet.awaitIdle(5_000);
			assertEquals(baked.resolve("other/larva").toString(), runnerArgs.get()[0]);
			assertEquals(baked.resolve("other/larva").toString(), rootOfOnlyRun());
		} finally {
			AppConstants.getInstance().remove("configurations.directory");
		}
	}

	@Test
	void otherClassLoaderFallsBackToTheConfigurationsOwnDirectoryProperty() throws Exception {
		Path own = tmp.resolve("own");
		Files.createDirectories(own.resolve("other/larva"));
		AppConstants.getInstance().setProperty("configurations.other.directory", own.toString());
		AppConstants.getInstance().setProperty("configurations.directory", tmp.resolve("global").toString());
		try {
			servlet = new LarvaRunServlet(fakeRunner(), req -> contextWithConfiguration("other", getClass().getClassLoader()));
			post("{\"configuration\":\"other\"}");
			LarvaRunServlet.awaitIdle(5_000);
			assertEquals(own.resolve("other/larva").toString(), runnerArgs.get()[0], "configurations.<name>.directory wins over the global one");
		} finally {
			AppConstants.getInstance().remove("configurations.other.directory");
			AppConstants.getInstance().remove("configurations.directory");
		}
	}

	@Test
	void otherClassLoaderFallsBackToTheGlobalConfigurationsDirectoryLast() throws Exception {
		Path global = tmp.resolve("global");
		Files.createDirectories(global.resolve("other/larva"));
		AppConstants.getInstance().setProperty("configurations.directory", global.toString());
		try {
			servlet = new LarvaRunServlet(fakeRunner(), req -> contextWithConfiguration("other", getClass().getClassLoader()));
			post("{\"configuration\":\"other\"}");
			LarvaRunServlet.awaitIdle(5_000);
			assertEquals(global.resolve("other/larva").toString(), runnerArgs.get()[0]);
			assertEquals(global.resolve("other/larva").toString(), rootOfOnlyRun());
		} finally {
			AppConstants.getInstance().remove("configurations.directory");
		}
	}

	@Test
	void aConfigurationTheIbisManagerDoesNotKnowIs404NotRegisteredEvenWithADirectoryOnDisk() throws Exception {
		Path global = tmp.resolve("global");
		Files.createDirectories(global.resolve("ghost/larva"));
		AppConstants.getInstance().setProperty("configurations.directory", global.toString());
		try {
			servlet = new LarvaRunServlet(fakeRunner(), req -> contextWithConfiguration("other", getClass().getClassLoader()));
			JsonNode body = post("{\"configuration\":\"ghost\"}");
			verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
			assertEquals("configuration-not-registered", body.get("code").asText());
			assertNull(runnerArgs.get());
		} finally {
			AppConstants.getInstance().remove("configurations.directory");
		}
	}

	@Test
	void aDirectoryExecuteGetsATrailingSeparatorSoSiblingsDoNotMatch() throws Exception {
		Path larva = loader.getResourceDir().toPath().resolve("larva");
		Files.createDirectories(larva.resolve("OrdersIn-rejects"));
		Files.writeString(larva.resolve("OrdersIn-rejects/scenario01.properties"), "scenario.description=x\n");
		post("{\"configuration\":\"tenant\",\"execute\":\"OrdersIn\"}");
		LarvaRunServlet.awaitIdle(5_000);
		assertEquals(larva.resolve("OrdersIn") + File.separator, runnerArgs.get()[1]);
		assertEquals(larva.toString(), runnerArgs.get()[0], "the root itself stays exact");
	}

	@Test
	void anExecuteThatResolvesToNothingIs400() throws Exception {
		HttpServletResponse resp = newResponse(new ByteArrayOutputStream());
		givenBody("{\"configuration\":\"tenant\",\"execute\":\"OrdersOut\"}");
		servlet.doPost(request, resp);
		verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
		assertNull(runnerArgs.get(), "no run started");
	}

	@Test
	void anOversizedBodyIs413() throws Exception {
		givenBody("{\"configuration\":\"tenant\"}");
		when(request.getContentLengthLong()).thenReturn((long) LarvaRunServlet.MAX_BODY_BYTES + 1);
		servlet.doPost(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE), anyString());
		assertNull(runnerArgs.get());
	}

	@Test
	void anUninitialisedConsoleIs503() throws Exception {
		servlet = new LarvaRunServlet(fakeRunner(), req -> null);
		givenBody("{\"configuration\":\"tenant\"}");
		servlet.doPost(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_SERVICE_UNAVAILABLE), anyString());
		assertNull(runnerArgs.get());
	}

	@Test
	void aNonNumericTimeoutIs400() throws Exception {
		givenBody("{\"configuration\":\"tenant\",\"timeoutMs\":\"soon\"}");
		servlet.doPost(request, response);
		verify(response).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
		assertNull(runnerArgs.get());
	}

	@Test
	void aCloneThatMovesDuringTheRunDropsTheCommitAndSaysSo() throws Exception {
		servlet = new LarvaRunServlet((ctx, root, execute, timeoutMs, observer) -> {
			try (Git git = Git.open(tmp.resolve(TempGitRepo.CLONE_DIR).toFile())) {
				git.commit().setAllowEmpty(true).setSign(false).setMessage("moved under the run").call();
			}
			TestRunStatus status = new TestRunStatus(new LarvaConfig(), mock(LarvaTool.class));
			observer.endTestSuiteExecution(status);
			return status;
		}, req -> mock(ApplicationContext.class));
		JsonNode accepted = post("{\"configuration\":\"tenant\"}");
		LarvaRunServlet.awaitIdle(5_000);
		JsonNode doc = get(accepted.get("runId").asText());
		assertEquals("finished", doc.get("state").asText());
		assertTrue(doc.get("commit").isNull());
		assertEquals("warning", doc.get("messages").get(0).get("level").asText());
		assertEquals("clone moved during the run", doc.get("messages").get(0).get("text").asText());
	}

	@Test
	void getWhileRunningShowsPrefilledFieldsAndRunningState() throws Exception {
		blockRunner = true;
		JsonNode accepted = post("{\"configuration\":\"tenant\"}");
		String runId = accepted.get("runId").asText();

		JsonNode doc = get(runId);

		assertEquals("running", doc.get("state").asText());
		assertEquals(runId, doc.get("runId").asText());
		assertEquals("tenant", doc.get("configuration").asText());
		String root = loader.getResourceDir().toPath().resolve("larva").toString();
		assertEquals(root, doc.get("root").asText());
		assertEquals("main", doc.get("ref").asText());
		assertEquals(loader.currentCommit(), doc.get("commit").asText());
		assertNotNull(doc.get("startedAt").asText());

		release.countDown();
	}

	@Test
	void configurationWithPathSeparatorsIs400() throws Exception {
		for (String bad : List.of("../x", "a/b", "a\\b")) {
			HttpServletResponse resp = newResponse(new ByteArrayOutputStream());
			givenBody("{\"configuration\":\"" + bad.replace("\\", "\\\\") + "\"}");
			servlet.doPost(request, resp);
			verify(resp).sendError(eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
		}
		assertNull(runnerArgs.get(), "no run started");
	}
}
