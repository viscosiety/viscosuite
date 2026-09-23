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
			if (executeAbsolutePath.endsWith(".properties") && !new File(executeAbsolutePath).isFile()) {
				scenarios = List.of();
			} else {
				scenarios = status.getScenariosToRun(executeAbsolutePath);
			}
			if (scenarios.isEmpty()) {
				observer.messageError("scenarios", "no scenarios found under [" + executeAbsolutePath + "]");
				observer.endTestSuiteExecution(status);
				return status;
			}
			ScenarioRunner runner = tool.createScenarioRunner(observer, status);
			runner.setMultipleThreads(false);
			runner.runScenarios(scenarios, rootDirectory);
			tool.flushOutput();
			observer.endTestSuiteExecution(status);
			return status;
		};
	}
}
