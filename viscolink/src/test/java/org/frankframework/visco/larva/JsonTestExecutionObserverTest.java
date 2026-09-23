package org.frankframework.visco.larva;

import java.io.File;
import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.frankframework.larva.LarvaConfig;
import org.frankframework.larva.LarvaTool;
import org.frankframework.larva.Scenario;
import org.frankframework.larva.Step;
import org.frankframework.larva.TestRunStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class JsonTestExecutionObserverTest {

	private static Scenario scenario(String relPath, String... stepLines) {
		Properties props = new Properties();
		for (String line : stepLines) {
			props.setProperty(line, "dummy");
		}
		return new Scenario(new File("/root/" + relPath + ".properties"), relPath, "desc " + relPath, props);
	}

	private static TestRunStatus status() {
		return new TestRunStatus(new LarvaConfig(), mock(LarvaTool.class));
	}

	@Test
	void mapsScenarioAndStepResultsIntoTheDocument() throws Exception {
		JsonTestExecutionObserver observer = new JsonTestExecutionObserver();
		TestRunStatus status = status();
		Scenario ok = scenario("OrdersIn/scenario01", "step1.java.OrdersIn.write", "step2.java.OrdersIn.read");
		Step write = Step.of(ok, "step1.java.OrdersIn.write");
		Step read = Step.of(ok, "step2.java.OrdersIn.read");

		observer.startTestSuiteExecution(status);
		observer.startScenario(status, ok);
		observer.startStep(status, ok, write);
		observer.finishStep(status, ok, write, LarvaTool.RESULT_OK, null);
		observer.startStep(status, ok, read);
		observer.stepMessageFailed(ok, read, "compare", "<a/>", "<a/>", "<b/>", "<b/>");
		observer.finishStep(status, ok, read, LarvaTool.RESULT_ERROR, "differs");
		observer.finishScenario(status, ok, LarvaTool.RESULT_ERROR, "1 step failed");
		observer.endTestSuiteExecution(status);

		JsonNode doc = new ObjectMapper().valueToTree(observer.document());
		JsonNode sc = doc.get("scenarios").get(0);
		assertEquals("OrdersIn/scenario01.properties", sc.get("path").asText());
		assertEquals("desc OrdersIn/scenario01", sc.get("description").asText());
		assertEquals("failed", sc.get("result").asText());
		assertEquals("1 step failed", sc.get("message").asText());
		JsonNode s1 = sc.get("steps").get(0);
		assertEquals("step1.java.OrdersIn.write", s1.get("name").asText());
		assertEquals("passed", s1.get("result").asText());
		assertTrue(s1.get("expected").isNull());
		JsonNode s2 = sc.get("steps").get(1);
		assertEquals("failed", s2.get("result").asText());
		assertEquals("<a/>", s2.get("expected").asText());
		assertEquals("<b/>", s2.get("actual").asText());
		assertEquals("<a/>", s2.get("expectedPrepared").asText());
		assertEquals("<b/>", s2.get("actualPrepared").asText());
		assertFalse(s2.get("truncated").asBoolean());
	}

	@Test
	void autosavedCountsAsPassedAndAScenarioWithoutStepsThatFailsIsAnError() {
		JsonTestExecutionObserver observer = new JsonTestExecutionObserver();
		TestRunStatus status = status();
		Scenario saved = scenario("A/scenario01", "step1.x.write");
		observer.startScenario(status, saved);
		observer.finishScenario(status, saved, LarvaTool.RESULT_AUTOSAVED, null);
		Scenario broken = scenario("B/scenario01");
		observer.startScenario(status, broken);
		observer.finishScenario(status, broken, LarvaTool.RESULT_ERROR, "could not create action");

		LarvaRunDocument doc = observer.document();
		assertEquals("passed", doc.scenarios.get(0).result);
		assertEquals("error", doc.scenarios.get(1).result);
		assertEquals("could not create action", doc.scenarios.get(1).message);
	}

	@Test
	void clipsStepTextsAndMessages() {
		JsonTestExecutionObserver observer = new JsonTestExecutionObserver();
		TestRunStatus status = status();
		Scenario sc = scenario("A/scenario01", "step1.x.read");
		Step read = Step.of(sc, "step1.x.read");
		String big = "x".repeat(JsonTestExecutionObserver.STEP_TEXT_MAX + 100);
		observer.startScenario(status, sc);
		observer.startStep(status, sc, read);
		observer.stepMessageFailed(sc, read, "compare", big, big, big, big);
		observer.finishStep(status, sc, read, LarvaTool.RESULT_ERROR, "m".repeat(1000));
		observer.finishScenario(status, sc, LarvaTool.RESULT_ERROR, "n".repeat(1000));

		LarvaRunDocument.ScenarioResult scenarioResult = observer.document().scenarios.get(0);
		LarvaRunDocument.StepResult step = scenarioResult.steps.get(0);
		assertTrue(step.truncated);
		assertEquals(JsonTestExecutionObserver.STEP_TEXT_MAX, step.expected.length());
		assertEquals(JsonTestExecutionObserver.STEP_TEXT_MAX, step.actualPrepared.length());
		assertTrue(step.message.length() <= JsonTestExecutionObserver.MESSAGE_MAX, "message clipped INCLUDING the suffix");
		assertTrue(step.message.endsWith(JsonTestExecutionObserver.TRUNCATION_SUFFIX));
		assertTrue(scenarioResult.message.length() <= JsonTestExecutionObserver.MESSAGE_MAX, "scenario message clipped INCLUDING the suffix");
		assertTrue(scenarioResult.message.endsWith(JsonTestExecutionObserver.TRUNCATION_SUFFIX));
	}

	@Test
	void messageErrorLandsInMessagesClipped() {
		JsonTestExecutionObserver observer = new JsonTestExecutionObserver();
		observer.messageError("boot", "e".repeat(2000));
		assertEquals(1, observer.document().messages.size());
		assertEquals("error", observer.document().messages.get(0).level);
		String text = observer.document().messages.get(0).text;
		assertTrue(text.length() <= JsonTestExecutionObserver.MESSAGE_MAX, "messageError text clipped INCLUDING the suffix");
		assertTrue(text.endsWith(JsonTestExecutionObserver.TRUNCATION_SUFFIX));
	}

	@Test
	void handlesMissingStartCallsDefensively() {
		JsonTestExecutionObserver observer = new JsonTestExecutionObserver();
		TestRunStatus status = status();
		Scenario sc = scenario("A/scenario01", "step1.x.read");
		Step read = Step.of(sc, "step1.x.read");

		// finishScenario with no preceding startScenario still produces a scenario entry.
		observer.finishScenario(status, sc, LarvaTool.RESULT_OK, "ok");
		assertEquals(1, observer.document().scenarios.size());
		assertEquals("passed", observer.document().scenarios.get(0).result);

		// finishStep with no preceding startStep still produces a step entry under the scenario.
		observer.finishStep(status, sc, read, LarvaTool.RESULT_OK, "done");
		LarvaRunDocument.ScenarioResult scenarioResult = observer.document().scenarios.get(0);
		assertEquals(1, scenarioResult.steps.size());
		assertEquals("passed", scenarioResult.steps.get(0).result);

		// stepMessageFailed with no preceding startStep, on a fresh Step instance, also produces its own entry.
		Step other = Step.of(sc, "step1.x.read");
		observer.stepMessageFailed(sc, other, "compare", "<a/>", "<a/>", "<b/>", "<b/>");
		assertEquals(2, scenarioResult.steps.size());
		assertEquals("<a/>", scenarioResult.steps.get(1).expected);
	}

	@Test
	void documentBudgetReducesTrailingScenariosToPathAndResult() {
		LarvaRunDocument doc = new LarvaRunDocument();
		for (int i = 0; i < 200; i++) {
			LarvaRunDocument.ScenarioResult sc = new LarvaRunDocument.ScenarioResult("S" + i + "/scenario01.properties", "d");
			LarvaRunDocument.StepResult step = new LarvaRunDocument.StepResult("step1.x.read");
			step.result = "failed";
			step.expected = "e".repeat(JsonTestExecutionObserver.STEP_TEXT_MAX);
			step.actual = "a".repeat(JsonTestExecutionObserver.STEP_TEXT_MAX);
			sc.steps.add(step);
			sc.result = "failed";
			doc.scenarios.add(sc);
		}
		doc.clipToBudget();
		assertTrue(doc.clipped);
		assertTrue(doc.serializedBytes() <= LarvaRunDocument.DOCUMENT_MAX);
		LarvaRunDocument.ScenarioResult last = doc.scenarios.get(199);
		assertEquals("failed", last.result);
		assertTrue(last.steps.isEmpty());
		assertNull(last.description);
		assertFalse(doc.scenarios.get(0).steps.isEmpty(), "leading scenarios keep their detail");
	}

	@Test
	void documentBudgetUsesRealSerializedBytesNotACharCountEstimate() {
		// Quote-dense XML with a non-ASCII codepoint: each '"' doubles in size when JSON-escaped
		// (-> \") and 'é' takes 2 bytes in UTF-8 but only 1 java char -- a naive sum of
		// String#length() (what the old approximateBytes() estimate did) does not see either
		// inflation, so it can understate the real encoded size enough to skip clipping it should
		// have done.
		String unit = "<a x=\"é\"/>";
		String text = unit.repeat(1550); // 15500 chars, comfortably under STEP_TEXT_MAX
		LarvaRunDocument doc = new LarvaRunDocument();
		for (int i = 0; i < 30; i++) {
			LarvaRunDocument.ScenarioResult sc = new LarvaRunDocument.ScenarioResult("S" + i + "/scenario01.properties", "d");
			LarvaRunDocument.StepResult step = new LarvaRunDocument.StepResult("step1.x.read");
			step.result = "failed";
			step.expected = text;
			step.actual = text;
			sc.steps.add(step);
			sc.result = "failed";
			doc.scenarios.add(sc);
		}

		// Prove the setup: a plain character-count sum over every text field stays UNDER budget...
		long charCountEstimate = 0;
		for (LarvaRunDocument.ScenarioResult sc : doc.scenarios) {
			charCountEstimate += sc.path.length() + sc.description.length();
			for (LarvaRunDocument.StepResult step : sc.steps) {
				charCountEstimate += step.name.length() + step.expected.length() + step.actual.length();
			}
		}
		assertTrue(charCountEstimate < LarvaRunDocument.DOCUMENT_MAX,
				"test setup: a char-count estimate must understate the real size to prove the point");
		// ...while the real UTF-8 JSON bytes are OVER budget: the escaping/multi-byte encoding the
		// char count misses is exactly what clipToBudget() must react to.
		assertTrue(doc.serializedBytes() > LarvaRunDocument.DOCUMENT_MAX,
				"test setup: the real serialised size must exceed budget where the char-count estimate would not have caught it");

		doc.clipToBudget();
		assertTrue(doc.clipped);
		assertTrue(doc.serializedBytes() <= LarvaRunDocument.DOCUMENT_MAX);
	}
}
