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
import java.util.Map;

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
 */
public class JsonTestExecutionObserver implements TestExecutionObserver {

	public static final int STEP_TEXT_MAX = 16 * 1024;
	public static final int DOCUMENT_MAX = 1024 * 1024;
	public static final int MESSAGE_MAX = 300;
	static final String TRUNCATION_SUFFIX = " ...[truncated]";

	private final LarvaRunDocument document;
	private final Map<Scenario, LarvaRunDocument.ScenarioResult> scenarioResults = new LinkedHashMap<>();
	private final Map<Step, LarvaRunDocument.StepResult> stepResults = new LinkedHashMap<>();

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
		LarvaRunDocument.ScenarioResult result = scenarioResults.computeIfAbsent(scenario, s -> {
			LarvaRunDocument.ScenarioResult late = new LarvaRunDocument.ScenarioResult(s.getName() + ".properties", s.getDescription());
			document.scenarios.add(late);
			return late;
		});
		if (scenarioResult == LarvaTool.RESULT_ERROR) {
			result.result = result.steps.isEmpty() ? "error" : "failed";
		} else {
			result.result = "passed";
		}
		result.message = clip(scenarioResultMessage, MESSAGE_MAX);
	}

	@Override
	public void startStep(TestRunStatus testRunStatus, Scenario scenario, Step step) {
		LarvaRunDocument.ScenarioResult owner = scenarioResults.get(scenario);
		if (owner == null) {
			startScenario(testRunStatus, scenario);
			owner = scenarioResults.get(scenario);
		}
		LarvaRunDocument.StepResult result = new LarvaRunDocument.StepResult(step.getBaseKey());
		stepResults.put(step, result);
		owner.steps.add(result);
	}

	@Override
	public void finishStep(TestRunStatus testRunStatus, Scenario scenario, Step step, int stepResult, String stepResultMessage) {
		LarvaRunDocument.StepResult result = stepResults.get(step);
		if (result == null) {
			startStep(testRunStatus, scenario, step);
			result = stepResults.get(step);
		}
		result.result = stepResult == LarvaTool.RESULT_ERROR ? "failed" : "passed";
		result.message = clip(stepResultMessage, MESSAGE_MAX);
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
		LarvaRunDocument.StepResult result = stepResults.get(step);
		if (result == null) {
			startStep(null, scenario, step);
			result = stepResults.get(step);
		}
		boolean truncated = over(stepExpectedResultMessage) || over(stepExpectedResultMessagePreparedForDiff)
				|| over(stepActualResultMessage) || over(stepActualResultMessagePreparedForDiff);
		result.expected = cut(stepExpectedResultMessage);
		result.expectedPrepared = cut(stepExpectedResultMessagePreparedForDiff);
		result.actual = cut(stepActualResultMessage);
		result.actualPrepared = cut(stepActualResultMessagePreparedForDiff);
		result.truncated = truncated;
	}

	@Override
	public void messageError(String description, String messageError) {
		String text = description == null || description.isBlank() ? messageError : description + ": " + messageError;
		document.messages.add(new LarvaRunDocument.LogMessage("error", clip(text, MESSAGE_MAX)));
	}

	private static boolean over(String text) {
		return text != null && text.length() > STEP_TEXT_MAX;
	}

	/** Hard cut at STEP_TEXT_MAX -- no suffix, the step's {@code truncated} flag says it was cut. */
	private static String cut(String text) {
		return text == null || text.length() <= STEP_TEXT_MAX ? text : text.substring(0, STEP_TEXT_MAX);
	}

	static String clip(String text, int max) {
		if (text == null || text.length() <= max) {
			return text;
		}
		return text.substring(0, max) + TRUNCATION_SUFFIX;
	}
}
