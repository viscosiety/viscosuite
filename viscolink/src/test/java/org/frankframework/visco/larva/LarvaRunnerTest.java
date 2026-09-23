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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import org.frankframework.core.SenderException;
import org.frankframework.larva.LarvaLogLevel;
import org.frankframework.larva.LarvaMessage;
import org.frankframework.larva.Scenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure pieces of {@link LarvaRunner}: {@link LarvaRunner#selectByPropertiesPath}
 * (replaces a direct call to {@code TestRunStatus.getScenariosToRun(String)} on a
 * {@code .properties} execute path, because that method NPEs on a miss) and the suite-deadline
 * loop {@link LarvaRunner#runWithinDeadline}. The real driver is covered end to end by
 * {@link LarvaRunnerLarvaToolTest}.
 */
class LarvaRunnerTest {

	private static Scenario scenario(File file) {
		return new Scenario(file, file.getName(), "desc", new Properties());
	}

	@Test
	void findsTheScenarioRegisteredUnderTheExactPath() {
		File file = new File("/root/OrdersIn/scenario01.properties");
		Scenario scenario = scenario(file);
		Map<Scenario.ID, Scenario> loaded = Map.of(scenario.getId(), scenario);

		List<Scenario> result = LarvaRunner.selectByPropertiesPath(loaded, file.getAbsolutePath());

		assertEquals(List.of(scenario), result);
	}

	@Test
	void returnsEmptyWhenTheFileWasNeverLoaded() {
		// Nothing was ever registered under this path -- the missing-file case from the
		// original bug (List.of(allScenarios.get(id)) would NPE here).
		Map<Scenario.ID, Scenario> loaded = Map.of();

		List<Scenario> result = LarvaRunner.selectByPropertiesPath(loaded, "/root/OrdersIn/missing.properties");

		assertTrue(result.isEmpty());
	}

	@Test
	void returnsEmptyWhenTheFileExistsButWasNotRegistered() {
		// The file is on disk (scenario.active=false, adapter.unstable=true, or no
		// scenario.description) but ScenarioLoader excluded it from allScenarios --
		// this is the case the review finding called out: the file exists, but the id
		// still misses the loaded map. List.of(allScenarios.get(id)) would NPE here too.
		Scenario other = scenario(new File("/root/OrdersIn/scenario01.properties"));
		Map<Scenario.ID, Scenario> loaded = Map.of(other.getId(), other);

		List<Scenario> result = LarvaRunner.selectByPropertiesPath(loaded, "/root/OrdersIn/unregistered.properties");

		assertTrue(result.isEmpty());
	}

	@Test
	void looksUpByScenarioIdEqualityNotObjectIdentity() {
		// The map key built from the scenario's own File and the key built from the raw
		// execute-path string are different Scenario.ID instances; the lookup must go through
		// Scenario.ID#equals (both derived from File#getAbsolutePath()), not object identity.
		File file = new File("/root/OrdersIn/scenario01.properties");
		Scenario scenario = scenario(file);
		Map<Scenario.ID, Scenario> loaded = Map.of(new Scenario.ID(file), scenario);

		List<Scenario> result = LarvaRunner.selectByPropertiesPath(loaded, new File(file.getAbsolutePath()).getAbsolutePath());

		assertEquals(List.of(scenario), result);
	}

	@Test
	void runsEveryScenarioInOrderWhileTheDeadlineHolds() {
		List<Scenario> scenarios = List.of(scenario(new File("/root/a.properties")), scenario(new File("/root/b.properties")));
		List<Scenario> ran = new ArrayList<>();
		JsonTestExecutionObserver observer = new JsonTestExecutionObserver();

		int count = LarvaRunner.runWithinDeadline(scenarios, s -> { ran.add(s); return null; }, () -> 0L, 1_000L, observer);

		assertEquals(2, count);
		assertEquals(scenarios, ran);
		assertTrue(observer.document().messages.isEmpty());
	}

	@Test
	void stopsBetweenScenariosOnceTheSuiteDeadlinePassesAndSaysSo() {
		List<Scenario> scenarios = List.of(scenario(new File("/root/a.properties")), scenario(new File("/root/b.properties")),
				scenario(new File("/root/c.properties")), scenario(new File("/root/d.properties")));
		AtomicLong now = new AtomicLong();
		List<Scenario> ran = new ArrayList<>();
		JsonTestExecutionObserver observer = new JsonTestExecutionObserver();

		// Each scenario "takes" 40 s; the deadline is 60 s -> a and b run (b crosses it but was
		// started before it), c and d are skipped.
		int count = LarvaRunner.runWithinDeadline(scenarios, s -> { ran.add(s); now.addAndGet(40_000L); return null; },
				now::get, 60_000L, observer);

		assertEquals(2, count);
		assertEquals(scenarios.subList(0, 2), ran);
		assertEquals(1, observer.document().messages.size());
		assertEquals("error", observer.document().messages.get(0).level);
		assertEquals("suite: suite deadline of 60 s reached after 2 of 4 scenarios; remaining skipped",
				observer.document().messages.get(0).text);
	}

	@Test
	void suiteDeadlineIsFourActionTimeoutsWithAFifteenMinuteFloor() {
		assertEquals(15 * 60_000L, LarvaRunner.suiteDeadlineMs(1_000L));
		assertEquals(15 * 60_000L, LarvaRunner.suiteDeadlineMs(120_000L));
		assertEquals(40 * 60_000L, LarvaRunner.suiteDeadlineMs(600_000L));
	}

	@Test
	void forwardsEveryErrorAndCapsWarningsWithASummaryLine() {
		// A big suite where many included scenarios override the same property warns once per
		// override -- 30 of those must never bury (or, via the document's own MESSAGES_MAX, push
		// out) the one real load ERROR.
		List<LarvaMessage> messages = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			messages.add(new LarvaMessage(LarvaLogLevel.WARNING, "warning " + i));
		}
		messages.add(new LarvaMessage(LarvaLogLevel.ERROR, "a real load error"));
		JsonTestExecutionObserver observer = new JsonTestExecutionObserver();

		LarvaRunner.forwardScenarioLoadMessages(messages, 0, observer);

		List<String> texts = observer.document().messages.stream().map(m -> m.text).toList();
		assertTrue(texts.stream().anyMatch(t -> t.contains("a real load error")), () -> "messages: " + texts);
		long forwardedWarnings = texts.stream().filter(t -> t.contains("warning ") && !t.contains("more warnings")).count();
		assertEquals(LarvaRunner.MAX_FORWARDED_SCENARIO_LOAD_WARNINGS, forwardedWarnings, () -> "messages: " + texts);
		assertTrue(texts.stream().anyMatch(t -> t.contains("and 10 more warnings while loading scenarios")),
				() -> "messages: " + texts);
	}

	@Test
	void forwardsEveryErrorEvenWhenThereAreNoWarningsToCap() {
		List<LarvaMessage> messages = List.of(
				new LarvaMessage(LarvaLogLevel.ERROR, "first error"),
				new LarvaMessage(LarvaLogLevel.ERROR, "second error"),
				new LarvaMessage(LarvaLogLevel.DEBUG, "not forwarded"));
		JsonTestExecutionObserver observer = new JsonTestExecutionObserver();

		LarvaRunner.forwardScenarioLoadMessages(messages, 0, observer);

		List<String> texts = observer.document().messages.stream().map(m -> m.text).toList();
		assertEquals(2, texts.size(), () -> "messages: " + texts);
		assertTrue(texts.stream().anyMatch(t -> t.contains("first error")));
		assertTrue(texts.stream().anyMatch(t -> t.contains("second error")));
	}

	@Test
	void loadMessageTextReservesRoomForTheExceptionSuffix() {
		// Mirrors JsonTestExecutionObserver#logMessage's reserved-suffix pattern: a long message
		// must not push the "(ExceptionClass)" suffix past what the observer's own MESSAGE_MAX clip
		// keeps, or the suffix is lost entirely.
		String longMessage = "m".repeat(1000);
		LarvaMessage message = new LarvaMessage(LarvaLogLevel.ERROR, longMessage, new SenderException("boom"));

		String text = LarvaRunner.loadMessageText(message);

		assertTrue(text.endsWith(" (SenderException)"), text);
		assertTrue(text.length() <= JsonTestExecutionObserver.MESSAGE_MAX, "leaves room for the \"scenarios: \" prefix too");
	}

	@Test
	void loadMessageTextWithNoExceptionIsJustTheMessage() {
		LarvaMessage message = new LarvaMessage(LarvaLogLevel.ERROR, "plain text");

		assertEquals("plain text", LarvaRunner.loadMessageText(message));
	}
}
