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

package com.viscosiety.filesystem;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import org.frankframework.filesystem.LocalFileSystem;

import com.viscosiety.components.ViscoLinkModule;

/**
 * The guards around ViscoLink's temporary {@link LocalFileSystem} classpath override (see that file's
 * banner, and frankframework/frankframework#12074). Each of these is a way the override could be lost
 * without any compile error, which would put path containment back to upstream's unchecked
 * {@code toFile}.
 */
class LocalFileSystemOverrideCheckTest {

	private static final String ALLOW_KEY = "application.security.filesystem.allowPathsOutsideRoot";

	@Test
	void theOverrideIsTheClassThatGetsLoaded() {
		assertDoesNotThrow(() -> new LocalFileSystemOverrideCheck().afterPropertiesSet());
	}

	@Test
	void theMarkerFieldTheCheckLooksForIsTheOneTheOverrideDeclares() throws Exception {
		assertEquals(LocalFileSystem.VISCO_OVERRIDE,
				LocalFileSystem.class.getDeclaredField(LocalFileSystemOverrideCheck.MARKER_FIELD).get(null));
	}

	/**
	 * A class without the marker is what a non-WAR layout looks like: {@code WEB-INF/classes} precedence
	 * is gone, {@code frankframework-filesystem.jar} wins, and its {@code LocalFileSystem} has no marker.
	 * The check must refuse rather than warn.
	 */
	@Test
	void aClassWithoutTheMarkerIsRefused() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> new LocalFileSystemOverrideCheck().readMarker(ClassWithoutMarker.class));

		assertTrue(e.getMessage().contains("NOT ViscoLink's root-containment override"), e.getMessage());
		assertTrue(e.getMessage().contains(LocalFileSystemOverrideCheck.MARKER_FIELD), e.getMessage());
	}

	/** A marker of the wrong shape is refused too, so the field cannot be hollowed out by accident. */
	@Test
	void aMarkerThatIsNotANonEmptyStringIsRefused() {
		assertThrows(IllegalStateException.class,
				() -> new LocalFileSystemOverrideCheck().readMarker(ClassWithEmptyMarker.class));
	}

	private static final class ClassWithoutMarker {
	}

	private static final class ClassWithEmptyMarker {
		@SuppressWarnings("unused")
		static final String VISCO_OVERRIDE = "";
	}

	/**
	 * The entry is load-bearing, not decoration: {@code ADDITIONAL.PROPERTIES.FILE} loads
	 * DeploymentSpecifics.properties AFTER the framework's AppConstants.properties and overrides it, and
	 * the F!F release carrying PR #12075 will ship this property as {@code true}.
	 */
	@Test
	void deploymentSpecificsPinsTheSafeValue() throws IOException {
		Properties properties = new Properties();
		try (InputStream in = getClass().getResourceAsStream("/DeploymentSpecifics.properties")) {
			assertTrue(in != null, "viscolink ships no DeploymentSpecifics.properties");
			properties.load(in);
		}

		assertEquals("false", properties.getProperty(ALLOW_KEY),
				ALLOW_KEY + " must be pinned to false in DeploymentSpecifics.properties");
	}

	/** Nothing in this WAR may ship a permissive entry; the Java field default has to stay reachable. */
	@Test
	void noShippedPropertiesFileTurnsContainmentOff() throws IOException {
		for (String resource : List.of("/DeploymentSpecifics.properties", "/StageSpecifics_LOC.properties",
				"/StageSpecifics_STUB.properties")) {
			Properties properties = new Properties();
			try (InputStream in = getClass().getResourceAsStream(resource)) {
				if (in == null) {
					continue;
				}
				properties.load(in);
			}
			assertFalse("true".equals(properties.getProperty(ALLOW_KEY)),
					resource + " turns LocalFileSystem root containment off");
		}
	}

	/** Without this registration the check never runs and the override could go missing unnoticed. */
	@Test
	void theCheckIsRegisteredAsASpringBeanOfTheModule() {
		assertTrue(new ViscoLinkModule().getSpringConfigurationFiles().contains("springLocalFileSystemOverride.xml"),
				"ViscoLinkModule does not register springLocalFileSystemOverride.xml");
	}
}
