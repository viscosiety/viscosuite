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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.lemmabase.lemma.Engine;
import com.lemmabase.lemma.EngineError;
import com.lemmabase.lemma.LemmaException;
import org.junit.jupiter.api.Test;

class LemmaInputsTest {

	private static final String SPEC = """
			spec parcel

			data weight: measure
			  -> unit kg: 1

			data length: measure
			  -> unit cm: 1
			  -> unit m: 100

			data express: boolean

			rule heavy:
			  weight > 10 kg
			""";

	@Test
	void registryImportFindsTheLemmaBaseLineOnly() {
		assertEquals(Optional.of("uses iso: @iso/countries alpha2"),
				LemmaInputs.registryImport("spec x\n\nuses lemma units\nuses iso: @iso/countries alpha2\n"));
		assertEquals(Optional.empty(), LemmaInputs.registryImport("spec x\n\nuses lemma units\n"));
		assertEquals(Optional.empty(), LemmaInputs.registryImport("spec x\n\"\"\"\nmail us @ example\n\"\"\"\n"));
	}

	@Test
	void singleUnitsListsOnlyMeasuresWithExactlyOneUnit() {
		try (Engine engine = Engine.create()) {
			engine.load(Map.of("parcel.lemma", SPEC));
			assertEquals(Map.of("weight", "kg"), LemmaInputs.singleUnits(engine.show(null, "parcel", null)));
		}
	}

	@Test
	void toDataAppendsTheOnlyUnitToABareNumberAndLeavesTheRestAlone() {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("weight", "12.5");
		values.put("length", "40");
		values.put("express", "true");
		values.put("ignored", null);
		values.put("blank", "  ");
		Map<String, Object> data = LemmaInputs.toData(values, Map.of("weight", "kg"));
		assertEquals("12.5 kg", data.get("weight"));
		assertEquals("40", data.get("length"), "two units: the caller must name one");
		assertEquals("true", data.get("express"));
		assertTrue(!data.containsKey("ignored"), "a null value is no data");
		assertTrue(!data.containsKey("blank"), "a blank value is no data either (the engine then reports it missing)");
		assertEquals("3 kg", LemmaInputs.toData(Map.of("weight", "3 kg"), Map.of("weight", "kg")).get("weight"),
				"a value that already carries a unit is left alone");
		assertEquals("-0.5 kg", LemmaInputs.toData(Map.of("weight", " -0.5 "), Map.of("weight", "kg")).get("weight"));
	}

	@Test
	void describeGivesFileLineAndColumn() {
		try (Engine engine = Engine.create()) {
			engine.load(Map.of("bad.lemma", "spec bad\n\nrule r: # comment\n  1\n"));
		} catch (LemmaException e) {
			String text = LemmaInputs.describe(e.errors());
			assertTrue(text.startsWith("bad.lemma:3:"), text);
			assertTrue(text.contains("Unexpected character '#'"), text);
			return;
		}
		throw new AssertionError("an inline comment must not load");
	}

	@Test
	void describeWithoutALocationIsTheMessage() {
		EngineError error = EngineError.request("Spec 'units' not found");
		assertEquals("Spec 'units' not found", LemmaInputs.describe(List.of(error)));
	}
}
