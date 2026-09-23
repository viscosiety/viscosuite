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

import java.io.File;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.frankframework.larva.LarvaConfig;
import org.frankframework.larva.LarvaLogLevel;
import org.frankframework.larva.LarvaMessage;
import org.frankframework.larva.LarvaTool;
import org.frankframework.larva.Scenario;
import org.frankframework.larva.ScenarioRunner;
import org.frankframework.larva.TestRunStatus;
import org.frankframework.larva.output.LarvaWriter;
import org.frankframework.larva.output.TestExecutionObserver;
import org.springframework.context.ApplicationContext;

/**
 * Runs Larva scenarios under one root. The default implementation drives {@link LarvaTool}
 * piece by piece rather than through {@code LarvaTool.runScenarios(execute, observer, writer)}:
 * that entry point throws "No scenarios root directories found" whenever no
 * {@code scenariosrootN.directory} property exists, and the root here is chosen
 * programmatically ({@link LarvaConfig#setActiveScenariosDirectory}), independent of those
 * properties. The public pieces ({@code createTestRunStatus}, {@code initScenarioDirectories},
 * {@code readScenarioFiles}, {@code getScenariosToRun}, {@code createScenarioRunner}) are the same
 * ones the entry point calls.
 *
 * <p>Scenarios run one by one through {@link ScenarioRunner#runOneFile(Scenario, boolean)} (what
 * {@code ScenarioRunner.runScenarios} does single-threaded) so a suite deadline can be checked
 * between scenarios: {@code timeoutMs} is only Larva's per-action default, and a suite against a
 * down adapter would otherwise hold the single worker (and answer every later POST with 409) for
 * scenarios x steps x timeout. The deadline is derived, see {@link #suiteDeadlineMs(long)}; on
 * expiry the remaining scenarios are skipped and a run-level message says so. It is checked
 * BETWEEN scenarios only -- one scenario still runs to its end, and a step that hangs inside a
 * call Larva's timeout does not cover (an inline JDBC call without a query timeout) still needs
 * the pod deleted.</p>
 *
 * <p>A seam interface so the servlet tests can substitute a fake runner.</p>
 */
@FunctionalInterface
public interface LarvaRunner {

	/** Floor of the suite deadline: short action timeouts must not starve an ordinary suite. */
	long MIN_SUITE_DEADLINE_MS = 15 * 60_000L;

	/** Appended to the "no scenarios found" message: an unresolved include is a common cause. */
	String NO_SCENARIOS_INCLUDE_HINT = " -- or an include that does not resolve (include paths are "
			+ "relative to the scenario file's own folder)";

	TestRunStatus run(ApplicationContext applicationContext, String rootDirectory, String executeAbsolutePath,
			long timeoutMs, TestExecutionObserver observer) throws Exception;

	static LarvaRunner larvaTool() {
		return (applicationContext, rootDirectory, executeAbsolutePath, timeoutMs, observer) -> {
			LarvaConfig config = new LarvaConfig();
			config.setActiveScenariosDirectory(rootDirectory);
			config.setTimeout(timeoutMs);
			config.setWaitBeforeCleanup(100);
			config.setMultiThreaded(false);
			config.setLogLevel(LarvaLogLevel.WRONG_PIPELINE_MESSAGES);
			// Never write .expected files from here: the portal's git tree is the only writer.
			config.setEnableSaving(false);
			config.setAutoSaveDiffs(false);

			LarvaTool tool = new LarvaTool(applicationContext, config);
			// LarvaTool writes its own log lines (incl. wrong pipeline messages) through this writer; the
			// observer is what we read, so discard them rather than buffer a whole suite's worth.
			tool.setWriter(new LarvaWriter(config, Writer.nullWriter()));

			TestRunStatus status = tool.createTestRunStatus();
			status.initScenarioDirectories();
			// initScenarioDirectories keeps a preset activeScenariosDirectory; re-assert it in case
			// the property scan replaced it (it only does when the preset was empty).
			config.setActiveScenariosDirectory(rootDirectory);
			// Snapshot before loading: a scenario file that fails to load (e.g. an include= that does
			// not resolve) never becomes a Scenario -- it only shows up as a LarvaMessage on the tool,
			// which otherwise only reaches the discarded LarvaWriter. Forward just what loading added,
			// not the whole run's messages.
			int messagesBeforeLoad = tool.getMessages().size();
			status.readScenarioFiles(tool.getScenarioLoader());
			forwardScenarioLoadMessages(tool.getMessages(), messagesBeforeLoad, observer);

			observer.startTestSuiteExecution(status);
			List<Scenario> scenarios;
			if (executeAbsolutePath.endsWith(".properties")) {
				// TestRunStatus#getScenariosToRun does List.of(allScenarios.get(id)) for a
				// .properties path, which throws NPE the moment the id is a miss -- not just when
				// the file is absent, but also when it exists on disk yet ScenarioLoader never
				// registered it (scenario.active=false, adapter.unstable=true, or no
				// scenario.description). Resolve it ourselves first so we never call the real
				// method on a miss.
				scenarios = selectByPropertiesPath(status.getAllScenarios(), executeAbsolutePath);
				if (!scenarios.isEmpty()) {
					// Re-resolve through the library's own method, now that we know it is safe:
					// that call also records status.scenariosToRun (private, no public setter),
					// which TestRunStatus#getScenarioExecuteCount() and the observer's run totals
					// depend on. Selecting it ourselves without this step would silently leave the
					// execute count at 0 for a run that actually executed one scenario.
					scenarios = status.getScenariosToRun(executeAbsolutePath);
				}
			} else {
				// Directory-prefix and root selection only ever filter/copy allScenarios.values();
				// neither path can miss the way the .properties id lookup can.
				scenarios = status.getScenariosToRun(executeAbsolutePath);
			}
			if (scenarios.isEmpty()) {
				String detail = executeAbsolutePath.endsWith(".properties") && new File(executeAbsolutePath).isFile()
						? " (the file exists but was not registered as a scenario -- check scenario.active, "
								+ "adapter.unstable, and scenario.description)"
						: "";
				observer.messageError("scenarios", "no scenarios found under [" + executeAbsolutePath + "]" + detail + NO_SCENARIOS_INCLUDE_HINT);
				observer.endTestSuiteExecution(status);
				return status;
			}
			ScenarioRunner runner = tool.createScenarioRunner(observer, status);
			runner.setMultipleThreads(false);
			long startTime = System.currentTimeMillis();
			runWithinDeadline(scenarios, scenario -> runner.runOneFile(scenario, true), System::currentTimeMillis,
					suiteDeadlineMs(timeoutMs), observer);
			tool.flushOutput();
			observer.executionOverview(status, System.currentTimeMillis() - startTime);
			observer.endTestSuiteExecution(status);
			return status;
		};
	}

	/** The suite deadline for a per-action {@code timeoutMs}: four action timeouts, at least {@link #MIN_SUITE_DEADLINE_MS}. */
	static long suiteDeadlineMs(long timeoutMs) {
		return Math.max(timeoutMs * 4, MIN_SUITE_DEADLINE_MS);
	}

	/**
	 * Runs {@code scenarios} in order until the list ends or {@code suiteDeadlineMs} (measured
	 * with {@code clock} from the call) has passed; checked before each scenario, so the one that
	 * crosses the deadline still completes. On expiry reports
	 * {@code "suite deadline of N s reached after M of K scenarios; remaining skipped"} through
	 * {@code observer.messageError("suite", ...)}. Returns how many scenarios ran. Pulled out (like
	 * {@link #selectByPropertiesPath}) so the loop is unit-testable without a Larva runtime.
	 */
	static int runWithinDeadline(List<Scenario> scenarios, Function<Scenario, String> runOne, LongSupplier clock,
			long suiteDeadlineMs, TestExecutionObserver observer) {
		long start = clock.getAsLong();
		int ran = 0;
		for (Scenario scenario : scenarios) {
			if (clock.getAsLong() - start >= suiteDeadlineMs) {
				observer.messageError("suite", "suite deadline of " + (suiteDeadlineMs / 1000) + " s reached after "
						+ ran + " of " + scenarios.size() + " scenarios; remaining skipped");
				break;
			}
			runOne.apply(scenario);
			ran++;
		}
		return ran;
	}

	/**
	 * Forwards every {@link LarvaMessage} added to {@code allMessages} at or after
	 * {@code fromIndex} (i.e. produced while loading scenarios, via
	 * {@code ScenarioLoader}/{@code LarvaTool.errorMessage}) to the observer as a run-level
	 * message, e.g. {@code "Could not read properties file [...]: ..."} for an include that does
	 * not resolve. Only ERROR and WARNING are forwarded; a WARNING is prefixed {@code "warning: "}
	 * since {@link TestExecutionObserver#messageError} carries no level of its own. The observer
	 * clips the text and reduces an attached exception to its simple class name.
	 */
	static void forwardScenarioLoadMessages(List<LarvaMessage> allMessages, int fromIndex, TestExecutionObserver observer) {
		for (int i = fromIndex; i < allMessages.size(); i++) {
			LarvaMessage message = allMessages.get(i);
			LarvaLogLevel level = message.getLogLevel();
			if (level == LarvaLogLevel.ERROR) {
				observer.messageError("scenarios", loadMessageText(message));
			} else if (level == LarvaLogLevel.WARNING) {
				observer.messageError("scenarios", "warning: " + loadMessageText(message));
			}
		}
	}

	/** The message text plus, if Larva attached one, the exception's simple class name -- never a stack trace. */
	private static String loadMessageText(LarvaMessage message) {
		String text = message.getMessage() == null ? "" : message.getMessage();
		Exception exception = message.getException();
		return exception == null ? text : text + " (" + exception.getClass().getSimpleName() + ")";
	}

	/**
	 * Resolves a {@code .properties} execute path against the already-loaded scenarios, without
	 * going through {@link TestRunStatus#getScenariosToRun(String)} (see the caller's comment for
	 * why that method is unsafe to call directly here). Pulled out as its own method -- interface
	 * static methods are implicitly public, so it doubles as a directly unit-testable seam: plain
	 * {@link Scenario} fixtures, no booted F!F {@link ApplicationContext} needed.
	 */
	static List<Scenario> selectByPropertiesPath(Map<Scenario.ID, Scenario> loadedScenarios, String executeAbsolutePath) {
		Scenario scenario = loadedScenarios.get(new Scenario.ID(executeAbsolutePath));
		return scenario == null ? List.of() : List.of(scenario);
	}
}
