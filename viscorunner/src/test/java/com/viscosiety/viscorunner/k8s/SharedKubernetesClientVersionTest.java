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

package com.viscosiety.viscorunner.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The fabric8 client shaded into the viscorunner jar sits on Tomcat's common.loader, beside the
 * copy inside viscolink.war. Both register {@code HttpClient$Factory} services, and the webapp's
 * ServiceLoader sees the shared registrations too: when the versions differ, a factory name that
 * exists only in the shared copy is loaded from it and fails with "not a subtype", which stops the
 * whole /viscolink context (live 2026-11-01, Frank!Framework 10.3.0 moved the WAR to 8.0.0 while
 * this jar stayed on 7.7.0). The golden WAR list is kept true by HealthClasspathIT.
 */
class SharedKubernetesClientVersionTest {

	private static final Pattern WAR_CLIENT = Pattern.compile("^kubernetes-client-(\\d[^-]*)\\.jar$");

	@Test
	void sharedClientMatchesTheWarCopy() throws IOException {
		assertEquals(warClientVersion(), sharedClientVersion(),
				"viscorunner/pom.xml's io.fabric8:kubernetes-client must equal the version inside viscolink.war");
	}

	private static String warClientVersion() throws IOException {
		try (InputStream in = resource("/golden/health-webapp-libs.txt")) {
			List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
			for (String line : lines) {
				Matcher m = WAR_CLIENT.matcher(line.trim());
				if (m.matches()) {
					return m.group(1);
				}
			}
		}
		throw new AssertionError("no kubernetes-client jar in the golden WAR list");
	}

	private static String sharedClientVersion() throws IOException {
		Properties props = new Properties();
		try (InputStream in = resource("/META-INF/maven/io.fabric8/kubernetes-client/pom.properties")) {
			props.load(in);
		}
		return props.getProperty("version");
	}

	private static InputStream resource(String path) {
		InputStream in = SharedKubernetesClientVersionTest.class.getResourceAsStream(path);
		assertNotNull(in, path + " is not on the test class path");
		return in;
	}
}
