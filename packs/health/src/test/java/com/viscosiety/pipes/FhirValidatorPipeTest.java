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

import org.frankframework.core.PipeForward;
import org.frankframework.core.PipeLineSession;
import org.frankframework.core.PipeRunException;
import org.frankframework.core.PipeRunResult;
import org.frankframework.stream.Message;
import org.junit.jupiter.api.Test;

class FhirValidatorPipeTest {

	private static final String VALID_PATIENT = """
			<Patient xmlns="http://hl7.org/fhir">
			  <name><family value="Janssen"/><given value="Emma"/></name>
			  <gender value="female"/>
			  <birthDate value="1984-03-12"/>
			</Patient>""";

	// gender carries a Dutch label instead of a code from administrative-gender:
	// HAPI refuses this at PARSE time (DataFormatException), not at validation time
	private static final String INVALID_GENDER = """
			<Patient xmlns="http://hl7.org/fhir"><gender value="onbekend"/></Patient>""";

	private FhirValidatorPipe pipe(boolean withFailureForward) throws Exception {
		FhirValidatorPipe pipe = new FhirValidatorPipe();
		pipe.setName("validateR4");
		pipe.setFhirVersion("R4");
		pipe.addForward(new PipeForward("success", "OK"));
		if (withFailureForward) {
			pipe.addForward(new PipeForward("failure", "Invalid"));
		}
		pipe.configure();
		return pipe;
	}

	@Test
	void unknownClaimedProfileIsNotARefusalByDefault() throws Exception {
		String withProfile = VALID_PATIENT.replace("<Patient xmlns=\"http://hl7.org/fhir\">",
				"<Patient xmlns=\"http://hl7.org/fhir\"><meta><profile value=\"http://nictiz.nl/fhir/StructureDefinition/nl-core-Patient\"/></meta>");
		FhirValidatorPipe pipe = pipe(true);
		try (PipeLineSession session = new PipeLineSession()) {
			assertEquals("success", pipe.doPipe(new Message(withProfile), session).getPipeForward().getName());
		}
	}

	@Test
	void unknownClaimedProfileRefusesWhenFailOnUnknownProfiles() throws Exception {
		String withProfile = VALID_PATIENT.replace("<Patient xmlns=\"http://hl7.org/fhir\">",
				"<Patient xmlns=\"http://hl7.org/fhir\"><meta><profile value=\"http://nictiz.nl/fhir/StructureDefinition/nl-core-Patient\"/></meta>");
		FhirValidatorPipe pipe = new FhirValidatorPipe();
		pipe.setName("validateR4");
		pipe.setFhirVersion("R4");
		pipe.setFailOnUnknownProfiles(true);
		pipe.addForward(new PipeForward("success", "OK"));
		pipe.addForward(new PipeForward("failure", "Invalid"));
		pipe.configure();
		try (PipeLineSession session = new PipeLineSession()) {
			assertEquals("failure", pipe.doPipe(new Message(withProfile), session).getPipeForward().getName());
		}
	}

	@Test
	void validResourceTakesSuccessForward() throws Exception {
		FhirValidatorPipe pipe = pipe(true);
		try (PipeLineSession session = new PipeLineSession()) {
			PipeRunResult result = pipe.doPipe(new Message(VALID_PATIENT), session);
			assertEquals("success", result.getPipeForward().getName());
		}
	}

	@Test
	void parseLevelRefusalTakesFailureForwardWithOperationOutcome() throws Exception {
		FhirValidatorPipe pipe = pipe(true);
		try (PipeLineSession session = new PipeLineSession()) {
			PipeRunResult result = pipe.doPipe(new Message(INVALID_GENDER), session);
			assertEquals("failure", result.getPipeForward().getName());
			String outcome = result.getResult().asString();
			assertTrue(outcome.contains("<OperationOutcome"), outcome);
			assertTrue(outcome.contains("onbekend"), outcome);
		}
	}

	@Test
	void jsonInputGetsJsonOperationOutcome() throws Exception {
		FhirValidatorPipe pipe = pipe(true);
		try (PipeLineSession session = new PipeLineSession()) {
			PipeRunResult result = pipe.doPipe(
					new Message("{\"resourceType\":\"Patient\",\"gender\":\"onbekend\"}"), session);
			assertEquals("failure", result.getPipeForward().getName());
			String outcome = result.getResult().asString();
			assertTrue(outcome.contains("\"resourceType\":\"OperationOutcome\""), outcome);
		}
	}

	@Test
	void parseFailureWithoutFailureForwardRaisesPipeRunException() throws Exception {
		FhirValidatorPipe pipe = pipe(false);
		try (PipeLineSession session = new PipeLineSession()) {
			PipeRunException e = assertThrows(PipeRunException.class,
					() -> pipe.doPipe(new Message(INVALID_GENDER), session));
			assertTrue(e.getMessage().contains("FHIR parse failed"), e.getMessage());
		}
	}
}
