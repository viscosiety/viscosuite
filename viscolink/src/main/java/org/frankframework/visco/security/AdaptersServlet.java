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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.messaging.Message;

import com.fasterxml.jackson.databind.JsonNode;

import org.frankframework.lifecycle.IbisInitializer;
import org.frankframework.management.bus.BusAction;
import org.frankframework.management.bus.BusTopic;
import org.frankframework.management.bus.OutboundGateway;
import org.frankframework.management.bus.message.RequestMessageBuilder;

/**
 * Bearer-JWT-only runtime adapter listing (console counterpart:
 * {@code org.frankframework.console.controllers.Adapters}, ADAPTER/GET bus request). GET returns a
 * JSON array of {@code {"configuration","adapter","state"}} -- the runtime truth about what
 * actually loaded, which the raw configuration XML cannot tell.
 *
 * <p><b>Expanded variant ({@code ?expanded=all}):</b> when the {@code expanded} query parameter is
 * present it is forwarded verbatim as the ADAPTER/GET {@code expanded} header (with
 * {@code showPendingMsgCount=true}) and each row additionally carries {@code messagesProcessed},
 * {@code lastMessage} and {@code receivers[].{name, messages.received,
 * transactionalStores.ERROR.numberOfMessages}} -- what the ViscoForge console needs to address a
 * receiver's error store and badge its ERROR count (viscoFoundry design
 * 2026-09-12-forge-console-launch §4a). The lean default projection (no parameter) is unchanged for
 * existing callers, and both share this servlet's {@code servlet.adapters.securityRoles} gate -- no
 * new property, so nothing extra to wire on existing instances.</p>
 */
@IbisInitializer
public class AdaptersServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	static final String SECURITY_ROLES_PROPERTY = "servlet.adapters.securityRoles";

	@Override
	public String getName() {
		return "adapters";
	}

	@Override
	public String getUrlMapping() {
		return "/api-service/adapters";
	}

	@Override
	protected String securityRolesProperty() {
		return SECURITY_ROLES_PROPERTY;
	}

	@Override
	protected String[] elevatedRoles() {
		// Minimal: ADAPTER/GET's @RolesAllowed already accepts IbisObserver alone.
		return new String[] { "IbisObserver" };
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (rejectUnauthorized(resp)) {
			return;
		}
		OutboundGateway gateway = lookupConsoleBean(req, OutboundGateway.class);
		if (gateway == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "console services not initialised");
			return;
		}

		String expanded = req.getParameter("expanded");
		boolean wantExpanded = expanded != null && !expanded.isBlank();

		// Uncaught BusException would surface as a container error page (no Spring MVC
		// exception translation out here) -- map to a clean 502 with a sanitized reason.
		Object adapters;
		try {
			adapters = callElevated(req, resp, () -> {
				RequestMessageBuilder builder = RequestMessageBuilder.create(BusTopic.ADAPTER, BusAction.GET);
				if (wantExpanded) {
					builder.addHeader("expanded", expanded);
					builder.addHeader("showPendingMsgCount", Boolean.TRUE);
				}
				Message<?> response = gateway.sendSyncMessage(builder.build(null));
				JsonNode payload = JSON.readTree(String.valueOf(response.getPayload()));
				return wantExpanded ? summarizeExpanded(payload) : summarize(payload);
			});
		} catch (RuntimeException e) {
			logBusFailure("adapters", e);
			resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "adapter listing failed: " + sanitizedReason(e));
			return;
		}
		writeJson(resp, adapters);
	}

	/**
	 * Flattens the ADAPTER/GET payload (an object keyed by adapter, each entry carrying at least
	 * {@code configuration} and {@code state}) into the contract's array shape. Tolerant of shape
	 * drift: unknown/missing fields become empty strings rather than failures.
	 */
	static List<Map<String, String>> summarize(JsonNode payload) {
		List<Map<String, String>> out = new ArrayList<>();
		for (Iterator<Map.Entry<String, JsonNode>> it = payload.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> entry = it.next();
			JsonNode adapter = entry.getValue();
			Map<String, String> row = new LinkedHashMap<>();
			row.put("configuration", adapter.path("configuration").asText(""));
			row.put("adapter", adapter.path("name").asText(entry.getKey()));
			row.put("state", adapter.path("state").asText(""));
			out.add(row);
		}
		return out;
	}

	/**
	 * The §4a expanded projection: the lean fields plus the runtime counters and the receiver
	 * error-store data the console addresses. Deliberately narrow -- it re-shapes only the subtree
	 * the console reads ({@code receivers[].{name, messages.received,
	 * transactionalStores.ERROR.numberOfMessages}}) rather than passing the full ADAPTER/GET payload
	 * through, and tolerates shape drift: absent counters/stores become JSON null, never failures.
	 */
	static List<Map<String, Object>> summarizeExpanded(JsonNode payload) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Iterator<Map.Entry<String, JsonNode>> it = payload.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> entry = it.next();
			JsonNode adapter = entry.getValue();
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("configuration", adapter.path("configuration").asText(""));
			row.put("adapter", adapter.path("name").asText(entry.getKey()));
			row.put("state", adapter.path("state").asText(""));
			row.put("messagesProcessed", numberOrNull(adapter, "messagesProcessed"));
			row.put("lastMessage", numberOrNull(adapter, "lastMessage"));
			row.put("receivers", mapReceivers(adapter.path("receivers")));
			out.add(row);
		}
		return out;
	}

	private static List<Map<String, Object>> mapReceivers(JsonNode receivers) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (!receivers.isArray()) {
			return out;
		}
		for (JsonNode receiver : receivers) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", receiver.path("name").asText(""));

			Map<String, Object> messages = new LinkedHashMap<>();
			messages.put("received", numberOrNull(receiver.path("messages"), "received"));
			row.put("messages", messages);

			Map<String, Object> error = new LinkedHashMap<>();
			// transactionalStores is keyed by the ProcessState enum's name(), so "ERROR".
			error.put("numberOfMessages", numberOrNull(receiver.path("transactionalStores").path("ERROR"), "numberOfMessages"));
			Map<String, Object> stores = new LinkedHashMap<>();
			stores.put("ERROR", error);
			row.put("transactionalStores", stores);

			out.add(row);
		}
		return out;
	}

	/**
	 * The value of {@code field} on {@code parent} as a plain Object -- a number stays numeric, a
	 * string (the bus renders an unavailable count as {@code "?"}) stays a string, and a
	 * missing/null node becomes null. Never throws on shape drift.
	 */
	static Object numberOrNull(JsonNode parent, String field) {
		JsonNode node = parent.path(field);
		if (node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (node.isNumber()) {
			return node.numberValue();
		}
		if (node.isTextual()) {
			return node.asText();
		}
		return null;
	}
}
