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

package com.viscosiety.pack.health;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The pack's classes moved to {@code com.viscosiety.pack.health.*} (design 2026-10-28). The old names were kept
 * as deprecated subclasses until no tenant configuration used them; they are gone now, and nothing of the pack
 * may go back into the old packages.
 */
class LegacyClassNamesTest {

	static final Map<String, String> RENAMED = Map.of(
			"com.viscosiety.fhir.FhirListener", "com.viscosiety.pack.health.fhir.FhirListener",
			"com.viscosiety.mllp.MllpListener", "com.viscosiety.pack.health.mllp.MllpListener",
			"com.viscosiety.mllp.MllpSender", "com.viscosiety.pack.health.mllp.MllpSender",
			"com.viscosiety.pipes.Hl7v2ToXmlPipe", "com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe",
			"com.viscosiety.pipes.XmlToHl7v2Pipe", "com.viscosiety.pack.health.pipes.XmlToHl7v2Pipe",
			"com.viscosiety.pipes.FhirFormatPipe", "com.viscosiety.pack.health.pipes.FhirFormatPipe",
			"com.viscosiety.pipes.FhirValidatorPipe", "com.viscosiety.pack.health.pipes.FhirValidatorPipe");

	static Stream<Arguments> pairs() {
		return RENAMED.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
	}

	@ParameterizedTest
	@MethodSource("pairs")
	void onlyTheNewNameLoads(String oldName, String newName) {
		assertThrows(ClassNotFoundException.class, () -> Class.forName(oldName));
		assertDoesNotThrow(() -> Class.forName(newName));
	}

	@Test
	void theOldPackagesHoldNothingOfThePack() throws IOException, URISyntaxException {
		Path classes = Paths.get(LegacyClassNamesTest.class.getResource("/springFhir.xml").toURI()).getParent();
		Set<String> found = new TreeSet<>();
		for (String pkg : new String[] {"fhir", "mllp", "pipes", "hl7util"}) {
			Path dir = classes.resolve("com/viscosiety").resolve(pkg);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(dir)) {
				files.filter(p -> p.toString().endsWith(".class")).forEach(p -> found.add(classes.relativize(p).toString()));
			}
		}
		assertEquals(Set.of(), found);
	}
}
