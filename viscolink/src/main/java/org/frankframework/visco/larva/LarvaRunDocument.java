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

	/** Cheap upper bound on the serialised size: every string's length plus a per-object overhead. */
	public int approximateBytes() {
		int total = 512;
		for (ScenarioResult scenario : scenarios) {
			total += 128 + len(scenario.path) + len(scenario.description) + len(scenario.message);
			for (StepResult step : scenario.steps) {
				total += 160 + len(step.name) + len(step.message) + len(step.expected) + len(step.actual)
						+ len(step.expectedPrepared) + len(step.actualPrepared);
			}
		}
		for (LogMessage message : messages) {
			total += 32 + len(message.text);
		}
		return total;
	}

	/**
	 * Enforces the document cap: while over budget, the LAST detailed scenario is reduced to
	 * {@code {path, result}} (steps, description and message dropped). Reduced from the tail so
	 * the scenarios that ran first -- the ones a reader looks at first -- keep their diffs.
	 */
	public void clipToBudget() {
		for (int i = scenarios.size() - 1; i >= 0 && approximateBytes() > JsonTestExecutionObserver.DOCUMENT_MAX; i--) {
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

	private static int len(String s) {
		return s == null ? 0 : s.length();
	}
}
