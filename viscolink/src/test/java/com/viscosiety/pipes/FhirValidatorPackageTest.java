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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.frankframework.configuration.ConfigurationException;
import org.frankframework.core.PipeForward;
import org.frankframework.core.PipeLineSession;
import org.frankframework.core.PipeRunResult;
import org.frankframework.stream.Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Package-backed profile validation against the real Nictiz nl-core R4 packages.
 * The packages (.tgz) are NOT committed; run viscorunner/fhir-packages/download-packages.sh
 * first — when the directory is absent or empty these tests are skipped, not failed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FhirValidatorPackageTest {

	private static final Path PACKAGES = Path.of(
			System.getProperty("fhir.packages.dir", "../viscorunner/fhir-packages"));

	private FhirValidatorPipe pipe;

	@BeforeAll
	void setUp() throws Exception {
		boolean present;
		try (var s = Files.isDirectory(PACKAGES) ? Files.list(PACKAGES) : null) {
			present = s != null && s.anyMatch(p -> p.toString().endsWith(".tgz"));
		}
		assumeTrue(present, "nl-core packages not downloaded — skipping package validation tests");
		pipe = new FhirValidatorPipe();
		pipe.setName("validateNlCore");
		pipe.setFhirVersion("R4");
		pipe.setValidationPackages(PACKAGES.toString());
		pipe.setFailOnUnknownProfiles(true);
		pipe.addForward(new PipeForward("success", "OK"));
		pipe.addForward(new PipeForward("failure", "Invalid"));
		pipe.configure();
	}

	/** A conformant nl-core Patient: name.text populated, and each given name carries
	 * the ISO 21090 name-qualifier extension (BR = full given name) nl-core requires. */
	private static String patient(String bsn, String gender) {
		return """
				<Patient xmlns="http://hl7.org/fhir">
				  <meta><profile value="http://nictiz.nl/fhir/StructureDefinition/nl-core-Patient"/></meta>
				  <identifier>
				    <system value="http://fhir.nl/fhir/NamingSystem/bsn"/>
				    <value value="%s"/>
				  </identifier>
				  <name>
				    <use value="official"/>
				    <text value="Emma Janssen"/>
				    <family value="Janssen"/>
				    <given value="Emma">
				      <extension url="http://hl7.org/fhir/StructureDefinition/iso21090-EN-qualifier">
				        <valueCode value="BR"/>
				      </extension>
				    </given>
				  </name>
				  <gender value="%s"/>
				  <birthDate value="1984-03-12"/>
				</Patient>""".formatted(bsn, gender);
	}

	private String run(String input) throws Exception {
		try (PipeLineSession session = new PipeLineSession()) {
			PipeRunResult result = pipe.doPipe(new Message(input), session);
			String body = result.getResult().asString();
			return result.getPipeForward().getName() + "\n" + body;
		}
	}

	@Test
	void demoPatientConformsToNlCore() throws Exception {
		String outcome = run(patient("999900011", "female"));
		assertEquals("success", outcome.substring(0, outcome.indexOf('\n')), outcome);
	}

	@Test
	void resourceViolatingNlCoreIsRefused() throws Exception {
		// base-R4-valid, nl-core-invalid: a structured given name WITHOUT the
		// ISO 21090 name-qualifier extension nl-core requires on every given
		String probe = """
				<Patient xmlns="http://hl7.org/fhir">
				  <meta><profile value="http://nictiz.nl/fhir/StructureDefinition/nl-core-Patient"/></meta>
				  <identifier>
				    <system value="http://fhir.nl/fhir/NamingSystem/bsn"/>
				    <value value="999900011"/>
				  </identifier>
				  <name>
				    <use value="official"/>
				    <text value="Emma Janssen"/>
				    <family value="Janssen"/>
				    <given value="Emma"/>
				  </name>
				  <gender value="female"/>
				</Patient>""";
		String outcome = run(probe);
		assertEquals("failure", outcome.substring(0, outcome.indexOf('\n')), outcome);
	}

	@Test
	void unknownProfileNowFailsBecausePackagesMakeStrictModeMeaningful() throws Exception {
		String probe = """
				<Patient xmlns="http://hl7.org/fhir">
				  <meta><profile value="http://example.org/StructureDefinition/not-a-real-profile"/></meta>
				  <gender value="female"/>
				</Patient>""";
		String outcome = run(probe);
		assertEquals("failure", outcome.substring(0, outcome.indexOf('\n')), outcome);
	}

	@Test
	void misconfiguredPackagePathFailsConfigurationLoudly() {
		FhirValidatorPipe broken = new FhirValidatorPipe();
		broken.setName("broken");
		broken.setFhirVersion("R4");
		broken.setValidationPackages("/does/not/exist");
		broken.addForward(new PipeForward("success", "OK"));
		assertThrows(ConfigurationException.class, broken::configure);
	}
}
