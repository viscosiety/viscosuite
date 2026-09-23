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
 *
 * <p>Wire contract beyond spec §5's example (additive fields the portal must tolerate):</p>
 * <ul>
 *   <li>{@code scenarios[].messages[]} -- {@code {level, text}} entries Larva recorded on the
 *   scenario itself ({@code Scenario.getMessages()}): the reason a step failed WITHOUT a diff
 *   (timeouts, "Could not send message to ...", "Property 'x.className' not found", action
 *   creation errors, deprecation warnings). {@code level} is the lower-cased {@code LarvaLogLevel}
 *   name ({@code "error"}, {@code "warning"}, ...); {@code text} is clipped to
 *   {@link JsonTestExecutionObserver#MESSAGE_MAX} and, when Larva attached an exception, ends with
 *   {@code " (<ExceptionSimpleName>)"} -- never a stack trace. At most
 *   {@link #SCENARIO_MESSAGES_MAX} per scenario.</li>
 *   <li>{@code messagesDropped} -- how many run-level {@code messages} were not recorded because
 *   {@link #MESSAGES_MAX} was reached (0 normally).</li>
 *   <li>A scenario can carry a synthetic step named {@code "cleanup"}: Larva reports "Found one or
 *   more messages on actions or in database after scenario executed" as a step failure with no
 *   step, which lands here.</li>
 * </ul>
 *
 * <p>Size: the observer clips incrementally (see {@link JsonTestExecutionObserver}), so the
 * document stays near {@link #DOCUMENT_MAX} while it runs; {@link #clipToBudget()} is only the
 * end-of-run safety net.</p>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class LarvaRunDocument {

	public static final String STATE_RUNNING = "running";
	public static final String STATE_FINISHED = "finished";
	public static final String STATE_FAILED = "failed";

	/** Hard cap on the serialised document size; owned here so {@link #clipToBudget()} can enforce it directly. */
	public static final int DOCUMENT_MAX = 1024 * 1024;
	/** Run-level {@link #messages} cap; later ones only increment {@link #messagesDropped}. */
	public static final int MESSAGES_MAX = 200;
	/** Per-scenario {@link ScenarioResult#messages} cap. */
	public static final int SCENARIO_MESSAGES_MAX = 50;

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
	public int messagesDropped;
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
		public final List<LogMessage> messages = new ArrayList<>();

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

	/** Records a run-level message, or counts it in {@link #messagesDropped} once {@link #MESSAGES_MAX} is reached. */
	public void addMessage(String level, String text) {
		if (messages.size() >= MESSAGES_MAX) {
			messagesDropped++;
			return;
		}
		messages.add(new LogMessage(level, text));
	}

	/**
	 * The real serialised size in UTF-8 JSON bytes -- not a character-count estimate. A
	 * character-length sum undercounts JSON escaping (each {@code "} or {@code \} doubles in
	 * size) and multi-byte UTF-8 encoding of non-ASCII text, so only the actual encoded bytes
	 * can guarantee the {@link #DOCUMENT_MAX} cap.
	 */
	public int serializedBytes() {
		return bytesOf(this);
	}

	/** Serialised UTF-8 JSON size of any part of the document (one scenario, one message). */
	static int bytesOf(Object value) {
		try {
			return OBJECT_MAPPER.writeValueAsBytes(value).length;
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialise " + value.getClass().getSimpleName(), e);
		}
	}

	/** Reduces a scenario to {@code {path, result}}: steps, description, message and messages dropped. */
	static void reduce(ScenarioResult scenario) {
		scenario.steps.clear();
		scenario.messages.clear();
		scenario.description = null;
		scenario.message = null;
	}

	private static boolean isReduced(ScenarioResult scenario) {
		return scenario.steps.isEmpty() && scenario.messages.isEmpty() && scenario.description == null && scenario.message == null;
	}

	/**
	 * End-of-run safety net for the document cap (the observer already clips incrementally, so
	 * this normally returns after one serialisation). While over budget, the LAST detailed
	 * scenario is reduced to {@code {path, result}}, so the scenarios that ran first -- the ones
	 * a reader looks at first -- keep their diffs; if reducing every scenario is still not enough
	 * the run-level messages are dropped from the tail (counted in {@link #messagesDropped}).
	 * Linear: the whole document is serialised once and each reduced scenario twice.
	 */
	public void clipToBudget() {
		long total = serializedBytes();
		if (total <= DOCUMENT_MAX) {
			return;
		}
		for (int i = scenarios.size() - 1; i >= 0 && total > DOCUMENT_MAX; i--) {
			ScenarioResult scenario = scenarios.get(i);
			if (isReduced(scenario)) {
				continue;
			}
			int before = bytesOf(scenario);
			reduce(scenario);
			total -= before - bytesOf(scenario);
			clipped = true;
		}
		while (total > DOCUMENT_MAX && !messages.isEmpty()) {
			LogMessage last = messages.remove(messages.size() - 1);
			// +1 for the separating comma; a slight over-estimate of the saving is harmless because
			// the loop re-checks against a fresh serialisation below.
			total -= bytesOf(last) + 1L;
			messagesDropped++;
			clipped = true;
			if (total <= DOCUMENT_MAX) {
				total = serializedBytes();
			}
		}
	}
}
