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
 * Bearer-JWT-only configuration/application warnings (console counterpart:
 * {@code org.frankframework.console.controllers.ServerDetails#getServerConfiguration}, the
 * APPLICATION/WARNINGS bus request behind {@code GET /iaf/api/server/warnings}). GET returns
 * {@code {"global": [..], "configurations": [{"configuration","warnings":[..],"exception"?}]}} --
 * the startup warnings (deprecations, security risks, misconfigurations) the console banner shows,
 * which raw configuration XML and XSD validation cannot reveal.
 */
@IbisInitializer
public class WarningsServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	static final String SECURITY_ROLES_PROPERTY = "servlet.warnings.securityRoles";

	@Override
	public String getName() {
		return "warnings";
	}

	@Override
	public String getUrlMapping() {
		return "/api-service/warnings";
	}

	@Override
	protected String securityRolesProperty() {
		return SECURITY_ROLES_PROPERTY;
	}

	@Override
	protected String[] elevatedRoles() {
		// APPLICATION/WARNINGS is @PermitAll on the bus side; IbisObserver matches the
		// console's weakest read role.
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

		Map<String, Object> warnings;
		try {
			warnings = callElevated(req, resp, () -> {
				RequestMessageBuilder builder = RequestMessageBuilder.create(BusTopic.APPLICATION, BusAction.WARNINGS);
				Message<?> response = gateway.sendSyncMessage(builder.build(null));
				return summarize(JSON.readTree(String.valueOf(response.getPayload())));
			});
		} catch (RuntimeException e) {
			logBusFailure("warnings", e);
			resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "warnings listing failed: " + sanitizedReason(e));
			return;
		}
		writeJson(resp, warnings);
	}

	/**
	 * Slims the APPLICATION/WARNINGS payload to what a config author can act on: the global
	 * warning list plus, per configuration, its warnings and (if present) its configuration
	 * exception. Skips the scalar bookkeeping fields ({@code totalErrorStoreCount}), global
	 * {@code messages}, and per-config noise (errorStoreCount, messages, monitorsRaised).
	 * Warning entries may be plain strings or objects carrying a {@code message} field --
	 * both are normalized to strings; configurations with nothing to report are omitted.
	 */
	static Map<String, Object> summarize(JsonNode payload) {
		List<String> global = new ArrayList<>();
		List<Map<String, Object>> configurations = new ArrayList<>();
		for (Iterator<Map.Entry<String, JsonNode>> it = payload.fields(); it.hasNext();) {
			Map.Entry<String, JsonNode> entry = it.next();
			JsonNode value = entry.getValue();
			if ("warnings".equals(entry.getKey()) && value.isArray()) {
				value.forEach(warning -> global.add(warningText(warning)));
				continue;
			}
			if (!value.isObject()) {
				continue; // totalErrorStoreCount and friends
			}
			List<String> configWarnings = new ArrayList<>();
			value.path("warnings").forEach(warning -> configWarnings.add(warningText(warning)));
			String exception = value.path("exception").asText("");
			if (configWarnings.isEmpty() && exception.isEmpty()) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("configuration", entry.getKey());
			row.put("warnings", configWarnings);
			if (!exception.isEmpty()) {
				row.put("exception", exception);
			}
			configurations.add(row);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("global", global);
		out.put("configurations", configurations);
		return out;
	}

	private static String warningText(JsonNode warning) {
		return warning.isObject() ? warning.path("message").asText(warning.toString()) : warning.asText(warning.toString());
	}
}
