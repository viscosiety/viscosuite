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
import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
 * Bearer-JWT-only receiver error-store browsing and triage -- the API counterpart to the console's
 * {@code org.frankframework.console.controllers.TransactionalStorage}, dispatched over
 * {@code BusTopic.MESSAGE_BROWSER}. The ViscoForge operator-console BFF drives this to browse a
 * failing receiver's error store, read one failed message with its payload, retry (resend) a
 * message, or resolve (delete) one. See viscoFoundry's
 * docs/superpowers/specs/2026-09-12-forge-console-launch-design.md §4b.
 *
 * <p>One servlet, dispatching by HTTP method under a single path shape
 * (mirroring the console's own receiver-store routes verb-for-verb):</p>
 * <ul>
 * <li>{@code GET  /configurations/{c}/adapters/{a}/receivers/{r}/stores/Error?max=} --
 *     {@code MESSAGE_BROWSER/FIND} (browse; headers configuration/adapter/receiver, processState
 *     {@code Error}, skip 0, max).</li>
 * <li>{@code GET  .../stores/Error/messages/{base64Id}} -- {@code MESSAGE_BROWSER/GET} (one
 *     message + payload).</li>
 * <li>{@code PUT  .../stores/Error/messages/{base64Id}} -- {@code MESSAGE_BROWSER/UPLOAD} (retry;
 *     async gateway).</li>
 * <li>{@code DELETE .../stores/Error/messages/{base64Id}} -- {@code MESSAGE_BROWSER/DELETE}
 *     (resolve; async gateway).</li>
 * </ul>
 *
 * <p>{@code messageId} is Base64-encoded in the path because it can contain '/' (verbatim from the
 * console). Retry and delete are state-changing and inherit the same fail-closed bearer gate as
 * every other mutating servlet in this family; they mirror the console exactly by carrying no
 * {@code processState} header (the bus endpoints hardcode {@code ERROR}).</p>
 *
 * <p>Uses the {@link OutboundGateway} bean directly (like {@link AdaptersServlet}/
 * {@link TestPipelineServlet}) rather than {@code FrankApiService}: browse/get pass the bus's own
 * JSON payload straight through, and retry/delete are fire-and-forget async sends.</p>
 */
@IbisInitializer
public class ErrorStoreServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	static final String SECURITY_ROLES_PROPERTY = "servlet.errorStore.securityRoles";

	/** The only process state this endpoint exposes -- receiver error stores. */
	static final String ERROR_PROCESS_STATE = "Error";

	/** Default page size when {@code ?max=} is absent (matches the bus endpoint's own default). */
	static final int DEFAULT_MAX = 100;
	/** Hard cap so a caller can't request an unbounded page. */
	static final int MAX_MAX = 1000;

	@Override
	public String getName() {
		return "errorStore";
	}

	@Override
	public String getUrlMapping() {
		// Path mapping. The two exact mappings in this family --
		// /api-service/configurations (reload) and /api-service/configurations/ref (configRef) --
		// win over this '/*' by servlet URL-matching rules, so they are not shadowed.
		return "/api-service/configurations/*";
	}

	@Override
	protected String securityRolesProperty() {
		return SECURITY_ROLES_PROPERTY;
	}

	@Override
	protected String[] elevatedRoles() {
		// MESSAGE_BROWSER's @RolesAllowed accepts IbisDataAdmin/IbisAdmin/IbisTester; IbisTester
		// alone satisfies it -- the same minimal choice as TestPipelineServlet/AdapterControlServlet.
		return new String[] { "IbisTester" };
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (rejectUnauthorized(resp)) {
			return;
		}
		ErrorStorePath path = ErrorStorePath.parse(req.getPathInfo());
		if (path == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND, "unknown error-store path");
			return;
		}
		if (path.messageId() == null) {
			browse(req, resp, path);
		} else {
			getMessage(req, resp, path);
		}
	}

	private void browse(HttpServletRequest req, HttpServletResponse resp, ErrorStorePath path) throws IOException {
		OutboundGateway gateway = lookupConsoleBean(req, OutboundGateway.class);
		if (gateway == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "console services not initialised");
			return;
		}
		int max = clampMax(req.getParameter("max"));
		JsonNode result;
		try {
			result = callElevated(req, resp, () -> {
				RequestMessageBuilder builder = RequestMessageBuilder.create(BusTopic.MESSAGE_BROWSER, BusAction.FIND);
				addStoreHeaders(builder, path);
				builder.addHeader("processState", ERROR_PROCESS_STATE);
				builder.addHeader("skip", 0);
				builder.addHeader("max", max);
				Message<?> response = gateway.sendSyncMessage(builder.build(null));
				return JSON.readTree(String.valueOf(response.getPayload()));
			});
		} catch (RuntimeException e) {
			logBusFailure("error-store-browse", e);
			resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "error-store browse failed: " + sanitizedReason(e));
			return;
		}
		writeJson(resp, result);
	}

	private void getMessage(HttpServletRequest req, HttpServletResponse resp, ErrorStorePath path) throws IOException {
		String messageId = decodeMessageId(path.messageId());
		if (messageId == null) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "messageId is not valid base64");
			return;
		}
		OutboundGateway gateway = lookupConsoleBean(req, OutboundGateway.class);
		if (gateway == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "console services not initialised");
			return;
		}
		JsonNode result;
		try {
			result = callElevated(req, resp, () -> {
				RequestMessageBuilder builder = RequestMessageBuilder.create(BusTopic.MESSAGE_BROWSER, BusAction.GET);
				addStoreHeaders(builder, path);
				builder.addHeader("processState", ERROR_PROCESS_STATE);
				builder.addHeader("messageId", messageId);
				Message<?> response = gateway.sendSyncMessage(builder.build(null));
				return JSON.readTree(String.valueOf(response.getPayload()));
			});
		} catch (RuntimeException e) {
			logBusFailure("error-store-get", e);
			resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "error-store message read failed: " + sanitizedReason(e));
			return;
		}
		writeJson(resp, result);
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		dispatchMutation(req, resp, BusAction.UPLOAD, "error-store-retry");
	}

	@Override
	protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		dispatchMutation(req, resp, BusAction.DELETE, "error-store-resolve");
	}

	/**
	 * Retry ({@code UPLOAD}) and resolve ({@code DELETE}) are structurally identical: a single
	 * base64-addressed message, sent async, carrying configuration/adapter/receiver/messageId and --
	 * mirroring the console -- no processState header. The bus endpoints hardcode ERROR.
	 */
	private void dispatchMutation(HttpServletRequest req, HttpServletResponse resp, BusAction action, String operation)
			throws IOException {
		if (rejectUnauthorized(resp)) {
			return;
		}
		ErrorStorePath path = ErrorStorePath.parse(req.getPathInfo());
		if (path == null || path.messageId() == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND, "a message id is required");
			return;
		}
		String messageId = decodeMessageId(path.messageId());
		if (messageId == null) {
			resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "messageId is not valid base64");
			return;
		}
		OutboundGateway gateway = lookupConsoleBean(req, OutboundGateway.class);
		if (gateway == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "console services not initialised");
			return;
		}
		try {
			callElevated(req, resp, () -> {
				RequestMessageBuilder builder = RequestMessageBuilder.create(BusTopic.MESSAGE_BROWSER, action);
				addStoreHeaders(builder, path);
				builder.addHeader("messageId", messageId);
				gateway.sendAsyncMessage(builder.build(null));
				return null;
			});
		} catch (RuntimeException e) {
			logBusFailure(operation, e);
			resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, operation + " failed: " + sanitizedReason(e));
			return;
		}
		// Mirrors the console's callAsyncGateway (fire-and-forget -> 200, empty body).
		resp.setStatus(HttpServletResponse.SC_OK);
	}

	private static void addStoreHeaders(RequestMessageBuilder builder, ErrorStorePath path) {
		builder.addHeader(BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY, path.configuration());
		builder.addHeader(BusMessageUtils.HEADER_ADAPTER_NAME_KEY, path.adapter());
		builder.addHeader(BusMessageUtils.HEADER_RECEIVER_NAME_KEY, path.receiver());
	}

	/** Base64-decode, matching the console ({@code Base64.getDecoder()}); null on malformed input. */
	static String decodeMessageId(String base64) {
		try {
			return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static int clampMax(String raw) {
		if (raw == null) {
			return DEFAULT_MAX;
		}
		try {
			int parsed = Integer.parseInt(raw);
			return Math.max(1, Math.min(MAX_MAX, parsed));
		} catch (NumberFormatException e) {
			return DEFAULT_MAX;
		}
	}

	/**
	 * The fixed-shape path under {@code /api-service/configurations}, with an optional trailing
	 * message id. {@code messageId} is null for the browse (collection) form, non-null (still
	 * base64-encoded) for the single-message forms.
	 */
	record ErrorStorePath(String configuration, String adapter, String receiver, String messageId) {

		/**
		 * Parses {@code pathInfo} of the form
		 * {@code /{c}/adapters/{a}/receivers/{r}/stores/Error[/messages/{base64Id}]}. Returns null
		 * for any other shape. The literal segments are validated so this can never resolve a pipe
		 * store or a non-Error process state. The message id is the remainder after
		 * {@code messages/} rejoined with '/', so a decoded slash in the path survives.
		 */
		static ErrorStorePath parse(String pathInfo) {
			if (pathInfo == null) {
				return null;
			}
			String trimmed = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
			String[] p = trimmed.split("/");
			// 7 fixed segments: {c} adapters {a} receivers {r} stores Error
			if (p.length < 7) {
				return null;
			}
			if (!"adapters".equals(p[1]) || !"receivers".equals(p[3])
					|| !"stores".equals(p[5]) || !ERROR_PROCESS_STATE.equals(p[6])) {
				return null;
			}
			String configuration = p[0];
			String adapter = p[2];
			String receiver = p[4];
			if (configuration.isEmpty() || adapter.isEmpty() || receiver.isEmpty()) {
				return null;
			}
			if (p.length == 7) {
				return new ErrorStorePath(configuration, adapter, receiver, null);
			}
			// Single-message form: /messages/{id...}
			if (!"messages".equals(p[7]) || p.length < 9) {
				return null;
			}
			StringBuilder id = new StringBuilder(p[8]);
			for (int i = 9; i < p.length; i++) {
				id.append('/').append(p[i]);
			}
			return new ErrorStorePath(configuration, adapter, receiver, id.toString());
		}
	}
}
