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
		observer.finishScenario(status, sc, LarvaTool.RESULT_ERROR, null);

		LarvaRunDocument.StepResult step = observer.document().scenarios.get(0).steps.get(0);
		assertTrue(step.truncated);
		assertEquals(JsonTestExecutionObserver.STEP_TEXT_MAX, step.expected.length());
		assertEquals(JsonTestExecutionObserver.STEP_TEXT_MAX, step.actualPrepared.length());
		assertTrue(step.message.length() <= JsonTestExecutionObserver.MESSAGE_MAX + 20, "message clipped");
	}

	@Test
	void messageErrorLandsInMessagesClipped() {
		JsonTestExecutionObserver observer = new JsonTestExecutionObserver();
		observer.messageError("boot", "e".repeat(2000));
		assertEquals(1, observer.document().messages.size());
		assertEquals("error", observer.document().messages.get(0).level);
		assertTrue(observer.document().messages.get(0).text.length() <= JsonTestExecutionObserver.MESSAGE_MAX + 20);
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
		assertTrue(doc.approximateBytes() <= JsonTestExecutionObserver.DOCUMENT_MAX);
		LarvaRunDocument.ScenarioResult last = doc.scenarios.get(199);
		assertEquals("failed", last.result);
		assertTrue(last.steps.isEmpty());
		assertNull(last.description);
		assertFalse(doc.scenarios.get(0).steps.isEmpty(), "leading scenarios keep their detail");
	}
}
