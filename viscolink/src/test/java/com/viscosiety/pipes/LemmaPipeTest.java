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
package com.viscosiety.pipes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.frankframework.configuration.ConfigurationException;
import org.frankframework.core.PipeForward;
import org.frankframework.core.PipeLineSession;
import org.frankframework.core.PipeRunException;
import org.frankframework.core.PipeRunResult;
import org.frankframework.parameters.Parameter;
import org.frankframework.stream.Message;
import org.junit.jupiter.api.Test;

class LemmaPipeTest {

	private static Parameter param(String name, String sessionKey) {
		Parameter p = new Parameter();
		p.setName(name);
		p.setSessionKey(sessionKey);
		return p;
	}

	private static LemmaPipe pipe(String rulesFile, String rules, boolean vetoForward) {
		LemmaPipe pipe = new LemmaPipe();
		pipe.setName("decide");
		pipe.addForward(new PipeForward("success", "READY"));
		if (vetoForward) {
			pipe.addForward(new PipeForward(LemmaPipe.VETO_FORWARD_NAME, "ERROR"));
		}
		pipe.setRulesFile(rulesFile);
		pipe.setSpec("cocktail_advice");
		if (rules != null) {
			pipe.setRules(rules);
		}
		pipe.addParameter(param("temperature", "temp"));
		pipe.addParameter(param("weather_group", "group"));
		return pipe;
	}

	private static PipeRunResult run(LemmaPipe pipe, String temp, String group, PipeLineSession session) throws Exception {
		session.put("temp", temp);
		session.put("group", group);
		return pipe.doPipe(new Message("<ignored/>"), session);
	}

	@Test
	void evaluatesWithTheUnitConvenienceAndReturnsXml() throws Exception {
		LemmaPipe pipe = pipe("lemma/cocktail_advice.lemma", "ingredient,drink_profile", false);
		pipe.configure();
		try (PipeLineSession session = new PipeLineSession()) {
			PipeRunResult result = run(pipe, "27", "Clouds", session);
			assertEquals("success", result.getPipeForward().getName());
			String xml = result.getResult().asString();
			assertTrue(xml.contains("<result rule=\"ingredient\" type=\"text\">Tequila</result>"), xml);
			assertTrue(xml.contains("<result rule=\"drink_profile\" type=\"text\">refreshing-citrus</result>"), xml);
			assertTrue(!xml.contains("rule=\"is_hot\""), "only the requested rules: " + xml);
		}
	}

	@Test
	void resultSessionKeyPrefixStoresEachValue() throws Exception {
		LemmaPipe pipe = pipe("lemma/cocktail_advice.lemma", "ingredient,drink_profile", false);
		pipe.setResultSessionKeyPrefix("advice.");
		pipe.configure();
		try (PipeLineSession session = new PipeLineSession()) {
			run(pipe, "30 celsius", "Clear", session);
			assertEquals("Vodka", session.getString("advice.ingredient"));
			assertEquals("refreshing-citrus", session.getString("advice.drink_profile"));
		}
	}

	@Test
	void aVetoGoesToTheVetoForward() throws Exception {
		LemmaPipe pipe = pipe("lemma/cocktail_advice.lemma", "ingredient", true);
		pipe.configure();
		try (PipeLineSession session = new PipeLineSession()) {
			PipeRunResult result = run(pipe, "10", "Rain", session);
			assertEquals("veto", result.getPipeForward().getName());
			assertTrue(result.getResult().asString().contains("vetoed=\"true\">No ingredient below 25 degrees"));
		}
	}

	@Test
	void aVetoWithoutAVetoForwardThrowsWithTheReason() throws Exception {
		LemmaPipe pipe = pipe("lemma/cocktail_advice.lemma", "ingredient", false);
		pipe.configure();
		try (PipeLineSession session = new PipeLineSession()) {
			PipeRunException e = assertThrows(PipeRunException.class, () -> run(pipe, "10", "Rain", session));
			assertTrue(e.getMessage().contains("ingredient: No ingredient below 25 degrees"), e.getMessage());
		}
	}

	@Test
	void missingDataAlwaysThrows() throws Exception {
		LemmaPipe pipe = pipe("lemma/cocktail_advice.lemma", "ingredient", true);
		pipe.configure();
		try (PipeLineSession session = new PipeLineSession()) {
			session.put("group", "Rain");
			PipeRunException e = assertThrows(PipeRunException.class, () -> pipe.doPipe(new Message("x"), session));
			assertTrue(e.getMessage().contains("missing data: temperature"), e.getMessage());
		}
	}

	@Test
	void aBrokenSpecFailsConfigureWithFileLineAndColumn() {
		LemmaPipe pipe = pipe("lemma/broken.lemma", null, false);
		pipe.setSpec("broken");
		ConfigurationException e = assertThrows(ConfigurationException.class, pipe::configure);
		assertTrue(e.getMessage().contains("lemma/broken.lemma:3:"), e.getMessage());
	}

	@Test
	void configureRefusesWhatCannotWork() {
		LemmaPipe missingFile = pipe("lemma/nope.lemma", null, false);
		assertTrue(assertThrows(ConfigurationException.class, missingFile::configure).getMessage()
				.contains("cannot find rules file [lemma/nope.lemma]"));

		LemmaPipe registry = pipe("lemma/registry.lemma", null, false);
		registry.setSpec("with_registry");
		assertTrue(assertThrows(ConfigurationException.class, registry::configure).getMessage()
				.contains("LemmaBase imports are not supported"));

		LemmaPipe unknownSpec = pipe("lemma/cocktail_advice.lemma", null, false);
		unknownSpec.setSpec("nope");
		assertTrue(assertThrows(ConfigurationException.class, unknownSpec::configure).getMessage().contains("spec [nope]"));

		LemmaPipe unknownRule = pipe("lemma/cocktail_advice.lemma", "ingredient,price", false);
		assertTrue(assertThrows(ConfigurationException.class, unknownRule::configure).getMessage().contains("rule [price]"));

		LemmaPipe unknownParam = pipe("lemma/cocktail_advice.lemma", null, false);
		unknownParam.addParameter(param("humidity", "h"));
		assertTrue(assertThrows(ConfigurationException.class, unknownParam::configure).getMessage().contains("parameter [humidity]"));
	}

	@Test
	void jsonOutputAndEffectiveFromTheSession() throws Exception {
		LemmaPipe pipe = pipe("lemma/cocktail_advice.lemma", "drink_profile", false);
		pipe.setOutputType(LemmaPipe.OutputType.JSON);
		pipe.setEffectiveSessionKey("when");
		pipe.configure();
		try (PipeLineSession session = new PipeLineSession()) {
			session.put("when", "2026-01-01T00:00:00Z");
			String json = run(pipe, "5", "Snow", session).getResult().asString();
			assertTrue(json.contains("\"drink_profile\":{\"type\":\"text\",\"result\":\"balanced-classic\"}"), json);
			assertTrue(json.contains("\"effective\":\"2026-01-01"), json);
		}
	}

	@Test
	void aMalformedEffectiveValueIsAPipeRunExceptionWithItsCause() throws Exception {
		LemmaPipe pipe = pipe("lemma/cocktail_advice.lemma", "drink_profile", false);
		pipe.setEffectiveSessionKey("when");
		pipe.configure();
		try (PipeLineSession session = new PipeLineSession()) {
			session.put("when", "not-a-date");
			PipeRunException e = assertThrows(PipeRunException.class, () -> run(pipe, "5", "Snow", session));
			assertTrue(e.getCause() != null, "the cause is kept");
		}
	}
}
