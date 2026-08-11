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

		// Uncaught BusException would surface as a container error page (no Spring MVC
		// exception translation out here) -- map to a clean 502 with a sanitized reason.
		List<Map<String, String>> adapters;
		try {
			adapters = callElevated(req, resp, () -> {
				RequestMessageBuilder builder = RequestMessageBuilder.create(BusTopic.ADAPTER, BusAction.GET);
				Message<?> response = gateway.sendSyncMessage(builder.build(null));
				return summarize(JSON.readTree(String.valueOf(response.getPayload())));
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
}
