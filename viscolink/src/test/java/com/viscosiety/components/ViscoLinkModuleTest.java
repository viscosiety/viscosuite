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
package com.viscosiety.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class ViscoLinkModuleTest {

	@Test
	void doesNotRegisterTheRemovedK8sEventsSpringFile() {
		assertFalse(new ViscoLinkModule().getSpringConfigurationFiles().contains("springK8sEvents.xml"),
				"the old springK8sEvents.xml is gone: WARN/ERROR events come from the upstream frankframework-kubernetes module, the INFO lifecycle from springLifecycleEvents.xml");
	}

	@Test
	void registersOnlyTheCoresOwnSpringFiles() {
		// The healthcare Spring files (springMllp.xml, springFhir.xml) belong to the pack's own Module.
		assertEquals(List.of("springStubbedRun.xml", "springConsoleSecurity.xml", "springPoolWaitLimit.xml", "springLifecycleEvents.xml"),
				new ViscoLinkModule().getSpringConfigurationFiles());
	}

	@Test
	void theVersionIsTheBuildsAndNeverAToken() {
		String version = ViscoLinkModule.IMPLEMENTATION_VERSION;
		assertFalse(version.isBlank(), "version is set");
		assertFalse(version.contains("${"), "Maven filtered the version: " + version);
		assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?"), "A.B.C[-qualifier]: " + version);
	}

	@Test
	void theVersionIsWhatTheFilteredResourceSaysAndWhatTheManifestCarries() throws Exception {
		Properties props = new Properties();
		try (InputStream in = ViscoLinkModule.class.getResourceAsStream("/META-INF/viscolink-version.properties")) {
			props.load(in);
		}
		assertEquals(props.getProperty("version"), ViscoLinkModule.IMPLEMENTATION_VERSION);
		assertEquals(ViscoLinkModule.IMPLEMENTATION_VERSION,
				new ViscoLinkModule().getModuleInformation().getVersion(), "the manifest carries the same version");
	}
}
