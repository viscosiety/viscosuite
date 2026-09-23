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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.frankframework.larva.LarvaMessage;
import org.frankframework.larva.LarvaTool;
import org.frankframework.larva.Scenario;
import org.frankframework.larva.Step;
import org.frankframework.larva.TestRunStatus;
import org.frankframework.larva.output.TestExecutionObserver;

/**
 * {@link TestExecutionObserver} that records a Larva run into a {@link LarvaRunDocument}
 * instead of rendering HTML/plain text. One instance per run; not thread-safe beyond the
 * single-threaded runner the servlet uses (multi-threaded Larva execution stays off).
 *
 * <p>Result mapping: {@code RESULT_OK} and {@code RESULT_AUTOSAVED} -> "passed" (autosave is
 * disabled in the runners, so the latter never happens, but it is a pass when it does);
 * {@code RESULT_ERROR} -> "failed" for a step, and for a scenario "failed" when at least one
 * step ran, "error" when none did (the scenario blew up before its first step: an action
 * class that could not be created, a missing include).</p>
 *
 * <p>Failure reasons without a diff: Larva records them on the {@link Scenario} itself
 * ({@code ScenarioRunner.scenarioError} -> {@code scenario.addError}) and writes them to the
 * discarded {@code LarvaWriter}, so the step only says "Step '...' failed".
 * {@link #finishScenario} copies {@code scenario.getMessages()} onto the scenario result as
 * {@code messages} ({@code {level, text}}, clipped, exception reduced to its simple class name).</p>
 *
 * <p>A {@code null} step: Larva's cleanup reports "Found one or more messages on actions or in
 * database after scenario executed" through {@code finishStep(..., null, RESULT_ERROR, ...)}; it
 * lands as a synthetic {@value #CLEANUP_STEP} step. Every other step callback ignores a null step.</p>
 *
 * <p>Size: each finished scenario is serialised once and added to a running total; once that
 * total would pass {@link LarvaRunDocument#DOCUMENT_MAX} minus {@link #DOCUMENT_HEADROOM}, the
 * finishing scenario is reduced to {@code {path, result}} and the document marked
 * {@code clipped} -- the first scenarios keep their detail and the document never grows past the
 * cap while running (each GET serialises it whole).</p>
 */
public class JsonTestExecutionObserver implements TestExecutionObserver {

	public static final int STEP_TEXT_MAX = 16 * 1024;
	/** Kept here (delegating to {@link LarvaRunDocument#DOCUMENT_MAX}) so callers of this class don't need to know the document owns the cap. */
	public static final int DOCUMENT_MAX = LarvaRunDocument.DOCUMENT_MAX;
	public static final int MESSAGE_MAX = 300;
	static final String TRUNCATION_SUFFIX = " ...[truncated]";
	static final String CLEANUP_STEP = "cleanup";
	/** Room kept free for the run-level fields and messages when deciding whether a scenario keeps its detail. */
	static final int DOCUMENT_HEADROOM = 64 * 1024;

	private final LarvaRunDocument document;
	private final Map<Scenario, LarvaRunDocument.ScenarioResult> scenarioResults = new LinkedHashMap<>();
	private final Map<Step, LarvaRunDocument.StepResult> stepResults = new LinkedHashMap<>();
	/** Serialised bytes of every finished scenario (detailed or reduced), kept incrementally. */
	private long scenarioBytes;

	public JsonTestExecutionObserver() {
		this(new LarvaRunDocument());
	}

	/** Records into {@code document} -- the servlet pre-fills runId/root/ref and hands out snapshots while the run progresses. */
	public JsonTestExecutionObserver(LarvaRunDocument document) {
		this.document = document;
	}

	public LarvaRunDocument document() {
		return document;
	}

	@Override
	public void startTestSuiteExecution(TestRunStatus testRunStatus) {
		// Nothing: the servlet stamps startedAt when it accepts the run.
	}

	@Override
	public void endTestSuiteExecution(TestRunStatus testRunStatus) {
		document.summary.passed = testRunStatus.getScenariosPassedCount() + testRunStatus.getScenariosAutosavedCount();
		document.summary.failed = testRunStatus.getScenariosFailedCount();
		document.summary.total = testRunStatus.getScenarioExecuteCount();
	}

	@Override
	public void executionOverview(TestRunStatus testRunStatus, long executionTime) {
		// The servlet measures its own duration; Larva's overview line is not needed.
	}

	@Override
	public void startScenario(TestRunStatus testRunStatus, Scenario scenario) {
		LarvaRunDocument.ScenarioResult result = new LarvaRunDocument.ScenarioResult(scenario.getName() + ".properties", scenario.getDescription());
		scenarioResults.put(scenario, result);
		document.scenarios.add(result);
	}

	@Override
	public void finishScenario(TestRunStatus testRunStatus, Scenario scenario, int scenarioResult, String scenarioResultMessage) {
		LarvaRunDocument.ScenarioResult result = scenarioResultFor(scenario);
		if (scenarioResult == LarvaTool.RESULT_ERROR) {
			result.result = result.steps.isEmpty() ? "error" : "failed";
		} else {
			result.result = "passed";
		}
		result.message = clip(scenarioResultMessage, MESSAGE_MAX);
		result.messages.clear();
		for (LarvaMessage message : scenario.getMessages()) {
			if (result.messages.size() >= LarvaRunDocument.SCENARIO_MESSAGES_MAX) {
				break;
			}
			result.messages.add(logMessage(message));
		}
		budget(result);
		// Step results of a finished scenario are never looked up again; don't pin them (and their
		// up-to-4x16 KiB texts, if the scenario was reduced) for the rest of the run.
		stepResults.keySet().removeIf(step -> step.getScenario() == scenario);
	}

	/** Keeps this scenario's detail while the running total allows it, else reduces it to {@code {path, result}}. */
	private void budget(LarvaRunDocument.ScenarioResult result) {
		int detailed = LarvaRunDocument.bytesOf(result);
		if (scenarioBytes + detailed > (long) DOCUMENT_MAX - DOCUMENT_HEADROOM) {
			LarvaRunDocument.reduce(result);
			document.clipped = true;
			scenarioBytes += LarvaRunDocument.bytesOf(result);
		} else {
			scenarioBytes += detailed;
		}
	}

	static LarvaRunDocument.LogMessage logMessage(LarvaMessage message) {
		String level = message.getLogLevel() == null ? "error" : message.getLogLevel().name().toLowerCase(Locale.ROOT);
		String text = message.getMessage() == null ? "" : message.getMessage();
		if (message.getException() == null) {
			return new LarvaRunDocument.LogMessage(level, clip(text, MESSAGE_MAX));
		}
		// The exception's class only -- its message is usually already part of Larva's text, and
		// neither a stack trace nor an unbounded exception body may reach the document.
		String suffix = " (" + message.getException().getClass().getSimpleName() + ")";
		return new LarvaRunDocument.LogMessage(level, clip(text, Math.max(0, MESSAGE_MAX - suffix.length())) + suffix);
	}

	private LarvaRunDocument.ScenarioResult scenarioResultFor(Scenario scenario) {
		return scenarioResults.computeIfAbsent(scenario, s -> {
			LarvaRunDocument.ScenarioResult late = new LarvaRunDocument.ScenarioResult(s.getName() + ".properties", s.getDescription());
			document.scenarios.add(late);
			return late;
		});
	}

	@Override
	public void startStep(TestRunStatus testRunStatus, Scenario scenario, Step step) {
		if (step == null) {
			return;
		}
		stepResultFor(scenario, step);
	}

	@Override
	public void finishStep(TestRunStatus testRunStatus, Scenario scenario, Step step, int stepResult, String stepResultMessage) {
		LarvaRunDocument.StepResult result;
		if (step == null) {
			// ScenarioRunner's cleanup: "Found one or more messages on actions or in database after
			// scenario executed" -- a failure that belongs to no step.
			result = new LarvaRunDocument.StepResult(CLEANUP_STEP);
			scenarioResultFor(scenario).steps.add(result);
		} else {
			result = stepResultFor(scenario, step);
		}
		result.result = stepResult == LarvaTool.RESULT_ERROR ? "failed" : "passed";
		// stepMessageFailed (called earlier for a failed compare) already recorded the real reason
		// (an XML diff / text diff description) as the message -- don't clobber it with Larva's
		// generic "Step '...' failed". Only a step that never went through a compare (or passed)
		// lands here with no message yet.
		if (result.message == null) {
			result.message = clip(stepResultMessage, MESSAGE_MAX);
		}
	}

	@Override
	public void stepMessage(Scenario scenario, Step step, String description, String stepMessage) {
		// Debug-level payload (the message sent into a write step); not part of the document.
	}

	@Override
	public void stepMessageSuccess(Scenario scenario, Step step, String description, String stepResultMessage, String stepResultMessagePreparedForDiff) {
		// A passed compare carries no diff worth storing.
	}

	@Override
	public void stepMessageFailed(Scenario scenario, Step step, String description, String stepExpectedResultMessage,
			String stepExpectedResultMessagePreparedForDiff, String stepActualResultMessage, String stepActualResultMessagePreparedForDiff) {
		if (step == null) {
			return;
		}
		LarvaRunDocument.StepResult result = stepResultFor(scenario, step);
		boolean truncated = over(stepExpectedResultMessage) || over(stepExpectedResultMessagePreparedForDiff)
				|| over(stepActualResultMessage) || over(stepActualResultMessagePreparedForDiff);
		result.expected = cut(stepExpectedResultMessage);
		result.expectedPrepared = cut(stepExpectedResultMessagePreparedForDiff);
		result.actual = cut(stepActualResultMessage);
		result.actualPrepared = cut(stepActualResultMessagePreparedForDiff);
		result.truncated = truncated;
		// The real compare reason -- XMLUnit's diff text, "Exception during XML diff: ...", or
		// "Starting at char N ..." for a text compare -- so it survives finishStep's generic message.
		result.message = clip(description, MESSAGE_MAX);
	}

	@Override
	public void messageError(String description, String messageError) {
		String text = description == null || description.isBlank() ? messageError : description + ": " + messageError;
		document.addMessage("error", clip(text, MESSAGE_MAX));
	}

	private LarvaRunDocument.StepResult stepResultFor(Scenario scenario, Step step) {
		return stepResults.computeIfAbsent(step, s -> {
			LarvaRunDocument.StepResult created = new LarvaRunDocument.StepResult(s.getBaseKey());
			scenarioResultFor(scenario).steps.add(created);
			return created;
		});
	}

	private static boolean over(String text) {
		return text != null && text.length() > STEP_TEXT_MAX;
	}

	/** Hard cut at STEP_TEXT_MAX -- no suffix, the step's {@code truncated} flag says it was cut. */
	private static String cut(String text) {
		return text == null || text.length() <= STEP_TEXT_MAX ? text : text.substring(0, STEP_TEXT_MAX);
	}

	/** Clips so the result INCLUDING the suffix never exceeds {@code max}. */
	static String clip(String text, int max) {
		if (text == null || text.length() <= max) {
			return text;
		}
		int cut = Math.max(0, max - TRUNCATION_SUFFIX.length());
		return text.substring(0, cut) + TRUNCATION_SUFFIX;
	}
}
