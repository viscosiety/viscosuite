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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.wearefrank.ladybug.Checkpoint;
import org.wearefrank.ladybug.Report;
import org.wearefrank.ladybug.TestTool;
import org.wearefrank.ladybug.storage.Storage;
import org.wearefrank.ladybug.storage.StorageException;

import org.frankframework.lifecycle.IbisInitializer;

/**
 * Bearer-JWT-only Ladybug read access: execution reports and their checkpoints (the step-by-step
 * trace every processed message leaves in the debug storage). One servlet, two paths:
 *
 * <ul>
 * <li>{@code GET /api-service/ladybug/reports?limit=N} -- most recent report summaries.</li>
 * <li>{@code GET /api-service/ladybug/reports?correlationId=<id>} -- resolve a report's storageId
 * by the correlationId it was captured under (404 if not found in the most recent
 * {@value #CORRELATION_LOOKUP_SCAN_LIMIT} reports).</li>
 * <li>{@code GET /api-service/ladybug/report/<storageId>} -- one report with checkpoints.</li>
 * </ul>
 *
 * <p>Ladybug is NOT a management-bus citizen: its {@link TestTool} bean (and the debug
 * {@link Storage} hanging off it) is resolved from the console context directly. The storage reads
 * still run inside {@code callElevated} for family uniformity: the request-context binding costs
 * nothing and protects against the bean graph ever growing a request/session-scoped edge, and the
 * single inert IbisObserver role documents the read-only intent.</p>
 */
@IbisInitializer
public class LadybugServlet extends AbstractBearerServiceServlet {

	@Serial
	private static final long serialVersionUID = 1L;

	static final String SECURITY_ROLES_PROPERTY = "servlet.ladybug.securityRoles";
	static final int MAX_LIMIT = 20;
	static final int MAX_CHECKPOINT_MESSAGE_CHARS = 2048;

	/** Metadata columns requested from the storage, in contract order. */
	private static final List<String> METADATA_NAMES = List.of("storageId", "name", "status", "endTime", "duration");

	private static final List<String> CORRELATION_LOOKUP_METADATA_NAMES = List.of("storageId", "correlationId");
	/** Old unauthenticated LadybugClient scanned the most recent 30 rows; a little headroom here. */
	static final int CORRELATION_LOOKUP_SCAN_LIMIT = 50;

	@Override
	public String getName() {
		return "ladybug";
	}

	@Override
	public String getUrlMapping() {
		return "/api-service/ladybug/*";
	}

	@Override
	protected String securityRolesProperty() {
		return SECURITY_ROLES_PROPERTY;
	}

	@Override
	protected String[] elevatedRoles() {
		return new String[] { "IbisObserver" };
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (rejectUnauthorized(resp)) {
			return;
		}
		TestTool testTool = lookupConsoleBean(req, TestTool.class);
		Storage storage = testTool != null ? testTool.getDebugStorage() : null;
		if (storage == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ladybug storage not initialised");
			return;
		}

		String pathInfo = req.getPathInfo() == null ? "" : req.getPathInfo();
		try {
			if ("/reports".equals(pathInfo)) {
				String correlationId = req.getParameter("correlationId");
				if (correlationId != null) {
					Integer storageId = callElevated(req, resp, () -> {
						try {
							return findStorageIdByCorrelationId(storage, correlationId);
						} catch (StorageException e) {
							throw new IllegalStateException(e);
						}
					});
					if (storageId == null) {
						resp.sendError(HttpServletResponse.SC_NOT_FOUND, "no report with correlationId " + correlationId);
						return;
					}
					writeJson(resp, Map.of("storageId", storageId));
					return;
				}
				int limit = clampLimit(req.getParameter("limit"));
				List<Map<String, Object>> reports = callElevated(req, resp, () -> {
					try {
						return listReports(storage, limit);
					} catch (StorageException e) {
						throw new IllegalStateException(e);
					}
				});
				writeJson(resp, reports);
				return;
			}
			Integer storageId = parseReportId(pathInfo);
			if (storageId != null) {
				Report report = callElevated(req, resp, () -> {
					try {
						return storage.getReport(storageId);
					} catch (StorageException e) {
						throw new IllegalStateException(e);
					}
				});
				if (report == null) {
					resp.sendError(HttpServletResponse.SC_NOT_FOUND, "no report with storageId " + storageId);
					return;
				}
				writeJson(resp, describeReport(report));
				return;
			}
		} catch (RuntimeException e) {
			// Sanitized: storage/DB failure detail (paths, SQL) stays in the log, never the body.
			logBusFailure("ladybug", e);
			resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "ladybug storage error: " + sanitizedReason(e));
			return;
		}
		resp.sendError(HttpServletResponse.SC_NOT_FOUND, "unknown ladybug path " + pathInfo);
	}

	private static List<Map<String, Object>> listReports(Storage storage, int limit) throws StorageException {
		// getMetadata's search-values list must match metadataNames positionally; nulls = no filter.
		List<String> noFilter = new ArrayList<>();
		for (int i = 0; i < METADATA_NAMES.size(); i++) {
			noFilter.add(null);
		}
		List<List<Object>> rows = storage.getMetadata(limit, METADATA_NAMES, noFilter, Storage.FILTER_RESET);
		List<Map<String, Object>> out = new ArrayList<>();
		for (List<Object> row : rows) {
			Map<String, Object> summary = new LinkedHashMap<>();
			for (int i = 0; i < METADATA_NAMES.size() && i < row.size(); i++) {
				summary.put(METADATA_NAMES.get(i), row.get(i));
			}
			out.add(summary);
		}
		return out;
	}

	private static Integer findStorageIdByCorrelationId(Storage storage, String correlationId) throws StorageException {
		List<String> noFilter = new ArrayList<>();
		for (int i = 0; i < CORRELATION_LOOKUP_METADATA_NAMES.size(); i++) {
			noFilter.add(null);
		}
		List<List<Object>> rows = storage.getMetadata(CORRELATION_LOOKUP_SCAN_LIMIT, CORRELATION_LOOKUP_METADATA_NAMES, noFilter, Storage.FILTER_RESET);
		return findStorageIdInRows(rows, correlationId);
	}

	/** Pure helper (unit-testable without a live Storage) -- rows are [storageId, correlationId] pairs, positional per CORRELATION_LOOKUP_METADATA_NAMES. */
	static Integer findStorageIdInRows(List<List<Object>> rows, String correlationId) {
		for (List<Object> row : rows) {
			if (correlationId.equals(row.get(1))) {
				Object id = row.get(0);
				return id instanceof Integer ? (Integer) id : Integer.valueOf(String.valueOf(id));
			}
		}
		return null;
	}

	private static Map<String, Object> describeReport(Report report) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", report.getName());
		out.put("correlationId", report.getCorrelationId());
		List<Map<String, Object>> checkpoints = new ArrayList<>();
		for (Checkpoint checkpoint : report.getCheckpoints()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("type", checkpoint.getType());
			entry.put("name", checkpoint.getName());
			entry.put("level", checkpoint.getLevel());
			entry.put("message", truncate(checkpoint.getMessage(), MAX_CHECKPOINT_MESSAGE_CHARS));
			entry.put("mimeType", mimeTypeOf(checkpoint));
			checkpoints.add(entry);
		}
		out.put("checkpoints", checkpoints);
		return out;
	}

	/**
	 * The checkpoint message's real mime type, when the F!F Message carried one:
	 * MessageContext stores it under "Metadata.MimeType" (MessageContext.METADATA_MIMETYPE)
	 * and ladybug snapshots that map onto the checkpoint. Null when absent -- the
	 * portal's Output panel falls back to content sniffing then. Pure and
	 * null-safe (unit-tested without a live storage).
	 */
	static String mimeTypeOf(Checkpoint checkpoint) {
		Map<String, Object> context = checkpoint.getMessageContext();
		if (context == null) return null;
		Object mimeType = context.get("Metadata.MimeType");
		return mimeType == null ? null : mimeType.toString();
	}

	static int clampLimit(String raw) {
		if (raw == null) {
			return MAX_LIMIT;
		}
		try {
			int parsed = Integer.parseInt(raw);
			return Math.max(1, Math.min(MAX_LIMIT, parsed));
		} catch (NumberFormatException e) {
			return MAX_LIMIT;
		}
	}

	/** {@code /report/<id>} -> id; anything else -> null. */
	static Integer parseReportId(String pathInfo) {
		if (pathInfo == null || !pathInfo.startsWith("/report/")) {
			return null;
		}
		try {
			int parsed = Integer.parseInt(pathInfo.substring("/report/".length()));
			return parsed >= 0 ? parsed : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
