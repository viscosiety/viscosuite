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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lemmabase.lemma.Engine;
import com.lemmabase.lemma.EvaluateRequest;
import com.lemmabase.lemma.Response;
import org.junit.jupiter.api.Test;

class LemmaResultsTest {

	private static final String SPEC = """
			spec drink

			data drink: text

			data guests: number

			rule price:
			  veto "Unknown drink"
			  unless drink is "tea" then 2
			  unless drink is "beer" then 4

			rule total:
			  price * guests

			rule label:
			  "drinks & snacks"
			""";

	private static Response evaluate(Map<String, Object> data, boolean explain) {
		try (Engine engine = Engine.create()) {
			engine.load(Map.of("drink.lemma", SPEC));
			return engine.evaluate(EvaluateRequest.of("drink").data(data).explain(explain));
		}
	}

	@Test
	void valuesVetoesAndMissingData() {
		Response ok = evaluate(Map.of("drink", "beer", "guests", "3"), false);
		assertEquals(Optional.of("12"), LemmaResults.valueText(ok.results().get("total")));
		assertEquals(Map.of(), LemmaResults.vetoes(ok.results()));

		Response veto = evaluate(Map.of("drink", "wine", "guests", "3"), false);
		assertEquals("Unknown drink", LemmaResults.vetoes(veto.results()).get("price"));
		assertTrue(LemmaResults.vetoes(veto.results()).containsKey("total"), "a veto propagates");

		Response missing = evaluate(Map.of("drink", "tea"), false);
		assertEquals(List.of("guests"), LemmaResults.missingData(missing.results()));
	}

	@Test
	void xmlEscapesTextAndMarksVetoes() {
		String xml = LemmaResults.xml(evaluate(Map.of("drink", "wine", "guests", "1"), false), false);
		assertTrue(xml.startsWith("<lemmaResult spec=\"drink\" effective=\""), xml);
		assertTrue(xml.contains("<result rule=\"label\" type=\"text\">drinks &amp; snacks</result>"), xml);
		assertTrue(xml.contains("<result rule=\"price\" vetoed=\"true\">Unknown drink</result>"), xml);
		assertTrue(xml.endsWith("</lemmaResult>"), xml);
	}

	@Test
	void explainAddsAnExplanationPerResult() throws Exception {
		String xml = LemmaResults.xml(evaluate(Map.of("drink", "tea", "guests", "2"), true), true);
		assertTrue(xml.contains("<explanation>"), xml);
		String json = LemmaResults.json(evaluate(Map.of("drink", "tea", "guests", "2"), true), true);
		JsonNode tree = new ObjectMapper().readTree(json);
		assertEquals("drink", tree.get("spec").asText());
		assertEquals("4", tree.get("results").get("total").get("result").asText());
		assertTrue(tree.get("results").get("total").has("explanation"));
	}
}
