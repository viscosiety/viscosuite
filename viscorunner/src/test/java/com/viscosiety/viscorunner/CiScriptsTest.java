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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

	@Test
	void cleanupKeepsReleasesAndTheNewestTenMainVersions() throws Exception {
		StringBuilder in = new StringBuilder();
		for (int i = 20; i >= 1; i--) {                      // newest first
			in.append("2026-11-").append(String.format("%02d", i)).append("T00:00:00Z\t1.0.0-").append(i).append("\t").append(i).append('\n');
		}
		in.append("2026-10-01T00:00:00Z\t0.22.0\t99\n");    // a release, older than everything
		Path dir = Files.createTempDirectory("cleanup");
		Path input = dir.resolve("versions.tsv");
		Files.writeString(input, in.toString());
		Run r = sh(dir, Map.of("CLEANUP_DECIDE_ONLY", input.toString()), "/bin/sh", REPO.resolve("scripts/ci/cleanup-packages.sh").toString());
		assertEquals(0, r.exit(), r.err());
		List<String> deleted = r.out().lines().filter(l -> l.startsWith("delete ")).toList();
		assertEquals(10, deleted.size(), r.out());
		assertTrue(deleted.get(0).equals("delete 10 1.0.0-10") && deleted.get(9).equals("delete 1 1.0.0-1"), "the ten oldest main versions: " + deleted);
		assertFalse(r.out().lines().anyMatch(l -> l.startsWith("delete ") && l.contains("0.22.0")), "a release is never deleted");
		assertTrue(r.out().contains("keep 99 0.22.0 (release)"), r.out());
	}

	@Test
	void cleanupRefusesAKeepThatIsNotANumber() throws Exception {
		Path dir = Files.createTempDirectory("cleanup");
		Path input = dir.resolve("versions.tsv");
		Files.writeString(input, "");
		Run r = sh(dir, Map.of("CLEANUP_DECIDE_ONLY", input.toString(), "KEEP", "ten"), "/bin/sh", REPO.resolve("scripts/ci/cleanup-packages.sh").toString());
		assertEquals(2, r.exit());
		assertTrue(r.err().contains("KEEP must be a number"), r.err());
	}

	@Test
	void anUploadIsSkippedForTheSameBytesAndRefusedForOtherBytes() throws Exception {
		String lib = REPO.resolve("scripts/ci/lib/packages.sh").toString();
		for (String[] c : new String[][] {{"", "aa", "upload"}, {"aa", "aa", "skip"}, {"bb", "aa", "conflict"}}) {
			Run r = sh(REPO, Map.of(), "/bin/sh", "-c", ". " + lib + "; upload_decision '" + c[0] + "' '" + c[1] + "'");
			assertEquals(0, r.exit(), r.err());
			assertEquals(c[2], r.out());
		}
	}
}
