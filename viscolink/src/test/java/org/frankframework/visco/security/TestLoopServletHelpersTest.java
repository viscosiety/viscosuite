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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure-helper coverage for the test-loop servlet family (the servlet request flows themselves
 * mirror ReloadConfigurationServlet's already-tested mechanics via the shared base class).
 */
class TestLoopServletHelpersTest {

	@Test
	void truncateLeavesShortTextAloneAndCutsLongText() {
		assertNull(AbstractBearerServiceServlet.truncate(null, 10));
		assertEquals("short", AbstractBearerServiceServlet.truncate("short", 10));
		String cut = AbstractBearerServiceServlet.truncate("x".repeat(20), 10);
		assertTrue(cut.startsWith("xxxxxxxxxx"));
		assertTrue(cut.endsWith("...[truncated]"));
	}

	@Test
	void clampLimitDefaultsAndClampsToTwenty() {
		assertEquals(20, LadybugServlet.clampLimit(null));
		assertEquals(20, LadybugServlet.clampLimit("banana"));
		assertEquals(20, LadybugServlet.clampLimit("500"));
		assertEquals(1, LadybugServlet.clampLimit("0"));
		assertEquals(5, LadybugServlet.clampLimit("5"));
	}

	@Test
	void parseReportIdAcceptsOnlyNumericReportPaths() {
		assertEquals(7, LadybugServlet.parseReportId("/report/7"));
		assertNull(LadybugServlet.parseReportId("/report/seven"));
		assertNull(LadybugServlet.parseReportId("/reports"));
		assertNull(LadybugServlet.parseReportId(null));
	}

	@Test
	void adaptersSummaryFlattensThePerAdapterMapTolerantly() throws Exception {
		var payload = new ObjectMapper().readTree("""
				{
				  "MyConfig/EchoAdapter": {"name": "EchoAdapter", "configuration": "MyConfig", "state": "started"},
				  "Bare": {"state": "stopped"}
				}
				""");
		List<Map<String, String>> rows = AdaptersServlet.summarize(payload);
		assertEquals(2, rows.size());
		assertEquals(Map.of("configuration", "MyConfig", "adapter", "EchoAdapter", "state", "started"), rows.get(0));
		assertEquals(Map.of("configuration", "", "adapter", "Bare", "state", "stopped"), rows.get(1));
	}

	@Test
	@SuppressWarnings("unchecked")
	void warningsSummarySlimsGlobalAndPerConfigWarningsTolerantly() throws Exception {
		var payload = new ObjectMapper().readTree("""
				{
				  "totalErrorStoreCount": 3,
				  "warnings": ["global plain string", {"message": "global object warning"}],
				  "messages": [{"date": 1, "message": "noise"}],
				  "tenant": {
				    "errorStoreCount": 3,
				    "warnings": ["EhCache attribute [keyXPath] has been deprecated since v10.2"],
				    "monitorsRaised": ["m1"]
				  },
				  "broken": {"exception": "Could not load"},
				  "quiet": {"errorStoreCount": 0}
				}
				""");
		Map<String, Object> out = WarningsServlet.summarize(payload);
		assertEquals(List.of("global plain string", "global object warning"), out.get("global"));
		var configurations = (List<Map<String, Object>>) out.get("configurations");
		assertEquals(2, configurations.size());
		assertEquals("tenant", configurations.get(0).get("configuration"));
		assertEquals(List.of("EhCache attribute [keyXPath] has been deprecated since v10.2"),
				configurations.get(0).get("warnings"));
		assertEquals("broken", configurations.get(1).get("configuration"));
		assertEquals("Could not load", configurations.get(1).get("exception"));
	}

	@Test
	void findStorageIdInRowsMatchesByCorrelationId() {
		List<List<Object>> rows = List.of(List.of(5, "corr-a"), List.of(7, "corr-b"));
		assertEquals(7, LadybugServlet.findStorageIdInRows(rows, "corr-b"));
		assertNull(LadybugServlet.findStorageIdInRows(rows, "corr-missing"));
	}

	@Test
	void payloadAsStringReadsStreamsAndStringifiesTheRest() throws Exception {
		// The bus hands back a streaming payload (SerializableInputStream); String.valueOf
		// on it produced "org.frankframework...SerializableInputStream@1096dba6" in the
		// live E2E (2026-08-11) instead of the pipeline result.
		var stream = new java.io.ByteArrayInputStream("<result/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertEquals("<result/>", TestPipelineServlet.payloadAsString(stream));
		assertEquals("plain", TestPipelineServlet.payloadAsString("plain"));
	}
}
