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

import org.springframework.messaging.Message;

import com.fasterxml.jackson.databind.JsonNode;

import org.frankframework.lifecycle.IbisInitializer;
import org.frankframework.management.Action;
import org.frankframework.management.bus.BusAction;
import org.frankframework.management.bus.BusMessageUtils;
import org.frankframework.management.bus.BusTopic;
import org.frankframework.management.bus.OutboundGateway;
import org.frankframework.management.bus.message.RequestMessageBuilder;

/**
 * Bearer-JWT-only adapter start/stop: the API counterpart to the console's own Start/Stop
 * buttons (see {@code org.frankframework.console.controllers.Adapters#updateAdapter}, PUT
 * /iaf/api/configurations/{configuration}/adapters/{adapter}) -- same BusTopic.IBISACTION /
 * Action.STARTADAPTER|STOPADAPTER dispatch, reachable with a stateless service-account token
 * instead of a browser session. See docs/superpowers/specs/2026-08-19-adapter-start-stop-tool-
 * design.md (viscoFoundry repo).
 *
 * <p>Unlike the console's own controller (and this repo's {@code ReloadConfigurationServlet}),
 * this call is SYNCHRONOUS: {@code IbisManager.handleAction()} runs {@code adapter.start()}/
 * {@code .stop()} inline before returning, so driving it via {@link OutboundGateway#sendSyncMessage}
 * (not {@code FrankApiService.callAsyncGateway}) blocks until the state change has actually
 * happened. A second sync call (BusTopic.ADAPTER/BusAction.FIND, the same endpoint
 * {@link AdaptersServlet} reads, single-adapter variant) then confirms the adapter's resulting
 * state, so callers get a real confirmed outcome in one request -- never a bare "accepted."</p>
 *
 * <p>PUT JSON {@code {"configuration","adapter","action":"start"|"stop"}}; responds
 * {@code {"configuration","adapter","state"}} -- the same shape {@link AdaptersServlet} already
 * returns per adapter, so client-side code can share one response type.</p>
 */
@IbisInitializer
public class AdapterControlServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	static final String SECURITY_ROLES_PROPERTY = "servlet.adapterControl.securityRoles";

	/** Request bodies larger than this are rejected before parsing -- three short strings, generous cap. */
	static final int MAX_BODY_BYTES = 8 * 1024;

	@Override
	public String getName() {
		return "adapterControl";
	}

	@Override
	public String getUrlMapping() {
		return "/api-service/adapters/state";
	}

	@Override
	protected String securityRolesProperty() {
		return SECURITY_ROLES_PROPERTY;
	}

	@Override
	protected String[] elevatedRoles() {
		// Satisfies both bus calls' @RolesAllowed: HandleIbisManagerAction
		// (IbisDataAdmin/IbisAdmin/IbisTester) and AdapterStatus.getAdapter (adds
		// IbisObserver to that same set). Same minimal choice as TestPipelineServlet.
		return new String[] { "IbisTester" };
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
		String adapter = body.path("adapter").asText(null);
		String action = body.path("action").asText(null);
		if (configuration == null || adapter == null || !("start".equals(action) || "stop".equals(action))) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "configuration, adapter, and action (\"start\" or \"stop\") are required");
			return;
		}
		Action busAction = "start".equals(action) ? Action.STARTADAPTER : Action.STOPADAPTER;

		OutboundGateway gateway = lookupConsoleBean(req, OutboundGateway.class);
		if (gateway == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "console services not initialised");
			return;
		}

		// Uncaught BusException would surface as a container error page (no Spring MVC
		// exception translation out here) -- map to a clean 502 with a sanitized reason,
		// same pattern as TestPipelineServlet/AdaptersServlet.
		Map<String, String> result;
		try {
			result = callElevated(req, resp, () -> {
				RequestMessageBuilder actionBuilder = RequestMessageBuilder.create(BusTopic.IBISACTION);
				actionBuilder.addHeader("action", busAction.name());
				actionBuilder.addHeader(BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY, configuration);
				actionBuilder.addHeader(BusMessageUtils.HEADER_ADAPTER_NAME_KEY, adapter);
				// Blocks until adapter.start()/stop() actually returns -- see class javadoc.
				gateway.sendSyncMessage(actionBuilder.build(null));

				RequestMessageBuilder statusBuilder = RequestMessageBuilder.create(BusTopic.ADAPTER, BusAction.FIND);
				statusBuilder.addHeader(BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY, configuration);
				statusBuilder.addHeader(BusMessageUtils.HEADER_ADAPTER_NAME_KEY, adapter);
				Message<?> statusResponse = gateway.sendSyncMessage(statusBuilder.build(null));
				JsonNode adapterInfo = JSON.readTree(String.valueOf(statusResponse.getPayload()));

				Map<String, String> out = new LinkedHashMap<>();
				out.put("configuration", adapterInfo.path("configuration").asText(configuration));
				out.put("adapter", adapterInfo.path("name").asText(adapter));
				out.put("state", adapterInfo.path("state").asText(""));
				return out;
			});
		} catch (RuntimeException e) {
			logBusFailure("adapter-control", e);
			resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "adapter " + action + " failed: " + sanitizedReason(e));
			return;
		}
		writeJson(resp, result);
	}
}
