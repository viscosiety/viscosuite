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
	void cleanupDeletesOnlyUnsupportedVersionsOlderThanTheCutOff() throws Exception {
		Path dir = Files.createTempDirectory("cleanup");
		Path input = dir.resolve("versions.tsv");
		Files.writeString(input,
			"2026-11-20T10:00:00.000Z\t1.4.0-2070\t1\n"      // unsupported, but newer than the cut-off
			+ "2026-10-01T10:00:00.000Z\t1.3.0-1849\t2\n"    // unsupported and old: deleted
			+ "2026-09-01T10:00:00.000Z\t1.1.0\t3\n"         // a supported release, old: kept
			+ "2026-08-01T10:00:00.000Z\t1.0.0\t4\n"         // an unsupported release, old: deleted
			+ "2026-08-02T10:00:00.000Z\t1.1.0-1605\t5\n");  // a pre-release a ViscoForge release uses: kept
		Path keep = dir.resolve("keep.txt");
		Files.writeString(keep, "1.1.0\n1.1.0-1605\n1.3.0\n");
		Run r = sh(dir, Map.of("CLEANUP_DECIDE_ONLY", input.toString(), "KEEP_FILE", keep.toString(), "CUTOFF", "2026-10-24T00:00:00"),
			"/bin/sh", REPO.resolve("scripts/ci/cleanup-packages.sh").toString());
		assertEquals(0, r.exit(), r.err());
		List<String> deleted = r.out().lines().filter(l -> l.startsWith("delete ")).toList();
		assertEquals(List.of("delete 2 1.3.0-1849", "delete 4 1.0.0"), deleted, r.out());
		assertTrue(r.out().contains("keep 1 1.4.0-2070 (newer than the cut-off)"), r.out());
		assertTrue(r.out().contains("keep 3 1.1.0 (supported)"), r.out());
		assertTrue(r.out().contains("keep 5 1.1.0-1605 (supported)"), r.out());
	}

	@Test
	void cleanupRefusesAMinimumAgeThatIsNotANumber() throws Exception {
		Path dir = Files.createTempDirectory("cleanup");
		Path input = dir.resolve("versions.tsv");
		Files.writeString(input, "");
		Run r = sh(dir, Map.of("CLEANUP_DECIDE_ONLY", input.toString(), "MIN_AGE_DAYS", "thirty"), "/bin/sh", REPO.resolve("scripts/ci/cleanup-packages.sh").toString());
		assertEquals(2, r.exit());
		assertTrue(r.err().contains("MIN_AGE_DAYS must be a number"), r.err());
	}

	@Test
	void cleanupDeletesNothingWithoutTheSupportedReleasesList() throws Exception {
		Path dir = Files.createTempDirectory("cleanup");
		Run r = sh(dir, Map.of("RELEASE_LIST_TOKEN", "x", "RELEASE_LIST_URL", "http://127.0.0.1:9/none", "CI_API_V4_URL", "http://127.0.0.1:9", "CI_PROJECT_ID", "2"),
			"/bin/sh", REPO.resolve("scripts/ci/cleanup-packages.sh").toString());
		assertEquals(1, r.exit(), r.out());
		assertTrue(r.err().contains("nothing deleted"), r.err());
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

	private Run publishedShaWithFakeCurl(long recordedSize, byte[] served) throws Exception {
		return publishedShaWithFakeCurl(recordedSize, served, "ok");
	}

	private Run publishedShaWithFakeCurl(long recordedSize, byte[] served, String mode) throws Exception {
		return publishedShaWithFakeCurl(recordedSize, served, mode, "x", null);
	}

	private Run publishedShaWithFakeCurl(long recordedSize, byte[] served, String mode, String token, Path argvLog) throws Exception {
		Path dir = Files.createTempDirectory("fakecurl");
		Path fixture = dir.resolve("fixture.bin");
		Files.write(fixture, served);
		Path curl = dir.resolve("curl");
		Files.writeString(curl, "#!/bin/sh\n"
				+ "out=''; url=''\n"
				+ "[ -z \"${CURL_ARGV_LOG:-}\" ] || for a in \"$@\"; do printf '%s\\n' \"$a\" >> \"$CURL_ARGV_LOG\"; done\n"
				+ "while [ $# -gt 0 ]; do case \"$1\" in --output) out=$2; shift 2 ;; --header|--config) shift 2 ;; -*) shift ;; *) url=$1; shift ;; esac; done\n"
				+ "emit() { if [ -n \"$out\" ]; then printf '%s\\n' \"$1\" > \"$out\"; else printf '%s\\n' \"$1\"; fi; }\n"
				+ "case \"$url\" in\n"
				+ "  */packages\\?*) " + ("fail-listing".equals(mode) ? "exit 22" : "not-json".equals(mode) ? "emit 'not json'" : "other-version".equals(mode) ? "emit '[{\"id\":7,\"name\":\"viscolink\",\"version\":\"9.9.9\"}]'" : "newline".equals(mode) ? "emit ''" : "empty".equals(mode) ? ": > \"$out\"" : "non-array".equals(mode) ? "emit '{}'" : "emit '[{\"id\":7,\"name\":\"com/visco/viscostore\",\"version\":\"1.0.0-1\"}]'") + " ;;\n"
				+ "  */packages/7/package_files*) " + ("fail-files".equals(mode) ? "exit 22" : "emit '[{\"file_name\":\"viscostore-1.0.0-1.war\",\"size\":" + recordedSize + ",\"file_sha256\":null}]'") + " ;;\n"
				+ "  */packages/maven/*) cp '" + fixture + "' \"$out\" ;;\n"
				+ "  *) echo \"unexpected $url\" >&2; exit 22 ;;\n"
				+ "esac\n");
		assertTrue(curl.toFile().setExecutable(true));
		String lib = REPO.resolve("scripts/ci/lib/packages.sh").toString();
		Map<String, String> env = new java.util.HashMap<>(Map.of("PATH", dir + ":" + System.getenv("PATH"),
				"CI_API_V4_URL", "https://example.invalid/api/v4", "CI_PROJECT_ID", "2", "CI_JOB_TOKEN", token, "VERSION", "1.0.0-1"));
		if (argvLog != null) env.put("CURL_ARGV_LOG", argvLog.toString());
		return sh(REPO, env, "/bin/sh", "-c", ". " + lib + "; published_sha com/visco/viscostore viscostore-1.0.0-1.war");
	}

	@Test
	void aMissingApiSha256IsComputedFromTheServedBytes() throws Exception {
		byte[] bytes = "served war bytes".getBytes(StandardCharsets.UTF_8);
		Run r = publishedShaWithFakeCurl(bytes.length, bytes);
		assertEquals(0, r.exit(), r.err());
		String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
		assertEquals(expected, r.out());
		Run bad = publishedShaWithFakeCurl(bytes.length + 1, bytes);
		assertNotEquals(0, bad.exit());
		assertTrue(bad.err().contains("registry records"), bad.err());
	}

	@Test
	void aFailingPackageListingIsAnErrorNotNotPublished() throws Exception {
		Run r = publishedShaWithFakeCurl(1, new byte[] {1}, "fail-listing");
		assertNotEquals(0, r.exit(), "an API failure must not read as 'not published'");
		assertEquals("", r.out());
		assertTrue(r.err().contains("cannot list packages"), r.err());
	}

	@Test
	void aNonJsonPackageListingIsAnError() throws Exception {
		Run r = publishedShaWithFakeCurl(1, new byte[] {1}, "not-json");
		assertNotEquals(0, r.exit());
		assertEquals("", r.out());
		assertTrue(r.err().contains("cannot read the package listing"), r.err());
	}

	@Test
	void aFailingFileListingIsAnError() throws Exception {
		Run r = publishedShaWithFakeCurl(1, new byte[] {1}, "fail-files");
		assertNotEquals(0, r.exit());
		assertEquals("", r.out());
		assertTrue(r.err().contains("cannot list files"), r.err());
	}

	@Test
	void anEmptyPackageListingIsAnError() throws Exception {
		Run r = publishedShaWithFakeCurl(1, new byte[] {1}, "empty");
		assertNotEquals(0, r.exit());
		assertEquals("", r.out());
		assertTrue(r.err().contains("empty package listing"), r.err());
	}

	@Test
	void aNonArrayPackageListingIsAnError() throws Exception {
		Run r = publishedShaWithFakeCurl(1, new byte[] {1}, "non-array");
		assertNotEquals(0, r.exit());
		assertEquals("", r.out());
		assertTrue(r.err().contains("cannot read the package listing"), r.err());
	}

	@Test
	void theTokenIsNotOnCurlsCommandLine() throws Exception {
		byte[] bytes = "served war bytes".getBytes(StandardCharsets.UTF_8);
		Path log = Files.createTempFile("argv", ".log");
		Run r = publishedShaWithFakeCurl(bytes.length, bytes, "ok", "tok-secret-xyz", log);
		assertEquals(0, r.exit(), r.err());
		String argv = Files.readString(log);
		assertFalse(argv.contains("tok-secret-xyz"), argv);
		assertTrue(argv.contains("--config"), argv);
	}

	@Test
	void aWhitespaceOnlyPackageListingIsAnError() throws Exception {
		Run r = publishedShaWithFakeCurl(1, new byte[] {1}, "newline");
		assertNotEquals(0, r.exit());
		assertEquals("", r.out());
		assertTrue(r.err().contains("cannot read the package listing"), r.err());
	}

	@Test
	void aListingWithoutTheVersionMeansNotPublished() throws Exception {
		Run r = publishedShaWithFakeCurl(1, new byte[] {1}, "other-version");
		assertEquals(0, r.exit(), r.err());
		assertEquals("", r.out());
		assertEquals("", r.err());
	}

	static final String CHECK_CHANGELOG_SH = REPO.resolve("scripts/ci/check-changelog.sh").toString();

	static Run checkChangelog(String content, String tag) throws Exception {
		Path dir = Files.createTempDirectory("changelog");
		Files.writeString(dir.resolve("CHANGELOG.md"), content);
		Map<String, String> env = tag == null ? Map.of() : Map.of("CI_COMMIT_TAG", tag);
		return sh(dir, env, "/bin/sh", CHECK_CHANGELOG_SH);
	}

	static final String GOOD_CHANGELOG = "# Changelog\n\nIntro.\n\n## [Unreleased]\n\n"
			+ "## [1.1.0] — 2026-11-12\n\n### In short\nThe images run on Java 25. Nothing to do.\n\n### Changed\n- detail\n\n"
			+ "## [1.0.0] - 2026-11-04\n\n### Added\n- old\n";

	@Test
	void aChangelogWithGoodHeadingsPassesOnMain() throws Exception {
		Run r = checkChangelog(GOOD_CHANGELOG, null);
		assertEquals(0, r.exit(), r.err());
	}

	@Test
	void aVersionHeadingInAnotherShapeIsRefused() throws Exception {
		Run r = checkChangelog(GOOD_CHANGELOG.replace("## [1.0.0] - 2026-11-04", "## 1.0.0 (2026-11-04)"), null);
		assertEquals(1, r.exit());
		assertTrue(r.err().contains("check-changelog: version heading"), r.err());
		assertTrue(r.err().contains("## 1.0.0 (2026-11-04)"), r.err());
	}

	@Test
	void aReleaseTagWithItsSummaryPasses() throws Exception {
		Run r = checkChangelog(GOOD_CHANGELOG, "v1.1.0");
		assertEquals(0, r.exit(), r.err());
	}

	@Test
	void aReleaseTagWithoutItsSectionIsRefused() throws Exception {
		Run r = checkChangelog(GOOD_CHANGELOG, "v1.2.0");
		assertEquals(1, r.exit());
		assertTrue(r.err().contains("no section ## [1.2.0]"), r.err());
	}

	@Test
	void aReleaseTagWithoutASummaryIsRefused() throws Exception {
		Run r = checkChangelog(GOOD_CHANGELOG, "v1.0.0");
		assertEquals(1, r.exit());
		assertTrue(r.err().contains("must start with ### In short"), r.err());
	}

	@Test
	void aReleaseTagWithAnEmptySummaryIsRefused() throws Exception {
		Run r = checkChangelog(GOOD_CHANGELOG.replace("The images run on Java 25. Nothing to do.\n", ""), "v1.1.0");
		assertEquals(1, r.exit());
		assertTrue(r.err().contains("### In short of [1.1.0] is empty"), r.err());
	}

	@Test
	void aReleaseTagWithUnreleasedContentIsRefused() throws Exception {
		Run r = checkChangelog(GOOD_CHANGELOG.replace("## [Unreleased]\n\n", "## [Unreleased]\n\n### Fixed\n- late\n\n"), "v1.1.0");
		assertEquals(1, r.exit());
		assertTrue(r.err().contains("## [Unreleased] still has content"), r.err());
	}

	@Test
	void aSummaryThatIsNotTheFirstSubsectionIsRefused() throws Exception {
		String moved = GOOD_CHANGELOG.replace("### In short\nThe images run on Java 25. Nothing to do.\n\n### Changed\n- detail\n",
				"### Changed\n- detail\n\n### In short\nThe images run on Java 25. Nothing to do.\n");
		Run r = checkChangelog(moved, "v1.1.0");
		assertEquals(1, r.exit());
		assertTrue(r.err().contains("must start with ### In short"), r.err());
	}

	/** A fake curl for upload_file_once: lists the package (empty, or holding the notes file), serves bytes, logs uploads. */
	private Run uploadFileOnceWithFakeCurl(String listed, byte[] served, byte[] local, Path uploadLog) throws Exception {
		Path dir = Files.createTempDirectory("fakecurl-upload");
		Path fixture = dir.resolve("served.bin");
		Files.write(fixture, served);
		Path localFile = dir.resolve("CHANGELOG.md");
		Files.write(localFile, local);
		Path curl = dir.resolve("curl");
		Files.writeString(curl, "#!/bin/sh\n"
				+ "out=''; url=''; upload=''\n"
				+ "while [ $# -gt 0 ]; do case \"$1\" in --output) out=$2; shift 2 ;; --upload-file) upload=$2; shift 2 ;; --header|--config) shift 2 ;; -*) shift ;; *) url=$1; shift ;; esac; done\n"
				+ "emit() { if [ -n \"$out\" ]; then printf '%s\\n' \"$1\" > \"$out\"; else printf '%s\\n' \"$1\"; fi; }\n"
				+ "if [ -n \"$upload\" ]; then printf '%s %s\\n' \"$upload\" \"$url\" >> '" + uploadLog + "'; exit 0; fi\n"
				+ "case \"$url\" in\n"
				+ "  */packages\\?*) emit '" + ("none".equals(listed) ? "[]" : "[{\"id\":7,\"name\":\"com/visco/viscosuite-release\",\"version\":\"1.1.0-9\"}]") + "' ;;\n"
				+ "  */packages/7/package_files*) emit '[{\"file_name\":\"viscosuite-release-1.1.0-9-notes.md\",\"size\":" + served.length + ",\"file_sha256\":null}]' ;;\n"
				+ "  */packages/maven/*) cp '" + fixture + "' \"$out\" ;;\n"
				+ "  *) echo \"unexpected $url\" >&2; exit 22 ;;\n"
				+ "esac\n");
		assertTrue(curl.toFile().setExecutable(true));
		String lib = REPO.resolve("scripts/ci/lib/packages.sh").toString();
		Map<String, String> env = Map.of("PATH", dir + ":" + System.getenv("PATH"),
				"CI_API_V4_URL", "https://example.invalid/api/v4", "CI_PROJECT_ID", "2", "CI_JOB_TOKEN", "x", "VERSION", "1.1.0-9");
		return sh(REPO, env, "/bin/sh", "-c", ". " + lib + "; upload_file_once com/visco/viscosuite-release viscosuite-release-1.1.0-9-notes.md '" + localFile + "'");
	}

	@Test
	void aNotesFileThatIsNotThereIsUploaded() throws Exception {
		Path log = Files.createTempFile("uploads", ".log");
		byte[] notes = "# Changelog\n".getBytes(StandardCharsets.UTF_8);
		Run r = uploadFileOnceWithFakeCurl("none", notes, notes, log);
		assertEquals(0, r.exit(), r.err());
		List<String> uploads = Files.readAllLines(log);
		assertEquals(1, uploads.size(), uploads.toString());
		assertTrue(uploads.get(0).endsWith(
				" https://example.invalid/api/v4/projects/2/packages/maven/com/visco/viscosuite-release/1.1.0-9/viscosuite-release-1.1.0-9-notes.md"),
				uploads.get(0));
	}

	@Test
	void aNotesFileWithTheSameBytesIsSkipped() throws Exception {
		Path log = Files.createTempFile("uploads", ".log");
		byte[] notes = "# Changelog\n".getBytes(StandardCharsets.UTF_8);
		Run r = uploadFileOnceWithFakeCurl("there", notes, notes, log);
		assertEquals(0, r.exit(), r.err());
		assertTrue(r.out().contains("skipping"), r.out());
		assertEquals(0, Files.readAllLines(log).size());
	}

	@Test
	void aNotesFileWithOtherBytesStopsThePublish() throws Exception {
		Path log = Files.createTempFile("uploads", ".log");
		Run r = uploadFileOnceWithFakeCurl("there", "old".getBytes(StandardCharsets.UTF_8), "new".getBytes(StandardCharsets.UTF_8), log);
		assertNotEquals(0, r.exit());
		assertTrue(r.err().contains("already published with another content"), r.err());
		assertEquals(0, Files.readAllLines(log).size());
	}
}
