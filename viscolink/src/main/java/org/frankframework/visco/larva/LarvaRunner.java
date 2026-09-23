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
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import org.frankframework.larva.LarvaConfig;
import org.frankframework.larva.LarvaLogLevel;
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
 * <p>A seam interface so the servlet tests can substitute a fake runner -- a real Larva run
 * needs a booted F!F application context.</p>
 */
@FunctionalInterface
public interface LarvaRunner {

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
			// LarvaTool writes its own log lines through this writer; the observer is what we read.
			tool.setWriter(new LarvaWriter(config, new StringWriter()));

			TestRunStatus status = tool.createTestRunStatus();
			status.initScenarioDirectories();
			// initScenarioDirectories keeps a preset activeScenariosDirectory; re-assert it in case
			// the property scan replaced it (it only does when the preset was empty).
			config.setActiveScenariosDirectory(rootDirectory);
			status.readScenarioFiles(tool.getScenarioLoader());

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
				observer.messageError("scenarios", "no scenarios found under [" + executeAbsolutePath + "]" + detail);
				observer.endTestSuiteExecution(status);
				return status;
			}
			ScenarioRunner runner = tool.createScenarioRunner(observer, status);
			runner.setMultipleThreads(false);
			long startTime = System.currentTimeMillis();
			runner.runScenarios(scenarios, rootDirectory);
			tool.flushOutput();
			observer.executionOverview(status, System.currentTimeMillis() - startTime);
			observer.endTestSuiteExecution(status);
			return status;
		};
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
