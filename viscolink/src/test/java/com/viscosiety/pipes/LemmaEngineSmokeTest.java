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

import java.util.Map;

import com.lemmabase.lemma.Engine;
import com.lemmabase.lemma.EvaluateRequest;
import com.lemmabase.lemma.Response;
import com.lemmabase.lemma.RuleResult;
import org.junit.jupiter.api.Test;

/** The Java binding loads its native library on this platform and evaluates a spec. */
class LemmaEngineSmokeTest {

	@Test
	void loadsAndEvaluates() {
		try (Engine engine = Engine.create()) {
			engine.load(Map.of("smoke.lemma", """
					spec smoke

					data amount: number

					rule doubled:
					  amount * 2
					"""));
			Response response = engine.evaluate(EvaluateRequest.of("smoke").data(Map.of("amount", "21")));
			RuleResult.Number doubled = (RuleResult.Number) response.results().get("doubled");
			assertEquals("42", doubled.result());
		}
	}
}
