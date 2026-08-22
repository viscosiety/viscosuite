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
 * <p>Checkout and reload are serialised on the classloader's own monitor: HEAD must not move again
 * while a reload is reading the working tree, and the reload's own {@code git pull} must not race
 * a concurrent checkout. The single-thread executor orders the dispatches; {@code synchronized
 * (loader)} on both sides orders them against the checkouts.</p>
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
		GitClassLoader loader = configuration == null ? null : GitClassLoader.lookup(configuration);
		if (loader == null) {
			sendNotRegistered(resp, configuration);
			return;
		}
		writeJson(resp, state(configuration, loader));
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

		// Everything that moves HEAD, inspects the result, or hands the result to the reloader is
		// one atomic step against this classloader -- the same monitor GitClassLoader#checkout and
		// #reload lock (so it is re-entrant here), and the one the reload task re-takes. Without
		// it, a second PUT could move HEAD between this checkout and its subdir check, or between
		// the check and the dispatch, and the reload would load a branch nobody asked for. Revert
		// paths are inside for the same reason: reverting must not undo someone else's checkout.
		synchronized (loader) {
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
				RELOAD_EXECUTOR.submit(() -> dispatchReload(gateway, loader, configuration, callerName));
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
		}
	}

	/**
	 * Sends the named RELOAD on {@link #RELOAD_EXECUTOR}, elevated to {@link #elevatedRoles()} the
	 * same way {@link #callElevated} elevates on a request thread -- minus the RequestContextHolder
	 * binding, which would be a lie out here (the request is long over) and is only needed by the
	 * session-scoped beans this deliberately does not touch.
	 *
	 * <p>The HTTP response went out before this ran, so a failure has no one left to tell: log it
	 * and stop. {@link Throwable}, not {@link Exception} -- an Error escaping a task on this
	 * single-thread executor would kill the worker silently and every later reload with it.</p>
	 */
	private static void dispatchReload(OutboundGateway gateway, GitClassLoader loader, String configuration, String callerName) {
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
			// Inside the monitor: sendAsyncMessage runs the reload inline (see class javadoc), and
			// the reload re-reads the working tree and pulls -- neither may race a new checkout.
			synchronized (loader) {
				gateway.sendAsyncMessage(builder.build(null));
			}
		} catch (Throwable t) {
			log.warn("configRef reload dispatch for configuration [{}] failed", configuration, t);
		} finally {
			// Worker threads are pooled: a leftover elevated context would be inherited by the
			// next reload task, which sets its own anyway, but not by accident.
			SecurityContextHolder.clearContext();
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
