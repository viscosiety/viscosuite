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
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;

import com.viscosiety.classloaders.GitClassLoader;

import org.frankframework.console.controllers.FrankApiService;
import org.frankframework.lifecycle.IbisInitializer;
import org.frankframework.management.Action;
import org.frankframework.management.bus.BusMessageUtils;
import org.frankframework.management.bus.BusTopic;
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
 * real reload is {@code IbisContext.reload(configurationName)}, reached the same way
 * {@link ReloadConfigurationServlet} reaches {@code IbisContext.reload()} for all configurations:
 * a management-bus RELOAD dispatch through {@link FrankApiService}, elevated to
 * {@link #elevatedRoles()} via {@link #callElevated}. The difference is the action's
 * configuration-name header -- RELOAD with a configuration name reaches
 * {@code IbisContext.reload(name)}; only the {@code *ALL*} sentinel (what
 * {@code Action.FULLRELOAD} sends instead) is the silent no-op.</p>
 */
@IbisInitializer
public class ConfigRefServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	static final String SECURITY_ROLES_PROPERTY = "servlet.configRef.securityRoles";

	/** Two short strings; anything larger is not a legitimate request. */
	static final int MAX_BODY_BYTES = 8 * 1024;

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
		return new String[] { "IbisAdmin" };
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (rejectUnauthorized(resp)) {
			return;
		}
		String configuration = req.getParameter("configuration");
		GitClassLoader loader = configuration == null ? null : GitClassLoader.lookup(configuration);
		if (loader == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND, "no git-backed configuration named [" + configuration + "]");
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
		String configuration = body.path("configuration").asText(null);
		String ref = body.path("ref").asText(null);
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
			resp.sendError(HttpServletResponse.SC_NOT_FOUND, "no git-backed configuration named [" + configuration + "]");
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
			try {
				loader.checkout(previousRef);
			} catch (Exception revertFailure) {
				logBusFailure("configRef revert after missing " + loader.getRepoSubdir() + " directory", revertFailure);
			}
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
		// (a no-op pull now), unload, load. That is dispatched the same way
		// ReloadConfigurationServlet does, but as a NAMED RELOAD: RELOAD with a configuration
		// name reaches IbisContext.reload(name) (only the *ALL* sentinel is the silent no-op).
		FrankApiService frankApiService = lookupConsoleBean(req, FrankApiService.class);
		if (frankApiService == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ref switched to [" + loader.currentRef()
					+ "] but the configuration reload could not be dispatched (console not initialised) -- retry");
			return;
		}
		try {
			callElevated(req, resp, () -> {
				RequestMessageBuilder builder = RequestMessageBuilder.create(BusTopic.IBISACTION);
				builder.addHeader("action", Action.RELOAD.name());
				builder.addHeader(BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY, configuration);
				frankApiService.callAsyncGateway(builder);
				return null;
			});
		} catch (RuntimeException e) {
			// HEAD already moved by this point -- an uncaught BusException/ApiException here would
			// otherwise surface as a raw container 500 with no indication the checkout itself
			// succeeded. Same pattern as TestPipelineServlet/AdapterControlServlet.
			logBusFailure("configRef reload", e);
			resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "configuration reload could not be dispatched (ref is now ["
					+ loader.currentRef() + "]): " + sanitizedReason(e));
			return;
		}
		writeJson(resp, state(configuration, loader));
	}

	private void sendConflict(HttpServletResponse resp, Map<String, Object> error) throws IOException {
		resp.setStatus(HttpServletResponse.SC_CONFLICT);
		writeJson(resp, error);
	}

	private static Map<String, Object> state(String configuration, GitClassLoader loader) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("configuration", configuration);
		out.put("ref", loader.currentRef());
		out.put("isDefault", loader.isDefaultRef());
		return out;
	}
}
