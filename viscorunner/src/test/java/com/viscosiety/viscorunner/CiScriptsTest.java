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

package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The CI shell scripts, run with sh against fixture trees (no GitLab, no network). */
class CiScriptsTest {

	static final Path REPO = Path.of("..").toAbsolutePath().normalize();
	static final String VERSION_SH = REPO.resolve("scripts/ci/version.sh").toString();

	record Run(int exit, String out, String err) {}

	static Run sh(Path cwd, Map<String, String> env, String... args) throws IOException, InterruptedException {
		assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs /bin/sh");
		ProcessBuilder pb = new ProcessBuilder(args).directory(cwd.toFile());
		pb.environment().keySet().removeIf(k -> k.startsWith("CI_"));
		pb.environment().putAll(env);
		Process p = pb.start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		return new Run(p.waitFor(), out.strip(), err.strip());
	}

	static Path pomWith(String version) throws IOException {
		Path dir = Files.createTempDirectory("version-sh");
		Files.writeString(dir.resolve("pom.xml"),
				"<project><groupId>com.visco</groupId><artifactId>viscosuite-parent</artifactId><version>" + version + "</version></project>\n");
		return dir;
	}

	@Test
	void mainCommitsGetTheBaseAndThePipelineId() throws Exception {
		Run r = sh(pomWith("1.0.0-SNAPSHOT"), Map.of("CI_PIPELINE_ID", "4242"), "/bin/sh", VERSION_SH);
		assertEquals(0, r.exit(), r.err());
		assertEquals("1.0.0-4242", r.out());
	}

	@Test
	void aReleaseTagGetsItsOwnNumber() throws Exception {
		Run r = sh(pomWith("1.0.0-SNAPSHOT"), Map.of("CI_PIPELINE_ID", "4242", "CI_COMMIT_TAG", "v1.0.0"), "/bin/sh", VERSION_SH);
		assertEquals(0, r.exit(), r.err());
		assertEquals("1.0.0", r.out());
	}

	@Test
	void aTagThatDoesNotMatchThePomBaseIsRefused() throws Exception {
		Run r = sh(pomWith("1.1.0-SNAPSHOT"), Map.of("CI_PIPELINE_ID", "1", "CI_COMMIT_TAG", "v1.0.0"), "/bin/sh", VERSION_SH);
		assertNotEquals(0, r.exit());
		assertTrue(r.err().contains("v1.0.0") && r.err().contains("1.1.0"), r.err());
	}

	@Test
	void aTagInAnotherShapeIsRefused() throws Exception {
		Run r = sh(pomWith("1.0.0-SNAPSHOT"), Map.of("CI_PIPELINE_ID", "1", "CI_COMMIT_TAG", "release-1"), "/bin/sh", VERSION_SH);
		assertNotEquals(0, r.exit());
		assertTrue(r.err().contains("vA.B.C"), r.err());
	}
}
