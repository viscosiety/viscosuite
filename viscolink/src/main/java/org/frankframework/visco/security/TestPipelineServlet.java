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
import org.frankframework.management.bus.BusAction;
import org.frankframework.management.bus.BusMessageUtils;
import org.frankframework.management.bus.BusTopic;
import org.frankframework.management.bus.OutboundGateway;
import org.frankframework.management.bus.message.RequestMessageBuilder;

/**
 * Bearer-JWT-only counterpart of the console's "Test a Pipeline" (see
 * {@code org.frankframework.console.controllers.TestPipeline}): runs one message through an
 * adapter's pipeline via the TEST_PIPELINE bus endpoint. POST JSON
 * {@code {"configuration","adapter","message"}}; responds {@code {"state","result"}} with the
 * result truncated at {@link #MAX_RESULT_CHARS}.
 *
 * <p>Uses the {@link OutboundGateway} bean directly rather than {@code FrankApiService}: the
 * service's Spring-response wrapper drops the bus's {@code state} header, and its raw
 * {@code sendSyncMessage} is package-protected. {@code build(null)} skips cluster targeting --
 * single-node semantics, which is what a tenant instance is.</p>
 */
@IbisInitializer
public class TestPipelineServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	static final String SECURITY_ROLES_PROPERTY = "servlet.testPipeline.securityRoles";
	static final int MAX_RESULT_CHARS = 32 * 1024;

	@Override
	public String getName() {
		return "testPipeline";
	}

	@Override
	public String getUrlMapping() {
		return "/api-service/test-pipeline";
	}

	@Override
	protected String securityRolesProperty() {
		return SECURITY_ROLES_PROPERTY;
	}

	@Override
	protected String[] elevatedRoles() {
		// The TEST_PIPELINE bus endpoint is gated on IbisTester (console's own test screens).
		return new String[] { "IbisAdmin", "IbisTester" };
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (rejectUnauthorized(resp)) {
			return;
		}
		JsonNode body = JSON.readTree(req.getInputStream());
		String configuration = body.path("configuration").asText(null);
		String adapter = body.path("adapter").asText(null);
		String message = body.path("message").asText(null);
		if (configuration == null || adapter == null || message == null) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "configuration, adapter and message are required");
			return;
		}

		OutboundGateway gateway = lookupConsoleBean(req, OutboundGateway.class);
		if (gateway == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "console services not initialised");
			return;
		}

		Map<String, String> result = callElevated(req, resp, () -> {
			RequestMessageBuilder builder = RequestMessageBuilder.create(BusTopic.TEST_PIPELINE, BusAction.UPLOAD);
			builder.addHeader(BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY, configuration);
			builder.addHeader(BusMessageUtils.HEADER_ADAPTER_NAME_KEY, adapter);
			builder.setPayload(message);
			Message<?> response = gateway.sendSyncMessage(builder.build(null));
			Map<String, String> out = new LinkedHashMap<>();
			out.put("state", BusMessageUtils.getHeader(response, "state"));
			out.put("result", truncate(String.valueOf(response.getPayload()), MAX_RESULT_CHARS));
			return out;
		});
		writeJson(resp, result);
	}
}
