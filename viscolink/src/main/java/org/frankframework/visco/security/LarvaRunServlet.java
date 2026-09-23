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

import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;

import com.viscosiety.classloaders.GitClassLoader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.frankframework.configuration.Configuration;
import org.frankframework.configuration.IbisContext;
import org.frankframework.configuration.IbisManager;
import org.frankframework.configuration.classloaders.DirectoryClassLoader;
import org.frankframework.larva.Scenario;
import org.frankframework.larva.Step;
import org.frankframework.larva.TestRunStatus;
import org.frankframework.larva.output.TestExecutionObserver;
import org.frankframework.lifecycle.FrankApplicationInitializer;
import org.frankframework.lifecycle.IbisInitializer;
import org.frankframework.util.AppConstants;
import org.frankframework.visco.larva.JsonTestExecutionObserver;
import org.frankframework.visco.larva.LarvaRunDocument;
import org.frankframework.visco.larva.LarvaRunner;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

/**
 * Runs Larva scenarios for one configuration and serves the results as JSON -- the runtime half
 * of viscoFoundry's Larva tests feature (spec 2026-09-23-larva-tests-design §5).
 *
 * <p>{@code POST /api-service/larva/runs} {@code {configuration, execute?, timeoutMs?}} starts a
 * run on a single worker thread and answers {@code 202 {runId, state:"running"}};
 * {@code GET /api-service/larva/runs/{runId}} returns the run's document ({@link LarvaRunDocument}),
 * {@code GET /api-service/larva/runs} lists the recent ones.</p>
 *
 * <p>The scenario root is {@code <resource dir>/larva} of the configuration's
 * {@link GitClassLoader} clone (workspace instances), {@code <directory>/larva} of its F!F
 * {@link DirectoryClassLoader} (casting images: {@code configurations.<name>.directory} plus the
 * name the classloader appends itself), else {@code <configurations.<name>.directory>/<name>/larva},
 * else {@code <configurations.directory>/<name>/larva} -- see {@link #resolveRoot}. The servlet
 * never pulls and never reloads: it runs
 * whatever is on disk at the clone's current ref and reports that ref and commit, so the portal can
 * tell when the tests on the instance are behind their branch (the sync contract is
 * apply_configuration / run_draft, exactly as for configuration files -- pulling here without a
 * reload would let the classloader serve resources newer than the loaded configuration).</p>
 *
 * <p>Lock order: no {@link GitClassLoader} monitor is ever taken here (only the lock-free
 * {@code currentRef()/currentCommit()/getResourceDir()} accessors), and {@link #RUN_LOCK} is never
 * held while Larva runs -- it only guards the accept-or-refuse decision, like
 * {@code ConfigRefServlet.SWITCH_LOCK}.</p>
 */
@IbisInitializer
public class LarvaRunServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	static final String SECURITY_ROLES_PROPERTY = "servlet.larvaRun.securityRoles";
	static final int MAX_BODY_BYTES = 8 * 1024;
	static final long DEFAULT_TIMEOUT_MS = 120_000L;
	static final long MIN_TIMEOUT_MS = 1_000L;
	static final long MAX_TIMEOUT_MS = 600_000L;
	static final int KEEP_RUNS = 20;

	static final String CODE_NOT_REGISTERED = "configuration-not-registered";
	static final String CODE_NO_ROOT = "no-larva-root";
	static final String CODE_RUN_IN_PROGRESS = "run-in-progress";
	static final String CODE_PRODUCTION = "production-instance";
	static final String CODE_RUN_NOT_FOUND = "run-not-found";

	/** Larva's own gate (LarvaServlet requires IbisTester); scenarios calling adapters need nothing more. */
	private static final String[] ELEVATED_ROLES = { "IbisTester" };

	private static final Logger log = LogManager.getLogger(LarvaRunServlet.class);

	/** Single-threaded on purpose: Larva scenarios share adapters; two suites at once would interleave. */
	private static final ExecutorService RUN_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "larva-run");
		thread.setDaemon(true);
		return thread;
	});

	private static final ReentrantLock RUN_LOCK = new ReentrantLock();
	private static volatile Future<?> inFlight;
	private static volatile String inFlightRunId;

	/** Recent run documents, oldest first; eldest evicted past {@link #KEEP_RUNS}. Guarded by itself. */
	private static final Map<String, LarvaRunDocument> RUNS = new LinkedHashMap<>() {
		@Serial
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, LarvaRunDocument> eldest) {
			return size() > KEEP_RUNS;
		}
	};

	private final transient LarvaRunner runner;
	private final transient Function<HttpServletRequest, ApplicationContext> contextResolver;

	/** Container constructor: the real Larva driver, application context from the F!F initializer. */
	public LarvaRunServlet() {
		this(LarvaRunner.larvaTool(), req -> {
			IbisContext ibisContext = FrankApplicationInitializer.getIbisContext(req.getServletContext());
			return ibisContext == null ? null : ibisContext.getApplicationContext();
		});
	}

	LarvaRunServlet(LarvaRunner runner, Function<HttpServletRequest, ApplicationContext> contextResolver) {
		this.runner = runner;
		this.contextResolver = contextResolver;
	}

	@Override
	public String getName() {
		return "larvaRun";
	}

	@Override
	public String getUrlMapping() {
		return "/api-service/larva/runs/*";
	}

	@Override
	protected String securityRolesProperty() {
		return SECURITY_ROLES_PROPERTY;
	}

	@Override
	protected String[] elevatedRoles() {
		return ELEVATED_ROLES.clone();
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (rejectUnauthorized(resp)) {
			return;
		}
		String pathInfo = req.getPathInfo();
		String runId = pathInfo == null || pathInfo.equals("/") ? null : pathInfo.substring(1);
		if (runId == null) {
			List<Map<String, Object>> runs = new ArrayList<>();
			synchronized (RUNS) {
				for (LarvaRunDocument doc : RUNS.values()) {
					Map<String, Object> row = new LinkedHashMap<>();
					// The worker thread mutates state/startedAt while it runs (via the
					// SynchronizedObserver / runOne's own synchronized(doc) blocks) -- read this
					// row's fields under the same monitor rather than let Jackson tear a half-written
					// field, exactly like the single-document path below.
					synchronized (doc) {
						row.put("runId", doc.runId);
						row.put("state", doc.state);
						row.put("startedAt", doc.startedAt);
						row.put("configuration", doc.configuration);
						row.put("execute", doc.execute);
					}
					runs.add(row);
				}
			}
			writeJson(resp, Map.of("runs", runs));
			return;
		}
		LarvaRunDocument doc;
		synchronized (RUNS) {
			doc = RUNS.get(runId);
		}
		if (doc == null) {
			sendCode(resp, HttpServletResponse.SC_NOT_FOUND, CODE_RUN_NOT_FOUND, Map.of("runId", runId));
			return;
		}
		// Serialise to bytes under the document's own monitor (the worker mutates it while it
		// runs -- see SynchronizedObserver), then write those bytes with the lock released: holding
		// the monitor across the actual response I/O would let a slow poller block every observer
		// callback on the worker thread for as long as the write takes.
		byte[] body;
		synchronized (doc) {
			body = JSON.writeValueAsBytes(doc);
		}
		resp.setContentType("application/json");
		resp.setCharacterEncoding("UTF-8");
		resp.getOutputStream().write(body);
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (rejectUnauthorized(resp)) {
			return;
		}
		if (req.getContentLengthLong() > MAX_BODY_BYTES) {
			resp.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "request body exceeds " + MAX_BODY_BYTES + " bytes");
			return;
		}
		JsonNode body;
		try {
			body = JSON.readTree(req.getInputStream());
		} catch (IOException e) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "request body is not valid JSON");
			return;
		}
		String configuration = textOrNull(body, "configuration");
		if (configuration == null || configuration.isBlank()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "configuration is required");
			return;
		}
		if (!isSafeConfigurationName(configuration)) {
			// resolveRoot's property fallbacks use this value as a file path segment
			// (new File(configurationsDirectory, configuration)) -- a plain name only, never a
			// path: no separators, no ".."/"." component.
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "configuration must be a plain name");
			return;
		}
		String execute = body.hasNonNull("execute") ? textOrNull(body, "execute") : "";
		if (execute == null || !isSafeExecute(execute)) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "execute must be a relative path under the larva root");
			return;
		}
		long timeoutMs = DEFAULT_TIMEOUT_MS;
		if (body.hasNonNull("timeoutMs")) {
			if (!body.get("timeoutMs").isNumber()) {
				resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "timeoutMs must be a number");
				return;
			}
			timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, body.get("timeoutMs").asLong()));
		}

		if ("PRD".equalsIgnoreCase(AppConstants.getInstance().getProperty("dtap.stage"))) {
			sendCode(resp, HttpServletResponse.SC_CONFLICT, CODE_PRODUCTION,
					Map.of("error", "Larva scenarios are not run on a production instance."));
			return;
		}

		// Before root resolution: a non-git configuration is only found through the IbisManager.
		ApplicationContext applicationContext = contextResolver.apply(req);
		if (applicationContext == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "console not initialised -- retry");
			return;
		}
		Root root = resolveRoot(configuration, applicationContext);
		if (root == null) {
			sendCode(resp, HttpServletResponse.SC_NOT_FOUND, CODE_NOT_REGISTERED, Map.of("configuration", configuration));
			return;
		}
		if (root.directory == null) {
			sendCode(resp, HttpServletResponse.SC_NOT_FOUND, CODE_NO_ROOT, Map.of("configuration", configuration));
			return;
		}
		if (!root.directory.isDirectory()) {
			sendCode(resp, HttpServletResponse.SC_NOT_FOUND, CODE_NO_ROOT,
					Map.of("configuration", configuration, "root", root.directory.getPath()));
			return;
		}
		File target = execute.isEmpty() ? root.directory : new File(root.directory, execute);
		Path rootPath = root.directory.toPath().toAbsolutePath().normalize();
		Path targetPath = target.toPath().toAbsolutePath().normalize();
		if (!targetPath.startsWith(rootPath)) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "execute must stay under the larva root");
			return;
		}
		if (!Files.exists(targetPath)) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "execute does not exist under the larva root");
			return;
		}
		String executeAbsolute = targetPath.toString();
		if (!targetPath.equals(rootPath) && Files.isDirectory(targetPath)) {
			// Larva selects a directory with startsWith(execute): without the separator
			// "OrdersIn" would also run "OrdersIn-rejects/...". The root itself stays exact --
			// Larva compares it with equals() against the active scenarios directory.
			executeAbsolute += File.separator;
		}

		String callerName = SecurityContextHolder.getContext().getAuthentication().getName();

		if (!RUN_LOCK.tryLock()) {
			sendRunInProgress(resp);
			return;
		}
		try {
			if (runInProgress()) {
				sendRunInProgress(resp);
				return;
			}
			LarvaRunDocument doc = new LarvaRunDocument();
			doc.runId = UUID.randomUUID().toString();
			doc.configuration = configuration;
			doc.execute = execute;
			doc.root = root.directory.getPath();
			doc.ref = root.ref;
			doc.commit = root.commit;
			doc.startedAt = Instant.now().toString();
			// Captured now, not re-read off doc after submit: a fast (e.g. fake, in-process) runner
			// can finish and flip doc.state to "finished"/"failed" before this response is written,
			// which would otherwise make the 202 body lie about the run's state at submit time.
			String initialState = doc.state;
			synchronized (RUNS) {
				RUNS.put(doc.runId, doc);
			}
			TestExecutionObserver observer = new SynchronizedObserver(doc, new JsonTestExecutionObserver(doc));
			String executeTarget = executeAbsolute;
			String rootAbsolute = rootPath.toString();
			long timeout = timeoutMs;
			GitClassLoader clone = root.gitLoader;
			inFlightRunId = doc.runId;
			try {
				inFlight = RUN_EXECUTOR.submit(() -> runOne(applicationContext, rootAbsolute, executeTarget, timeout, observer, doc, callerName, clone));
			} catch (RuntimeException e) {
				// RejectedExecutionException (executor shut down / queue refused) -- same reasoning
				// as ConfigRefServlet's reload-submit guard: nothing is coming for this run, so it
				// must not linger in RUNS or hold the in-flight gate closed.
				logBusFailure("larva run submit", e);
				synchronized (RUNS) {
					RUNS.remove(doc.runId);
				}
				inFlightRunId = null;
				Map<String, Object> error = new LinkedHashMap<>();
				error.put("error", "the Larva run could not be dispatched: " + sanitizedReason(e));
				resp.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
				writeJson(resp, error);
				return;
			}
			resp.setStatus(HttpServletResponse.SC_ACCEPTED);
			writeJson(resp, Map.of("runId", doc.runId, "state", initialState));
		} finally {
			RUN_LOCK.unlock();
		}
	}

	private void runOne(ApplicationContext applicationContext, String root, String execute, long timeoutMs,
			TestExecutionObserver observer, LarvaRunDocument doc, String callerName, GitClassLoader clone) {
		long started = System.currentTimeMillis();
		try {
			SecurityContext elevated = SecurityContextHolder.createEmptyContext();
			List<SimpleGrantedAuthority> authorities = Arrays.stream(ELEVATED_ROLES)
					.map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
					.toList();
			elevated.setAuthentication(new PreAuthenticatedAuthenticationToken(callerName, "n/a", authorities));
			SecurityContextHolder.setContext(elevated);

			runner.run(applicationContext, root, execute, timeoutMs, observer);
			synchronized (doc) {
				doc.state = LarvaRunDocument.STATE_FINISHED;
			}
		} catch (Throwable t) {
			log.warn("larva run [{}] for configuration [{}] failed", doc.runId, doc.configuration, t);
			synchronized (doc) {
				doc.state = LarvaRunDocument.STATE_FAILED;
				doc.error = t instanceof Exception e ? sanitizedReason(e) : t.getClass().getSimpleName();
			}
		} finally {
			// Lock-free re-read (no loader monitor): an apply_configuration / run_draft during the
			// run moves HEAD under Larva's feet, so the start commit no longer describes what ran.
			String endCommit = clone == null ? null : clone.currentCommit();
			synchronized (doc) {
				if (clone != null && doc.commit != null && !doc.commit.equals(endCommit)) {
					doc.addMessage("warning", "clone moved during the run");
					doc.commit = null;
				}
				doc.finishedAt = Instant.now().toString();
				doc.durationMs = System.currentTimeMillis() - started;
				doc.clipToBudget();
			}
			SecurityContextHolder.clearContext();
			inFlightRunId = null;
			inFlight = null;
		}
	}

	/** {@code directory} null = the configuration is registered but no directory could be derived. */
	private record Root(File directory, String ref, String commit, GitClassLoader gitLoader) {

		static Root git(GitClassLoader loader) {
			return new Root(new File(loader.getResourceDir(), "larva"), loader.currentRef(), loader.currentCommit(), loader);
		}

		static Root directory(File directory) {
			return new Root(directory, null, null, null);
		}
	}

	/**
	 * Resolves the configuration's scenario root through its REAL classloader, in this order:
	 * <ol>
	 *   <li>the {@link GitClassLoader} registry (works even before the IbisManager lists the
	 *   configuration): {@code <resource dir>/larva} with ref + commit;</li>
	 *   <li>the configuration's classloader from the {@link IbisManager}: a {@link GitClassLoader}
	 *   as above, a {@link DirectoryClassLoader} -> {@code <getDirectory()>/larva} (the directory
	 *   already includes the base path, i.e. the configuration name a casting's
	 *   {@code configurations.<name>.directory=/opt/frank/baked-configurations} relies on);</li>
	 *   <li>any other classloader: {@code <configurations.<name>.directory>/<name>/larva}, else
	 *   {@code <configurations.directory>/<name>/larva}, else a root without directory.</li>
	 * </ol>
	 * Null when neither the registry nor the IbisManager knows the configuration.
	 */
	private static Root resolveRoot(String configuration, ApplicationContext applicationContext) {
		GitClassLoader registered = GitClassLoader.lookup(configuration);
		if (registered != null && registered.getResourceDir() != null) {
			return Root.git(registered);
		}
		Configuration loaded = findConfiguration(applicationContext, configuration);
		if (loaded == null) {
			return null;
		}
		ClassLoader classLoader = loaded.getClassLoader();
		if (classLoader instanceof GitClassLoader git && git.getResourceDir() != null) {
			return Root.git(git);
		}
		if (classLoader instanceof DirectoryClassLoader directoryLoader && directoryLoader.getDirectory() != null) {
			return Root.directory(new File(directoryLoader.getDirectory(), "larva"));
		}
		AppConstants appConstants = AppConstants.getInstance();
		for (String property : List.of("configurations." + configuration + ".directory", "configurations.directory")) {
			String base = appConstants.getProperty(property);
			if (base != null && !base.isBlank()) {
				return Root.directory(new File(new File(base, configuration), "larva"));
			}
		}
		return Root.directory(null);
	}

	/** The named configuration from the first {@link IbisManager} up the context hierarchy, or null. */
	private static Configuration findConfiguration(ApplicationContext applicationContext, String configuration) {
		for (ApplicationContext ctx = applicationContext; ctx != null; ctx = ctx.getParent()) {
			IbisManager manager;
			try {
				manager = ctx.getBean(IbisManager.class);
			} catch (BeansException e) {
				continue;
			}
			if (manager == null) {
				continue;
			}
			try {
				return manager.getConfiguration(configuration);
			} catch (RuntimeException e) {
				// IbisManager keeps a plain ArrayList: a concurrent (re)load can make the stream
				// throw. Treat it as "not registered right now"; the caller retries.
				log.debug("could not look up configuration [{}] in the IbisManager", configuration, e);
				return null;
			}
		}
		return null;
	}

	static boolean isSafeExecute(String execute) {
		if (execute.isEmpty()) {
			return true;
		}
		if (execute.startsWith("/") || execute.contains("\\") || execute.indexOf('\0') >= 0) {
			return false;
		}
		for (String segment : execute.split("/")) {
			if (segment.isEmpty() || segment.equals("..") || segment.equals(".")) {
				return false;
			}
		}
		return true;
	}

	/**
	 * A plain name, not a path: {@link #resolveRoot} uses it as a single file-path segment
	 * ({@code new File(configurationsDirectory, configuration)}) for the property fallbacks, so unlike {@link #isSafeExecute} no separator is tolerated at all -- {@code "a/b"}
	 * would still escape one level, exactly like {@code ".."} would.
	 */
	static boolean isSafeConfigurationName(String configuration) {
		if (configuration.isEmpty() || configuration.equals(".") || configuration.equals("..")) {
			return false;
		}
		return configuration.indexOf('/') < 0 && configuration.indexOf('\\') < 0 && configuration.indexOf('\0') < 0;
	}

	static boolean runInProgress() {
		Future<?> current = inFlight;
		return current != null && !current.isDone();
	}

	private void sendRunInProgress(HttpServletResponse resp) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", "a Larva run is already in progress on this instance");
		body.put("runId", inFlightRunId);
		sendCode(resp, HttpServletResponse.SC_CONFLICT, CODE_RUN_IN_PROGRESS, body);
	}

	private void sendCode(HttpServletResponse resp, int status, String code, Map<String, ?> fields) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>(fields);
		body.put("code", code);
		resp.setStatus(status);
		writeJson(resp, body);
	}

	private static String textOrNull(JsonNode body, String field) {
		JsonNode node = body.get(field);
		return node != null && node.isTextual() ? node.asText() : null;
	}

	/** Test hook: blocks until no run is queued or running, or the deadline passes. Returns whether it settled. */
	static boolean awaitIdle(long millis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + millis;
		while (runInProgress() && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		return !runInProgress();
	}

	/** Test hook: forgets recorded runs and the in-flight marker. */
	static void resetForTests() {
		synchronized (RUNS) {
			RUNS.clear();
		}
		inFlight = null;
		inFlightRunId = null;
	}

	/**
	 * Wraps {@link JsonTestExecutionObserver} so every callback runs under {@code synchronized(doc)}
	 * -- the same monitor {@link #doGet} takes to read the document. Without this, the worker
	 * thread's unsynchronized list writes ({@code document.scenarios}/{@code owner.steps}/
	 * {@code document.messages}) race a concurrent GET's Jackson serialisation of those same lists
	 * (ConcurrentModificationException, no happens-before edge between the two threads). Task 2's
	 * {@link JsonTestExecutionObserver} itself stays untouched -- it is not thread-safe by design,
	 * a single caller assumed; this decorator is what makes that assumption hold for this servlet's
	 * caller (the worker thread) against this servlet's reader (a concurrent GET).
	 */
	private static final class SynchronizedObserver implements TestExecutionObserver {

		private final LarvaRunDocument doc;
		private final TestExecutionObserver delegate;

		SynchronizedObserver(LarvaRunDocument doc, TestExecutionObserver delegate) {
			this.doc = doc;
			this.delegate = delegate;
		}

		@Override
		public void startTestSuiteExecution(TestRunStatus testRunStatus) {
			synchronized (doc) {
				delegate.startTestSuiteExecution(testRunStatus);
			}
		}

		@Override
		public void endTestSuiteExecution(TestRunStatus testRunStatus) {
			synchronized (doc) {
				delegate.endTestSuiteExecution(testRunStatus);
			}
		}

		@Override
		public void executionOverview(TestRunStatus testRunStatus, long executionTime) {
			synchronized (doc) {
				delegate.executionOverview(testRunStatus, executionTime);
			}
		}

		@Override
		public void startScenario(TestRunStatus testRunStatus, Scenario scenario) {
			synchronized (doc) {
				delegate.startScenario(testRunStatus, scenario);
			}
		}

		@Override
		public void finishScenario(TestRunStatus testRunStatus, Scenario scenario, int scenarioResult, String scenarioResultMessage) {
			synchronized (doc) {
				delegate.finishScenario(testRunStatus, scenario, scenarioResult, scenarioResultMessage);
			}
		}

		@Override
		public void startStep(TestRunStatus testRunStatus, Scenario scenario, Step step) {
			synchronized (doc) {
				delegate.startStep(testRunStatus, scenario, step);
			}
		}

		@Override
		public void finishStep(TestRunStatus testRunStatus, Scenario scenario, Step step, int stepResult, String stepResultMessage) {
			synchronized (doc) {
				delegate.finishStep(testRunStatus, scenario, step, stepResult, stepResultMessage);
			}
		}

		@Override
		public void stepMessage(Scenario scenario, Step step, String description, String stepMessage) {
			synchronized (doc) {
				delegate.stepMessage(scenario, step, description, stepMessage);
			}
		}

		@Override
		public void stepMessageSuccess(Scenario scenario, Step step, String description, String stepResultMessage,
				String stepResultMessagePreparedForDiff) {
			synchronized (doc) {
				delegate.stepMessageSuccess(scenario, step, description, stepResultMessage, stepResultMessagePreparedForDiff);
			}
		}

		@Override
		public void stepMessageFailed(Scenario scenario, Step step, String description, String stepExpectedResultMessage,
				String stepExpectedResultMessagePreparedForDiff, String stepActualResultMessage, String stepActualResultMessagePreparedForDiff) {
			synchronized (doc) {
				delegate.stepMessageFailed(scenario, step, description, stepExpectedResultMessage,
						stepExpectedResultMessagePreparedForDiff, stepActualResultMessage, stepActualResultMessagePreparedForDiff);
			}
		}

		@Override
		public void messageError(String description, String messageError) {
			synchronized (doc) {
				delegate.messageError(description, messageError);
			}
		}
	}
}
