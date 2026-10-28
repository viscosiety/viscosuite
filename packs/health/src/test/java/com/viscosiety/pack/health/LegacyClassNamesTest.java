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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.frankframework.configuration.ConfigurationWarning;
import org.frankframework.core.PipeForward;
import org.frankframework.core.PipeLineSession;
import org.frankframework.pipes.FixedForwardPipe;
import org.frankframework.stream.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.annotation.AnnotationUtils;

/**
 * The pack's classes moved to {@code com.viscosiety.pack.health.*}; configurations written before the move
 * name the old classes. The old names stay as empty, deprecated subclasses that Frank!Framework reports as
 * a configuration warning.
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

	private static final String ADT_A01 = String.join("\r",
			"MSH|^~\\&|EPIC|HOSP|VISCO|LINK|20260101120000||ADT^A01|MSG0001|P|2.5",
			"EVN|A01|20260101120000",
			"PID|1||DEMO-12345^^^HOSP^MR||Doe^Jane||19800101|F",
			"PV1|1|I|WARD^101^1") + "\r";

	static Stream<Arguments> pairs() {
		return RENAMED.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
	}

	@ParameterizedTest
	@MethodSource("pairs")
	void oldNameIsADeprecatedSubclassThatWarns(String oldName, String newName) throws Exception {
		Class<?> old = Class.forName(oldName);
		Class<?> now = Class.forName(newName);
		assertEquals(now, old.getSuperclass());
		assertEquals(0, old.getDeclaredMethods().length, "a shim adds no behaviour");

		// Frank!Framework's ValidateAttributeRule looks the annotations up exactly like this on the bean class.
		Deprecated deprecated = AnnotationUtils.findAnnotation(old, Deprecated.class);
		ConfigurationWarning warning = AnnotationUtils.findAnnotation(old, ConfigurationWarning.class);
		assertNotNull(deprecated, oldName + " must be @Deprecated");
		assertTrue(deprecated.forRemoval());
		assertEquals("0.21.0", deprecated.since());
		assertNotNull(warning, oldName + " must carry @ConfigurationWarning");
		assertEquals("Use className=\"" + newName + "\"", warning.value());

		assertNull(AnnotationUtils.findAnnotation(now, Deprecated.class), newName + " must not warn");
		assertNull(AnnotationUtils.findAnnotation(now, ConfigurationWarning.class), newName + " must not warn");
	}

	@Test
	void oldPackagesHoldOnlyTheShims() throws IOException, URISyntaxException {
		Path classes = Paths.get(LegacyClassNamesTest.class.getResource("/springFhir.xml").toURI()).getParent();
		Set<String> found = new TreeSet<>();
		for (String pkg : new String[] {"fhir", "mllp", "pipes", "hl7util"}) {
			Path dir = classes.resolve("com/viscosiety").resolve(pkg);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (Stream<Path> files = Files.list(dir)) {
				files.map(p -> p.getFileName().toString())
						.filter(n -> n.endsWith(".class"))
						.forEach(n -> found.add("com.viscosiety." + pkg + "." + n.substring(0, n.length() - 6)));
			}
		}
		assertEquals(new TreeSet<>(RENAMED.keySet()), found);
	}

	@Test
	void oldNamePipeConvertsLikeTheNewOne() throws Exception {
		assertEquals(convert("com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe"),
				convert("com.viscosiety.pipes.Hl7v2ToXmlPipe"));
	}

	/** Instantiates the pipe by class name, as the digester does, and converts {@link #ADT_A01}. */
	private static String convert(String className) throws Exception {
		Object bean = Class.forName(className).getDeclaredConstructor().newInstance();
		com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe pipe = (com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe) bean;
		pipe.setName("parse");
		pipe.setHl7Version("2.5");
		pipe.setValidateMessage(false);
		((FixedForwardPipe) pipe).addForward(new PipeForward("success", "READY"));
		pipe.configure();
		try (PipeLineSession session = new PipeLineSession()) {
			String xml = pipe.doPipe(new Message(ADT_A01), session).getResult().asString();
			assertTrue(xml.contains("ADT_A01"), xml);
			return xml;
		}
	}
}
