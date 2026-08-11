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
}
