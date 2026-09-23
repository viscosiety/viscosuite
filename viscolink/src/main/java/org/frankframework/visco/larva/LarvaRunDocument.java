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

package org.frankframework.visco.larva;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The JSON document one Larva run produces -- the wire contract with the portal
 * (viscoFoundry spec 2026-09-23-larva-tests-design §5). Plain public fields serialised by
 * Jackson; nulls are written (the portal validates the shape and treats null as "unknown").
 *
 * <p>Mutable on purpose: the observer fills it while the run progresses and the servlet
 * hands out snapshots of the same object (state "running" until the worker finishes).</p>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class LarvaRunDocument {

	public static final String STATE_RUNNING = "running";
	public static final String STATE_FINISHED = "finished";
	public static final String STATE_FAILED = "failed";

	/** Hard cap on the serialised document size; owned here so {@link #clipToBudget()} can enforce it directly. */
	public static final int DOCUMENT_MAX = 1024 * 1024;

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	public String runId;
	public String state = STATE_RUNNING;
	public String configuration;
	public String execute;
	public String root;
	public String ref;
	public String commit;
	public String startedAt;
	public String finishedAt;
	public Long durationMs;
	public final Summary summary = new Summary();
	public final List<ScenarioResult> scenarios = new ArrayList<>();
	public final List<LogMessage> messages = new ArrayList<>();
	public String error;
	public boolean clipped;

	public static class Summary {
		public int total;
		public int passed;
		public int failed;
	}

	public static class LogMessage {
		public String level;
		public String text;

		public LogMessage(String level, String text) {
			this.level = level;
			this.text = text;
		}
	}

	public static class ScenarioResult {
		public String path;
		public String description;
		public String result;
		public String message;
		public final List<StepResult> steps = new ArrayList<>();

		public ScenarioResult(String path, String description) {
			this.path = path;
			this.description = description;
		}
	}

	public static class StepResult {
		public String name;
		public String result;
		public String message;
		public String expected;
		public String actual;
		public String expectedPrepared;
		public String actualPrepared;
		public boolean truncated;

		public StepResult(String name) {
			this.name = name;
		}
	}

	/**
	 * The real serialised size in UTF-8 JSON bytes -- not a character-count estimate. A
	 * character-length sum undercounts JSON escaping (each {@code "} or {@code \} doubles in
	 * size) and multi-byte UTF-8 encoding of non-ASCII text, so only the actual encoded bytes
	 * can guarantee the {@link #DOCUMENT_MAX} cap.
	 */
	public int serializedBytes() {
		try {
			return OBJECT_MAPPER.writeValueAsBytes(this).length;
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialise LarvaRunDocument", e);
		}
	}

	/**
	 * Enforces the document cap: while over budget, the LAST detailed scenario is reduced to
	 * {@code {path, result}} (steps, description and message dropped). Reduced from the tail so
	 * the scenarios that ran first -- the ones a reader looks at first -- keep their diffs.
	 */
	public void clipToBudget() {
		for (int i = scenarios.size() - 1; i >= 0 && serializedBytes() > DOCUMENT_MAX; i--) {
			ScenarioResult scenario = scenarios.get(i);
			if (scenario.steps.isEmpty() && scenario.description == null && scenario.message == null) {
				continue;
			}
			scenario.steps.clear();
			scenario.description = null;
			scenario.message = null;
			clipped = true;
		}
	}
}
