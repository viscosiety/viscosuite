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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.frankframework.configuration.ConfigurationException;
import org.frankframework.core.PipeForward;
import org.frankframework.core.PipeLineSession;
import org.frankframework.core.PipeRunException;
import org.frankframework.core.PipeRunResult;
import org.frankframework.stream.Message;
import org.junit.jupiter.api.Test;

class FhirFormatPipeTest {

	/** A one-entry R4 Bundle in FHIR XML — the single {@code entry} MUST become a JSON array. */
	private static final String BUNDLE_XML = """
			<Bundle xmlns="http://hl7.org/fhir">
			  <type value="transaction"/>
			  <entry>
			    <resource>
			      <Patient>
			        <identifier><system value="urn:visco:mrn"/><value value="MRN-1002"/></identifier>
			        <name><family value="Janssen"/><given value="Emma"/></name>
			      </Patient>
			    </resource>
			    <request><method value="POST"/><url value="Patient"/></request>
			  </entry>
			</Bundle>""";

	private static final String PATIENT_JSON =
			"{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Janssen\",\"given\":[\"Emma\"]}]}";

	private FhirFormatPipe configured(String outputFormat) throws ConfigurationException {
		FhirFormatPipe pipe = new FhirFormatPipe();
		pipe.setName("toTargetFormat");
		pipe.addForward(new PipeForward("success", "READY"));
		if (outputFormat != null) {
			pipe.setOutputFormat(outputFormat);
		}
		pipe.configure();
		return pipe;
	}

	private String run(FhirFormatPipe pipe, String input) throws PipeRunException, Exception {
		try (PipeLineSession session = new PipeLineSession()) {
			PipeRunResult result = pipe.doPipe(new Message(input), session);
			assertEquals("success", result.getPipeForward().getName());
			return result.getResult().asString();
		}
	}

	@Test
	void xmlToJsonKeepsSingleElementArrays() throws Exception {
		String json = run(configured("application/fhir+json"), BUNDLE_XML);
		// the FHIR-critical part a generic XML->JSON conversion gets wrong
		assertTrue(json.contains("\"entry\":[{"), "single entry must be a JSON array: " + json);
		assertTrue(json.contains("\"identifier\":[{"), "single identifier must be a JSON array");
		assertTrue(json.contains("\"given\":[\"Emma\"]"), "given is an array of primitives");
		assertTrue(json.contains("\"resourceType\":\"Bundle\""));
	}

	@Test
	void jsonToXmlProducesFhirNamespace() throws Exception {
		String xml = run(configured("application/fhir+xml"), PATIENT_JSON);
		assertTrue(xml.contains("<Patient xmlns=\"http://hl7.org/fhir\">"), xml);
		assertTrue(xml.contains("<family value=\"Janssen\""), xml);
	}

	@Test
	void xmlInputMayCarryLeadingWhitespaceOrDeclaration() throws Exception {
		assertTrue(run(configured("json"), "\n  " + BUNDLE_XML).contains("\"resourceType\":\"Bundle\""));
		String declared = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + BUNDLE_XML;
		assertTrue(run(configured("json"), declared).contains("\"resourceType\":\"Bundle\""));
	}

	@Test
	void shorthandsAndMimetypeParametersAreAccepted() throws Exception {
		assertTrue(run(configured("json"), BUNDLE_XML).startsWith("{"));
		assertTrue(run(configured("XML"), PATIENT_JSON).startsWith("<"));
		assertTrue(run(configured("application/fhir+json; fhirVersion=4.0"), BUNDLE_XML).startsWith("{"));
	}

	@Test
	void defaultOutputIsJson() throws Exception {
		assertTrue(run(configured(null), BUNDLE_XML).startsWith("{"));
	}

	@Test
	void jsonToJsonNormalises() throws Exception {
		// same-format input is fine: parse validates, encode canonicalises
		String json = run(configured("json"), PATIENT_JSON);
		assertTrue(json.contains("\"given\":[\"Emma\"]"));
	}

	@Test
	void prettyPrintIsHonoured() throws Exception {
		FhirFormatPipe pipe = new FhirFormatPipe();
		pipe.setName("toTargetFormat");
		pipe.addForward(new PipeForward("success", "READY"));
		pipe.setPrettyPrint(true);
		pipe.configure();
		assertTrue(run(pipe, BUNDLE_XML).contains("\n"));
		assertFalse(run(configured("json"), BUNDLE_XML).contains("\n"));
	}

	@Test
	void sessionKeyOverridesConfiguredOutputFormat() throws Exception {
		FhirFormatPipe pipe = new FhirFormatPipe();
		pipe.setName("toTargetFormat");
		pipe.addForward(new PipeForward("success", "READY"));
		pipe.setOutputFormat("application/fhir+json");
		pipe.setOutputFormatSessionKey("deliveryFormat");
		pipe.configure();

		try (PipeLineSession session = new PipeLineSession()) {
			session.put("deliveryFormat", "application/fhir+xml");
			String out = pipe.doPipe(new Message(PATIENT_JSON), session).getResult().asString();
			assertTrue(out.startsWith("<"), out);
		}
	}

	@Test
	void absentOrEmptySessionKeyFallsBackToConfiguredFormat() throws Exception {
		FhirFormatPipe pipe = new FhirFormatPipe();
		pipe.setName("toTargetFormat");
		pipe.addForward(new PipeForward("success", "READY"));
		pipe.setOutputFormat("application/fhir+xml");
		pipe.setOutputFormatSessionKey("deliveryFormat");
		pipe.configure();

		try (PipeLineSession session = new PipeLineSession()) {
			assertTrue(pipe.doPipe(new Message(PATIENT_JSON), session).getResult().asString().startsWith("<"));
		}
		try (PipeLineSession session = new PipeLineSession()) {
			session.put("deliveryFormat", "  ");
			assertTrue(pipe.doPipe(new Message(PATIENT_JSON), session).getResult().asString().startsWith("<"));
		}
	}

	@Test
	void unsupportedSessionKeyValueFailsTheMessage() throws Exception {
		FhirFormatPipe pipe = new FhirFormatPipe();
		pipe.setName("toTargetFormat");
		pipe.addForward(new PipeForward("success", "READY"));
		pipe.setOutputFormatSessionKey("deliveryFormat");
		pipe.configure();

		try (PipeLineSession session = new PipeLineSession()) {
			session.put("deliveryFormat", "text/csv");
			PipeRunException e = assertThrows(PipeRunException.class,
					() -> pipe.doPipe(new Message(PATIENT_JSON), session));
			assertTrue(e.getMessage().contains("deliveryFormat"), e.getMessage());
		}
	}

	@Test
	void unsupportedOutputFormatFailsConfiguration() {
		FhirFormatPipe pipe = new FhirFormatPipe();
		pipe.setName("toTargetFormat");
		pipe.addForward(new PipeForward("success", "READY"));
		pipe.setOutputFormat("text/csv");
		assertThrows(ConfigurationException.class, pipe::configure);
	}

	@Test
	void unsupportedFhirVersionFailsConfiguration() {
		FhirFormatPipe pipe = new FhirFormatPipe();
		pipe.setName("toTargetFormat");
		pipe.addForward(new PipeForward("success", "READY"));
		pipe.setFhirVersion("R99");
		assertThrows(ConfigurationException.class, pipe::configure);
	}

	@Test
	void unknownResourceTypeRaisesPipeRunException() throws Exception {
		FhirFormatPipe pipe = configured("json");
		try (PipeLineSession session = new PipeLineSession()) {
			PipeRunException e = assertThrows(PipeRunException.class,
					() -> pipe.doPipe(new Message("{\"resourceType\":\"NotAResource\"}"), session));
			assertTrue(e.getMessage().contains("FHIR parse failed (JSON, R4)"), e.getMessage());
		}
	}

	@Test
	void malformedXmlRaisesPipeRunException() throws Exception {
		FhirFormatPipe pipe = configured("json");
		try (PipeLineSession session = new PipeLineSession()) {
			PipeRunException e = assertThrows(PipeRunException.class,
					() -> pipe.doPipe(new Message("<Bundle xmlns=\"http://hl7.org/fhir\">"), session));
			assertTrue(e.getMessage().contains("FHIR parse failed (XML, R4)"), e.getMessage());
		}
	}

	@Test
	void emptyInputRaisesPipeRunException() throws Exception {
		FhirFormatPipe pipe = configured("json");
		try (PipeLineSession session = new PipeLineSession()) {
			assertThrows(PipeRunException.class, () -> pipe.doPipe(new Message(""), session));
		}
	}

	@Test
	void r5VersionIsSupported() throws Exception {
		FhirFormatPipe pipe = new FhirFormatPipe();
		pipe.setName("toTargetFormat");
		pipe.addForward(new PipeForward("success", "READY"));
		pipe.setFhirVersion("R5");
		pipe.configure();
		String json = run(pipe, PATIENT_JSON);
		assertTrue(json.contains("\"resourceType\":\"Patient\""));
	}
}
