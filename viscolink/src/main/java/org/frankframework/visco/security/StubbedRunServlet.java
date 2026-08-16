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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frankframework.lifecycle.IbisInitializer;

import com.viscosiety.ladybug.StubbedRunner;

/**
 * Bearer-JWT-only sibling of {@code com.viscosiety.flow.FlowController}'s
 * {@code POST /flow-api/stubbed-run}. That path is reachable only through F!F's console session
 * authentication (ConsoleSecurityRegistrar reuses the browser-session OAuth2 chain for every
 * non-F!F-owned path, {@code /flow-api/*} included), which structurally cannot authenticate a
 * bare Bearer header -- confirmed the same way {@code TestPipelineServlet}'s own javadoc history
 * documents for F!F's console chain generally. Server-to-server callers (ViscoForge's
 * ShareController) need this sibling instead. Same underlying capability -- run an adapter with
 * all senders stubbed to a no-op, captured as a Ladybug report -- just reachable by a tenant
 * service-account token. The original {@code /flow-api/stubbed-run} path is untouched and still
 * serves genuine browser-session callers.
 */
@IbisInitializer
public class StubbedRunServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	static final String SECURITY_ROLES_PROPERTY = "servlet.stubbedRun.securityRoles";

	/**
	 * Request bodies larger than this are rejected before the input stream is touched -- same
	 * cap and same rationale as {@link TestPipelineServlet#MAX_BODY_BYTES}: this endpoint runs
	 * tenant-configured pipeline logic over the body, so an unbounded read is a resource-exhaustion
	 * vector. Stubbed-run inputs are not expected to be larger than a single test-pipeline message,
	 * so the same bound applies.
	 */
	static final int MAX_BODY_BYTES = 64 * 1024;

	@Override
	public String getName() {
		return "stubbedRun";
	}

	@Override
	public String getUrlMapping() {
		return "/api-service/stubbed-run";
	}

	@Override
	protected String securityRolesProperty() {
		return SECURITY_ROLES_PROPERTY;
	}

	@Override
	protected String[] elevatedRoles() {
		// StubbedRunner drives the adapter's pipeline in-process, same shape of call as
		// TestPipelineServlet's own TEST_PIPELINE bus call -- same minimal grant.
		return new String[] { "IbisTester" };
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (rejectUnauthorized(resp)) {
			return;
		}
		String qs = req.getQueryString();
		String configuration = param(qs, "config", null);
		String adapter = param(qs, "adapter", null);
		String originId = param(qs, "originId", null);
		String cidPrefix = param(qs, "cidPrefix", null);
		if (adapter == null || adapter.isBlank()) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "adapter query param is required");
			return;
		}
		if (req.getContentLengthLong() > MAX_BODY_BYTES) {
			resp.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "request body exceeds " + MAX_BODY_BYTES + " bytes");
			return;
		}
		StubbedRunner runner = StubbedRunner.getInstance();
		if (runner == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "stubbed runner not initialised");
			return;
		}
		byte[] input = req.getInputStream().readAllBytes();
		StubbedRunner.Result result;
		try {
			result = callElevated(req, resp, () -> runner.runStubbed(configuration, adapter, input, originId, cidPrefix));
		} catch (IllegalArgumentException e) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
			return;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("correlationId", result.correlationId());
		body.put("configuration", configuration);
		body.put("adapter", adapter);
		body.put("state", result.state());
		writeJson(resp, body);
	}

	/**
	 * Hand-rolled query-string parameter read, mirroring {@code FlowController}'s own private
	 * {@code param(qs, name, def)} helper (this class's sibling deliberately uses the same
	 * pattern). Calling {@link HttpServletRequest#getParameter(String)} on a POST would trigger
	 * body parsing when the request carries {@code Content-Type: application/x-www-form-urlencoded}
	 * -- Tomcat then consumes the input stream, so a later {@code getInputStream().readAllBytes()}
	 * can silently return empty/truncated bytes instead of throwing. Reading the query string
	 * directly avoids that failure mode entirely; the request body is reserved exclusively for the
	 * stubbed-run input message.
	 */
	private static String param(String qs, String name, String def) {
		if (qs == null || qs.isEmpty()) {
			return def;
		}
		for (String kv : qs.split("&")) {
			if (kv.startsWith(name + "=")) {
				try {
					return URLDecoder.decode(kv.substring(name.length() + 1), StandardCharsets.UTF_8);
				} catch (Exception e) {
					return def;
				}
			}
		}
		return def;
	}
}
