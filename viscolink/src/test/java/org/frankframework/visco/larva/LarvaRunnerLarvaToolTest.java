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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.frankframework.larva.TestRunStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the REAL {@link LarvaRunner#larvaTool()} (LarvaTool, ScenarioLoader, ScenarioRunner,
 * LarvaActionFactory, LarvaScenarioContext) against scenario files on disk. No adapter and no
 * IbisContext: the scenarios talk to {@code org.frankframework.senders.EchoSender} (writeline in,
 * read compared against a file), which Larva instantiates in its own per-scenario
 * {@code LarvaScenarioContext}; a plain refreshed {@link GenericApplicationContext} is enough as
 * that context's parent.
 */
class LarvaRunnerLarvaToolTest {

	private static final String ECHO = "java.echo.className=org.frankframework.senders.EchoSender\n";

	@TempDir Path tmp;

	private Path root;
	private GenericApplicationContext applicationContext;

	@BeforeEach
	void setUp() throws IOException {
		root = tmp.resolve("larva");
		write("OrdersIn/scenario01.properties", "scenario.description=echo matches\n" + ECHO
				+ "step1.java.echo.writeline=Echo This\nstep2.java.echo.read=out.txt\n");
		write("OrdersIn/scenario02.properties", "scenario.description=echo differs\n" + ECHO
				+ "step1.java.echo.writeline=Echo This\nstep2.java.echo.read=wrong.txt\n");
		write("OrdersIn/inactive.properties", "scenario.description=switched off\nscenario.active=false\n" + ECHO
				+ "step1.java.echo.writeline=Echo This\nstep2.java.echo.read=out.txt\n");
		write("OrdersIn/out.txt", "Echo This");
		write("OrdersIn/wrong.txt", "Something else");
		// A sibling whose name starts with "OrdersIn": F!F filters a directory execute with
		// startsWith, so ".../OrdersIn" without a trailing separator would also run this one.
		write("OrdersIn-rejects/scenario01.properties", "scenario.description=sibling\n" + ECHO
				+ "step1.java.echo.writeline=Echo This\nstep2.java.echo.read=out.txt\n");
		write("OrdersIn-rejects/out.txt", "Echo This");
		write("Broken/scenario01.properties", "scenario.description=unknown action\n" + ECHO
				+ "step1.java.echo.writeline=Echo This\nstep2.java.nope.read=out.txt\n");
		write("Broken/out.txt", "Echo This");
		applicationContext = new GenericApplicationContext();
		applicationContext.refresh();
	}

	@AfterEach
	void tearDown() {
		applicationContext.close();
	}

	private void write(String relative, String content) throws IOException {
		Path file = root.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	private LarvaRunDocument run(String executeAbsolutePath) throws Exception {
		JsonTestExecutionObserver observer = new JsonTestExecutionObserver();
		TestRunStatus status = LarvaRunner.larvaTool().run(applicationContext, root.toString(), executeAbsolutePath, 10_000L, observer);
		assertNotNull(status);
		return observer.document();
	}

	private static LarvaRunDocument.ScenarioResult scenario(LarvaRunDocument doc, String path) {
		return doc.scenarios.stream().filter(s -> s.path.equals(path)).findFirst()
				.orElseThrow(() -> new AssertionError("no scenario " + path + " in " + doc.scenarios.stream().map(s -> s.path).toList()));
	}

	@Test
	void aPassingScenarioIsReportedPassedWithItsSteps() throws Exception {
		LarvaRunDocument doc = run(root.resolve("OrdersIn/scenario01.properties").toString());

		assertEquals(1, doc.summary.total);
		assertEquals(1, doc.summary.passed);
		assertEquals(0, doc.summary.failed);
		LarvaRunDocument.ScenarioResult passed = scenario(doc, "OrdersIn/scenario01.properties");
		assertEquals("passed", passed.result, () -> "messages: " + doc.messages + " / " + passed.messages);
		assertEquals("echo matches", passed.description);
		assertEquals(List.of("step1.java.echo.writeline", "step2.java.echo.read"), passed.steps.stream().map(s -> s.name).toList());
		assertTrue(passed.steps.stream().allMatch(s -> "passed".equals(s.result)));
	}

	@Test
	void aFailingDiffCarriesExpectedAndActual() throws Exception {
		LarvaRunDocument doc = run(root.resolve("OrdersIn/scenario02.properties").toString());

		assertEquals(1, doc.summary.failed);
		LarvaRunDocument.ScenarioResult failed = scenario(doc, "OrdersIn/scenario02.properties");
		assertEquals("failed", failed.result);
		LarvaRunDocument.StepResult read = failed.steps.get(1);
		assertEquals("step2.java.echo.read", read.name);
		assertEquals("failed", read.result);
		assertNotNull(read.expected);
		assertNotNull(read.actual);
		assertTrue(read.expected.contains("Something else"), read.expected);
		assertTrue(read.actual.contains("Echo This"), read.actual);
	}

	@Test
	void aDirectoryExecuteWithTrailingSeparatorRunsOnlyThatDirectory() throws Exception {
		LarvaRunDocument doc = run(root.resolve("OrdersIn") + File.separator);

		assertEquals(List.of("OrdersIn/scenario01.properties", "OrdersIn/scenario02.properties"),
				doc.scenarios.stream().map(s -> s.path).sorted().toList(), "inactive scenario and OrdersIn-rejects excluded");
		assertEquals(2, doc.summary.total);
		assertEquals(1, doc.summary.passed);
		assertEquals(1, doc.summary.failed);
	}

	@Test
	void theRootExecuteRunsEveryRegisteredScenario() throws Exception {
		LarvaRunDocument doc = run(root.toString());

		assertEquals(List.of("Broken/scenario01.properties", "OrdersIn-rejects/scenario01.properties",
				"OrdersIn/scenario01.properties", "OrdersIn/scenario02.properties"),
				doc.scenarios.stream().map(s -> s.path).sorted().toList());
		assertEquals(4, doc.summary.total);
	}

	@Test
	void anUnregisteredPropertiesFileRunsNothingAndSaysWhy() throws Exception {
		LarvaRunDocument doc = run(root.resolve("OrdersIn/inactive.properties").toString());

		assertTrue(doc.scenarios.isEmpty());
		assertEquals(1, doc.messages.size());
		assertTrue(doc.messages.get(0).text.contains("was not registered as a scenario"), doc.messages.get(0).text);
	}

	@Test
	void withoutTheTrailingSeparatorLarvaAlsoMatchesSiblingDirectories() throws Exception {
		// Documents the F!F behaviour the servlet compensates for (it appends File.separator).
		LarvaRunDocument doc = run(root.resolve("OrdersIn").toString());

		assertTrue(doc.scenarios.stream().anyMatch(s -> s.path.equals("OrdersIn-rejects/scenario01.properties")));
	}

	@Test
	void aFailureWithoutADiffReachesTheScenarioMessages() throws Exception {
		LarvaRunDocument doc = run(root.resolve("Broken/scenario01.properties").toString());

		LarvaRunDocument.ScenarioResult broken = scenario(doc, "Broken/scenario01.properties");
		assertEquals("failed", broken.result);
		LarvaRunDocument.StepResult step = broken.steps.get(1);
		assertEquals("failed", step.result);
		assertNull(step.expected, "no diff for this failure");
		assertTrue(broken.messages.stream().anyMatch(m -> "error".equals(m.level) && m.text.contains("java.nope.className")),
				() -> "scenario messages: " + broken.messages.stream().map(m -> m.level + ":" + m.text).toList());
	}
}
