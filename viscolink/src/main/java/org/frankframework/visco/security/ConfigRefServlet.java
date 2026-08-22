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

import java.io.IOException;
import java.io.Serial;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import com.fasterxml.jackson.databind.JsonNode;

import com.viscosiety.classloaders.GitClassLoader;

import org.frankframework.lifecycle.IbisInitializer;
import org.frankframework.management.Action;
import org.frankframework.management.bus.BusMessageUtils;
import org.frankframework.management.bus.BusTopic;
import org.frankframework.management.bus.OutboundGateway;
import org.frankframework.management.bus.message.RequestMessageBuilder;

/**
 * Bearer-JWT-only "which branch is this instance running" endpoint (spec
 * docs/superpowers/specs/2026-08-22-run-draft-on-instance-design.md §3.2, viscoFoundry repo).
 * GET reports the checked-out ref of a git-backed configuration; PUT switches it
 * ({@link GitClassLoader#checkout(String)}) and reloads. The portal uses it to run an assistant
 * draft branch on a non-production instance before merging, and to return the instance to its
 * default branch on merge/discard.
 *
 * <p>Reaches the classloader through {@link GitClassLoader#lookup(String)} -- a static registry
 * -- rather than a console bean: the classloader lives in the app context, not the console
 * context this servlet family otherwise resolves beans from (see
 * {@link AbstractBearerServiceServlet#lookupConsoleBean}).</p>
 *
 * <p>Moving HEAD ({@code checkout}) is not itself a configuration reload -- {@code
 * AbstractClassLoader.reload()} only evicts this classloader's cached {@code AppConstants}. The
 * real reload is {@code IbisContext.reload(configurationName)}, reached like
 * {@link ReloadConfigurationServlet} reaches {@code IbisContext.reload()} for all configurations:
 * a management-bus RELOAD dispatch. The difference is the action's configuration-name header --
 * RELOAD with a configuration name reaches {@code IbisContext.reload(name)}; only the
 * {@code *ALL*} sentinel (what {@code Action.FULLRELOAD} sends instead) is the silent no-op.</p>
 *
 * <p><b>The dispatch is genuinely asynchronous, and has to be.</b> "Async gateway" names the
 * message pattern (no reply), not the threading: the local bus channel has no executor, so
 * {@code sendAsyncMessage} runs {@code IbisContext.reload} <em>inline on the calling thread</em>
 * and only returns once the whole configuration has been unloaded and loaded again. That is
 * minutes on a real configuration, and nginx in front of the instance gives up at 60s -- the
 * caller would see a gateway timeout for a reload that is in fact proceeding fine. So the RELOAD
 * goes to {@link #RELOAD_EXECUTOR} and the response is a {@code 202 Accepted} carrying
 * {@code "reloading": true}; the portal confirms completion by polling GET, not by this response.
 * That also rules out {@code FrankApiService} as the dispatch route (what
 * {@link ReloadConfigurationServlet} uses): it transitively resolves the console's
 * <em>session-scoped</em> clientSession bean, which needs a request bound to the calling thread --
 * impossible on a background worker outliving the request. {@link OutboundGateway} is the same bus
 * with no such binding, and is what {@link AdapterControlServlet} already dispatches IBISACTION
 * through.</p>
 *
 * <p><b>Lock order: never hold a {@link GitClassLoader} monitor while calling into the bus or
 * {@code IbisContext}.</b> F!F takes those two locks in exactly that order -- {@code
 * IbisContext.reload(String)} is synchronized and calls the synchronized {@code
 * GitClassLoader.reload()} from inside it -- so a thread holding the classloader monitor while
 * waiting on an inline bus reload inverts the order, and any concurrent per-configuration reload
 * (the console's own reload button, {@code CheckReloadJob}, {@code ReloadSender}) deadlocks both
 * threads permanently. The reload worker therefore takes no loader monitor at all; the pull inside
 * the reload is already serialised by {@code GitClassLoader.reload()} being synchronized.</p>
 *
 * <p>What this servlet serialises instead is its own work, with locks it fully owns and never
 * holds across a call into F!F:</p>
 * <ul>
 * <li>{@link #SWITCH_LOCK} makes one checkout sequence (checkout, subdir check, revert, dispatch)
 * atomic against another. It is taken with {@code tryLock()}, never blockingly: a request thread
 * that cannot have it immediately answers 409 rather than parking.</li>
 * <li>{@link #inFlightReload} rejects a ref switch while a reload dispatched here is still queued
 * or running -- checking out mid-reload would have F!F digest a tree that changed underneath it,
 * and blocking the second PUT until the reload finished would hit the same 60s proxy timeout the
 * async dispatch exists to avoid. GET reports the same flag as {@code "reloading"} so a poller can
 * tell "still reloading" from "settled".</li>
 * </ul>
 */
@IbisInitializer
public class ConfigRefServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	static final String SECURITY_ROLES_PROPERTY = "servlet.configRef.securityRoles";

	/** Two short strings; anything larger is not a legitimate request. */
	static final int MAX_BODY_BYTES = 8 * 1024;

	/** JSON {@code code} on the "this configuration is not in the registry" 409 -- see {@link #sendNotRegistered}. */
	static final String CODE_NOT_REGISTERED = "configuration-not-registered";

	/** JSON {@code code} on the "a reload or another switch is already running" 409 -- see {@link #sendReloadInProgress}. */
	static final String CODE_RELOAD_IN_PROGRESS = "reload-in-progress";

	/**
	 * Satisfies {@code HandleIbisManagerAction}'s {@code @RolesAllowed} for RELOAD. A field rather
	 * than a literal in {@link #elevatedRoles()} because {@link #dispatchReload} builds the worker
	 * thread's authorities from the same list without an instance to ask.
	 */
	private static final String[] ELEVATED_ROLES = { "IbisAdmin" };

	private static final Logger log = LogManager.getLogger(ConfigRefServlet.class);

	/**
	 * Carries the RELOAD dispatch off the request thread (see class javadoc). Single-threaded on
	 * purpose: it makes concurrent PUTs queue rather than reload the same configuration twice at
	 * once. Daemon threads so a shutting-down container is never held open by a queued reload --
	 * the checkout is already committed to disk and a fresh boot loads it anyway.
	 */
	private static final ExecutorService RELOAD_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "configRef-reload");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * Guards one ref-switch sequence against another (see class javadoc). Static because it guards
	 * this servlet's own single-flight behaviour, not any one classloader -- and deliberately NOT a
	 * classloader monitor, which must never be held across the dispatch.
	 */
	private static final ReentrantLock SWITCH_LOCK = new ReentrantLock();

	/**
	 * The reload dispatched by the most recent successful PUT, or null once no reload has been
	 * dispatched since the last one finished. Written under {@link #SWITCH_LOCK} by the request
	 * thread and cleared by the worker itself; {@code volatile} so a poller on any thread sees the
	 * change. Read through {@link #reloadInProgress()}, never directly.
	 */
	private static volatile Future<?> inFlightReload;

	@Override
	public String getName() {
		return "configRef";
	}

	@Override
	public String getUrlMapping() {
		return "/api-service/configurations/ref";
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
		String configuration = req.getParameter("configuration");
		if (configuration == null || configuration.isBlank()) {
			// A malformed request, not a statement about any configuration -- answering it with the
			// 409 below would report "no git-backed configuration named [null] registered", which
			// reads like a real (and retryable) instance state rather than a caller bug.
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "configuration is required");
			return;
		}
		GitClassLoader loader = GitClassLoader.lookup(configuration);
		if (loader == null) {
			sendNotRegistered(resp, configuration);
			return;
		}
		Map<String, Object> out = state(configuration, loader);
		// The ref is already switched while the reload that follows it is still running, so "ref"
		// alone cannot tell a poller whether the instance has actually settled on it yet.
		out.put("reloading", reloadInProgress());
		writeJson(resp, out);
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
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
		// Both fields must be JSON *strings*: asText(null) alone substitutes the default only for
		// null/missing nodes and silently coerces every other type, so {"ref":5} would arrive here
		// as the branch name "5" and {"ref":{}} as "" -- neither is a request anyone meant to make.
		String configuration = textOrNull(body, "configuration");
		String ref = textOrNull(body, "ref");
		if (configuration == null || ref == null) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "configuration and ref are required");
			return;
		}
		try {
			GitClassLoader.validateRef(ref);
		} catch (IllegalArgumentException e) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
			return;
		}
		GitClassLoader loader = GitClassLoader.lookup(configuration);
		if (loader == null) {
			sendNotRegistered(resp, configuration);
			return;
		}

		// The caller's principal name has to be read here, on the request thread: the reload task
		// runs on a worker with its own (empty) SecurityContext and cannot ask who called.
		String callerName = SecurityContextHolder.getContext().getAuthentication().getName();

		// A reload we dispatched is still queued or running: moving HEAD now would have F!F digest
		// a working tree that changes underneath it. Answered before touching the lock, and
		// crucially without touching HEAD -- the caller retries once the instance has settled.
		if (reloadInProgress()) {
			sendReloadInProgress(resp, loader);
			return;
		}

		// Everything that moves HEAD, inspects the result, or hands the result to the reloader is
		// one atomic step: without it, a second PUT could move HEAD between this checkout and its
		// subdir check, or between the check and the dispatch, and the reload would load a branch
		// nobody asked for. Revert paths are inside for the same reason -- reverting must not undo
		// someone else's checkout. tryLock, not lock: a request thread must never park here (the
		// whole point of the async dispatch is that this endpoint answers promptly), and it must
		// never be a loader monitor (see the class javadoc on lock order).
		if (!SWITCH_LOCK.tryLock()) {
			sendReloadInProgress(resp, loader);
			return;
		}
		try {
			// Re-check under the lock: between the check above and acquiring it, the thread that
			// held the lock may have dispatched a reload of its own.
			if (reloadInProgress()) {
				sendReloadInProgress(resp, loader);
				return;
			}
			String previousRef = loader.currentRef();
			try {
				loader.checkout(ref);
			} catch (Exception e) {
				// The clone stays on its previous ref (checkout fails before moving HEAD); tell the
				// caller why in one sanitised line -- never a stack trace into a chat transcript.
				logBusFailure("configRef checkout", e);
				Map<String, Object> error = new LinkedHashMap<>();
				error.put("error", sanitizedReason(e));
				sendConflict(resp, error);
				return;
			}

			if (!loader.resourceDirExists()) {
				// The branch checked out cleanly but does not contain this configuration's resource
				// root (e.g. a branch that predates the configuration, or one for an unrelated
				// purpose) -- reverting keeps the instance runnable instead of dispatching a reload
				// against a configuration with nothing to load.
				revert(loader, previousRef, "missing " + loader.getRepoSubdir() + " directory");
				// "ref" reports where the instance actually ended up -- if the revert itself failed
				// (logged above, not surfaced) this is NOT previousRef, and the caller must see that.
				Map<String, Object> error = new LinkedHashMap<>();
				error.put("error", "branch [" + ref + "] has no " + loader.getRepoSubdir() + " directory");
				error.put("ref", loader.currentRef());
				sendConflict(resp, error);
				return;
			}

			// Moving HEAD is not a configuration reload: AbstractClassLoader.reload() only evicts
			// AppConstants, and the real reload is IbisContext.reload(name) -- classloader reload
			// (a no-op pull now), unload, load. Dispatched as a NAMED RELOAD: RELOAD with a
			// configuration name reaches IbisContext.reload(name) (only the *ALL* sentinel is the
			// silent no-op). OutboundGateway rather than FrankApiService -- see class javadoc.
			OutboundGateway gateway = lookupConsoleBean(req, OutboundGateway.class);
			if (gateway == null) {
				// Nothing will reload this instance, so leaving it on the new branch would leave it
				// running a configuration the caller was never told had loaded. Put HEAD back.
				revert(loader, previousRef, "undispatchable reload (console not initialised)");
				sendRefError(resp, loader, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
						"the configuration reload could not be dispatched (console not initialised) -- retry");
				return;
			}
			try {
				inFlightReload = RELOAD_EXECUTOR.submit(() -> dispatchReload(gateway, configuration, callerName));
			} catch (RuntimeException e) {
				// RejectedExecutionException (executor shut down / queue refused). Same reasoning
				// as the 503 above: no reload is coming, so do not leave HEAD moved.
				logBusFailure("configRef reload submit", e);
				revert(loader, previousRef, "rejected reload dispatch");
				sendRefError(resp, loader, HttpServletResponse.SC_BAD_GATEWAY,
						"the configuration reload could not be dispatched: " + sanitizedReason(e));
				return;
			}

			// 202, not 200: HEAD has moved and the reload is queued, but nothing about the reload
			// has been observed yet. "reloading" says so explicitly -- the caller polls GET (or the
			// console's own status) for completion.
			Map<String, Object> accepted = state(configuration, loader);
			accepted.put("reloading", true);
			resp.setStatus(HttpServletResponse.SC_ACCEPTED);
			writeJson(resp, accepted);
		} finally {
			SWITCH_LOCK.unlock();
		}
	}

	/** True while a reload this servlet dispatched is still queued or running. */
	static boolean reloadInProgress() {
		Future<?> inFlight = inFlightReload;
		return inFlight != null && !inFlight.isDone();
	}

	/**
	 * Sends the named RELOAD on {@link #RELOAD_EXECUTOR}, elevated to {@link #elevatedRoles()} the
	 * same way {@link #callElevated} elevates on a request thread -- minus the RequestContextHolder
	 * binding, which would be a lie out here (the request is long over) and is only needed by the
	 * session-scoped beans this deliberately does not touch.
	 *
	 * <p>Takes no {@link GitClassLoader} monitor: {@code sendAsyncMessage} runs the reload inline,
	 * and F!F locks {@code IbisContext} before the classloader, so holding one here would invert
	 * the order against every other reload path in the process. See the class javadoc.</p>
	 *
	 * <p>The HTTP response went out before this ran, so a failure has no one left to tell: log it
	 * and stop. {@link Throwable}, not {@link Exception} -- {@code submit} captures whatever
	 * escapes into the {@link Future} nobody here inspects, so an uncaught Error would otherwise
	 * vanish without a trace in the log.</p>
	 */
	private static void dispatchReload(OutboundGateway gateway, String configuration, String callerName) {
		try {
			SecurityContext elevated = SecurityContextHolder.createEmptyContext();
			List<SimpleGrantedAuthority> authorities = Arrays.stream(ELEVATED_ROLES)
					.map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
					.toList();
			elevated.setAuthentication(new PreAuthenticatedAuthenticationToken(callerName, "n/a", authorities));
			SecurityContextHolder.setContext(elevated);

			RequestMessageBuilder builder = RequestMessageBuilder.create(BusTopic.IBISACTION);
			builder.addHeader("action", Action.RELOAD.name());
			builder.addHeader(BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY, configuration);
			// Runs the reload inline (see class javadoc) -- and holds no loader monitor while it
			// does, which is what keeps it out of the deadlock with F!F's own reload paths.
			gateway.sendAsyncMessage(builder.build(null));
		} catch (Throwable t) {
			log.warn("configRef reload dispatch for configuration [{}] failed", configuration, t);
		} finally {
			// The one worker thread is reused: a leftover elevated context would be inherited by
			// the next reload task, which sets its own anyway, but not by accident.
			SecurityContextHolder.clearContext();
			// Reopens the endpoint to the next ref switch. Deliberately last, and in a finally:
			// a task that died still has to clear the gate, or every later PUT answers 409.
			inFlightReload = null;
		}
	}

	/** Best-effort return to {@code previousRef}; a failed revert is logged, and shows up in the response's own "ref". */
	private void revert(GitClassLoader loader, String previousRef, String why) {
		try {
			loader.checkout(previousRef);
		} catch (Exception e) {
			logBusFailure("configRef revert after " + why, e);
		}
	}

	private void sendConflict(HttpServletResponse resp, Map<String, Object> error) throws IOException {
		resp.setStatus(HttpServletResponse.SC_CONFLICT);
		writeJson(resp, error);
	}

	/**
	 * A failure the caller must read as JSON, carrying the ref the instance actually sits on now.
	 * {@code setStatus} + {@code writeJson} rather than {@code sendError}, which would replace the
	 * body with the container's HTML error page.
	 */
	private void sendRefError(HttpServletResponse resp, GitClassLoader loader, int status, String message) throws IOException {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("error", message);
		error.put("ref", loader.currentRef());
		resp.setStatus(status);
		writeJson(resp, error);
	}

	/**
	 * 409, not 404: 404 on this endpoint means "no such endpoint" (an instance whose image predates
	 * it), and a client that cannot tell the two apart will report "unsupported instance" for what
	 * is really a transient state. A configuration missing from the registry is exactly that --
	 * either a reload is between destroy() and configure(), or its classloader failed to configure
	 * -- so it is a conflict with the instance's current state, and retryable.
	 */
	/**
	 * 409 for "the instance is busy with the last switch". Distinct {@code code} from
	 * {@link #CODE_NOT_REGISTERED} because the two want different client behaviour: this one is a
	 * plain retry-after-the-reload-settles, and {@code "ref"} tells the caller which branch it is
	 * settling on -- which may already be the one they just asked for.
	 */
	private void sendReloadInProgress(HttpServletResponse resp, GitClassLoader loader) throws IOException {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("error", "configuration reload in progress -- retry");
		error.put("code", CODE_RELOAD_IN_PROGRESS);
		error.put("ref", loader.currentRef());
		resp.setStatus(HttpServletResponse.SC_CONFLICT);
		writeJson(resp, error);
	}

	private void sendNotRegistered(HttpServletResponse resp, String configuration) throws IOException {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("error", "no git-backed configuration named [" + configuration
				+ "] registered (reload in progress, or its classloader failed to configure)");
		error.put("code", CODE_NOT_REGISTERED);
		resp.setStatus(HttpServletResponse.SC_CONFLICT);
		writeJson(resp, error);
	}

	/** The value of {@code field} when it is a JSON string, else null (see the call site for why). */
	private static String textOrNull(JsonNode body, String field) {
		JsonNode value = body.path(field);
		return value.isTextual() ? value.asText() : null;
	}

	private static Map<String, Object> state(String configuration, GitClassLoader loader) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("configuration", configuration);
		out.put("ref", loader.currentRef());
		out.put("isDefault", loader.isDefaultRef());
		return out;
	}
}
