# Component model M1 — viscoSuite v1.0.0: publish artifacts, `viscobase`, the fetcher, the release BOM — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every Java component of ViscoSuite (the core WAR, the store WAR, the health pack overlay) is a versioned Maven artifact in this project's GitLab package registry, pinned with its sha256 by a release BOM; a bare `viscobase` image (Tomcat + `runtime.war` + a fetcher) can be composed with those components at build time (the fat images) or at pod start (the commercial runner, from M3); v1.0.0 is the first release.

**Architecture:** One multi-stage `viscorunner/Dockerfile`: stage `viscobase` holds nothing variant-specific, stage `fat` runs the same POSIX `fetch-components` script against a line-oriented components list (local `file://` URLs for compose builds, registry `https://` URLs in CI) and `place-components` puts WARs into `webapps/` and overlays into the PreResources dirs. CI publishes the three artifacts with `deploy:deploy-file` after the integration tests, builds the images from the published bytes, writes `viscosuite-release:<v>` (JSON, from a checked-in template plus the registry's sha256 and the image digest) and triggers viscoForge with `UPSTREAM_RELEASE`. `PackServlet` reports `release` and `components[]` from the fetcher's `MANIFEST`.

**Tech Stack:** Java 21, Maven 3.9 (`./mvnw`), Tomcat 11.0.14 / JRE 21 image, POSIX sh + curl + unzip, JUnit 5 (surefire unit tests, failsafe ITs), GitLab 19.4 CE (Maven package registry, container registry, `glab api`), Docker 27 with BuildKit.

**Spec:** viscoFoundry `docs/superpowers/specs/2026-11-04-component-model-design.md` (§4 BOM, §5 build/publish/fetch, §6 composition, §8 integrity, §9 M1). Deviations decided while planning are listed under "Deviations from the spec" at the end and are recorded in the spec's as-built note.

**Branch:** `feat/component-model-m1` in the worktree `/Users/tom/IdeaProjects/viscoSuite-wt-component-m1` (from `main` c4c1258, 2026-11-03). Every command below runs from that directory unless it says otherwise.

## Global Constraints

- Image names stay `viscorunner`, `viscolink`, `viscostore`; new: `viscobase`. `viscolink:<sha>-core` / `viscolink:latest-core` become tag aliases of `viscobase` (same manifest), never a separate build.
- Context name = URL path = WAR name: `runtime`, `fhir-store`, `console`. The PreResources dir of `runtime` stays `/opt/frank/webapp-overlay/viscolink` in M1 (viscoForge's Dockerfiles and the portal's brand mount write there until M4); `deployAs: overlay/runtime` maps to it in `place-components`.
- Components list line format, exactly: `<id> <kind> <version> <url> <sha256> <deployAs>` (six space-separated fields) after one header line `release <version>`; `kind` ∈ `war | overlay | container`; a `container` line carries `-` for `sha256` and `deployAs` and `<image>@<digest>` as `url`; blank lines and `#` lines ignored. `id`, `kind`, `version` match `[A-Za-z0-9._-]+`.
- `MANIFEST` format, exactly: `release=<version>` (empty value when none) then one `component=<id> <kind> <version>` line per component, in list order.
- Fetcher fails closed: any download error, sha256 mismatch, bad line or bad zip exits non-zero before Tomcat starts; the token is read from `COMPONENT_REGISTRY_TOKEN` (header `Deploy-Token`) or `CI_JOB_TOKEN` (header `Job-Token`), passed to curl through a 0600 config file, never printed, never on a command line.
- Version scheme: a tag `vA.B.C` publishes `A.B.C` and must equal the poms' `A.B.C-SNAPSHOT` base; a `main` commit publishes `A.B.C-<CI_PIPELINE_ID>`. Image tags: `<sha>`, `<version>`, `latest`.
- BOM artifact: `com.visco:viscosuite-release:<version>`, file `viscosuite-release-<version>.json`, uploaded to the Maven registry with a plain `PUT`; field names exactly as in spec §4 (`release`, `base{image,digest,version,servlets}`, `suiteRelease`, `components[]{id,kind,artifact|image,version,sha256|digest,deployAs,requires,excludes,servlets,properties}`).
- The three published artifacts and their files: `com.visco:viscolink:<v>` (`viscolink-<v>.war`, `viscolink-<v>-classes.jar`), `com.visco:viscolink-pack-health:<v>` (`viscolink-pack-health-<v>-overlay.zip`), `com.visco:viscostore:<v>` (`viscostore-<v>.war`). Nothing else is published (not the viscorunner shaded jar, not hl7util).
- Frozen golden `viscorunner/src/test/resources/golden/health-webapp-libs.txt` is not touched; `HealthClasspathIT` stays green unchanged.
- No `git stash`; commit trailer `Co-Authored-By: <the model doing the work> <noreply@anthropic.com>`; conventional-commit subjects.
- `Dockerfile.viscostore` (the store-only image) changes only where it shares the ROOT page (Task 5); its build and tags are unchanged.

## File structure

New files:

| File | Responsibility |
|---|---|
| `viscolink/src/main/resources/META-INF/viscolink-version.properties` | The build's version (filtered `${project.version}`), read by `ViscoLinkModule` |
| `viscolink/src/main/java/com/viscosiety/pack/ComponentManifest.java` | Reads `MANIFEST`; the `release` + `components[]` the descriptor reports |
| `viscolink/src/test/java/com/viscosiety/pack/ComponentManifestTest.java` | Its parser and the file fallback |
| `viscolink/src/test/java/com/viscosiety/components/ViscoLinkModuleTest.java` | The version is the build's, never a token |
| `viscorunner/src/scripts/fetch-components` | Downloads, verifies, unpacks; writes `MANIFEST` + `components.json` |
| `viscorunner/src/scripts/place-components` | Copies fetched WARs/overlays into Tomcat's dirs (build time and every start) |
| `viscorunner/conf/context.xml` | The one Tomcat global context (renamed from `context-viscostore.xml`) |
| `viscorunner/release/viscosuite-release.template.json` | The BOM's static part (ids, kinds, deployAs, requires, servlets, properties) |
| `viscorunner/src/test/java/com/viscosiety/viscorunner/FetchComponentsScriptTest.java` | Runs the two scripts on temp dirs |
| `viscorunner/src/test/java/com/viscosiety/viscorunner/ReleaseTemplateTest.java` | Template shape; `properties[]` = the pack's property keys |
| `viscorunner/src/test/java/com/viscosiety/viscorunner/PublishWiringTest.java` | The poms and settings carry the registry wiring |
| `viscorunner/src/test/java/com/viscosiety/viscorunner/CiScriptsTest.java` | `version.sh` and the list writer, run with sh |
| `scripts/ci/version.sh` | Prints the version of this pipeline |
| `scripts/ci/stage-publish.sh` | Collects the three artifacts + poms + sha256 into `viscorunner/target/publish/` |
| `scripts/ci/publish.sh` | `deploy:deploy-file` of the three artifacts; writes the registry components lists |
| `scripts/ci/write-release-bom.sh` | Fills the template from the Packages API + registry and uploads it |
| `scripts/ci/smoke-image.sh` | Boots a built image, checks health + `MANIFEST` (+ the store) |
| `scripts/ci/cleanup-packages.sh` | Keeps releases and the newest 10 main versions per package |
| `docs/design/2026-11-04-component-delivery-design.md` | The Suite-side design note (pointer to the full spec) |

Modified: `viscorunner/Dockerfile` (two stages), `viscorunner/Dockerfile.viscostore` (ROOT sed), `viscorunner/src/scripts/entrypoint.sh`, `viscorunner/src/webapp/ROOT/index.html`, `viscorunner/pom.xml`, `viscorunner/docker-compose{,.viscolink,.core}.yml`, `viscorunner/src/test/.../DockerfileContractTest.java`, `.../it/PackOverlayLayoutIT.java`, `viscolink/pom.xml`, `viscolink/src/main/java/com/viscosiety/components/ViscoLinkModule.java`, `viscolink/src/main/java/com/viscosiety/pack/PackJson.java`, `viscolink/src/test/java/com/viscosiety/pack/PackJsonTest.java`, `packs/health/pom.xml`, `packs/health/src/assembly/overlay.xml`, `viscostore/pom.xml`, `.mvn/settings.xml`, `.gitlab-ci.yml`, `CHANGELOG.md`, `CLAUDE.md`, `README.md`, `viscorunner/README.md`, `docs/design/README.md`.

Deleted: `viscorunner/conf/context-none.xml`, `viscorunner/conf/context-viscostore.xml` (renamed), `viscorunner/src/packs/core/WEB-INF/pack.properties`, `packs/health/src/main/overlay/WEB-INF/pack.properties`.

---

### Task 1: The version comes from the build, not a constant

**Files:**
- Create: `viscolink/src/main/resources/META-INF/viscolink-version.properties`
- Modify: `viscolink/pom.xml` (add a `<resources>` block inside `<build>`), `viscolink/src/main/java/com/viscosiety/components/ViscoLinkModule.java:22`
- Create: `viscolink/src/test/java/com/viscosiety/components/ViscoLinkModuleTest.java`
- Create: `scripts/ci/version.sh`
- Create: `viscorunner/src/test/java/com/viscosiety/viscorunner/CiScriptsTest.java` (the `version.sh` part; Task 6 adds a second test to it)

**Interfaces:**
- Produces: `ViscoLinkModule.IMPLEMENTATION_VERSION` (unchanged name, now the filtered build version; `CorePack.version()` reads it). `scripts/ci/version.sh` prints the pipeline's version to stdout and exits 1 with a message on a bad tag.

- [ ] **Step 1: Write the failing module test**

`viscolink/src/test/java/com/viscosiety/components/ViscoLinkModuleTest.java`:

```java
package com.viscosiety.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class ViscoLinkModuleTest {

    @Test
    void theVersionIsTheBuildsAndNeverAToken() throws Exception {
        String version = ViscoLinkModule.IMPLEMENTATION_VERSION;
        assertFalse(version.isBlank(), "version is set");
        assertFalse(version.contains("${"), "Maven filtered the version: " + version);
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?"), "A.B.C[-qualifier]: " + version);
    }

    @Test
    void theVersionIsWhatTheFilteredResourceSays() throws Exception {
        Properties props = new Properties();
        try (InputStream in = ViscoLinkModule.class.getResourceAsStream("/META-INF/viscolink-version.properties")) {
            props.load(in);
        }
        assertEquals(props.getProperty("version"), ViscoLinkModule.IMPLEMENTATION_VERSION);
        assertEquals(ViscoLinkModule.IMPLEMENTATION_VERSION,
                new ViscoLinkModule().getModuleInformation().getVersion(), "the manifest carries the same version");
    }
}
```

(`ModuleInformation.getVersion()` exists on Frank!Framework 10.3's `org.frankframework.components.ModuleInformation`; if the accessor is named differently in 10.3.0, read `getModuleInformation()`'s manifest the way `ViscoLinkModule` builds it: `assertEquals(version, info.getVersion())` is the intent.)

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -pl viscolink -Dtest=ViscoLinkModuleTest`
Expected: FAIL (`theVersionIsWhatTheFilteredResourceSays`: NullPointerException on the missing resource).

- [ ] **Step 3: Add the filtered resource and read it**

`viscolink/src/main/resources/META-INF/viscolink-version.properties`:

```properties
# Filled by Maven resource filtering (viscolink/pom.xml): the build's version.
version=${project.version}
```

In `viscolink/pom.xml`, inside `<build>` before `<plugins>` (the module has no `<resources>` block today; this keeps every other resource unfiltered, like `packs/health/pom.xml` does):

```xml
		<resources>
			<resource>
				<directory>src/main/resources</directory>
				<filtering>false</filtering>
				<excludes><exclude>META-INF/viscolink-version.properties</exclude></excludes>
			</resource>
			<resource>
				<directory>src/main/resources</directory>
				<filtering>true</filtering>
				<includes><include>META-INF/viscolink-version.properties</include></includes>
			</resource>
		</resources>
```

`ViscoLinkModule.java`: replace the constant line (22) with

```java
    /**
     * The module's {@code Implementation-Version}: the build's version, read once from the
     * Maven-filtered {@code META-INF/viscolink-version.properties}, so the manifest below and
     * {@link com.viscosiety.pack.CorePack#version()} (the only other reader) follow every
     * {@code versions:set} in CI instead of a constant that drifted. "unknown" only when the
     * resource is missing, which a packaged WAR never is.
     */
    public static final String IMPLEMENTATION_VERSION = readVersion();

    private static String readVersion() {
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = ViscoLinkModule.class.getResourceAsStream("/META-INF/viscolink-version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            return "unknown";
        }
        return props.getProperty("version", "unknown");
    }
```

- [ ] **Step 4: Run the module tests**

Run: `./mvnw -q test -pl viscolink -Dtest='ViscoLinkModuleTest,CoreDefaultsTest,PackJsonTest'`
Expected: PASS.

- [ ] **Step 5: Write the failing script test**

`viscorunner/src/test/java/com/viscosiety/viscorunner/CiScriptsTest.java`:

```java
package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The CI shell scripts, run with sh on fixture trees (no GitLab, no network). Paths are relative to the module. */
class CiScriptsTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    record Run(int exit, String out, String err) {}

    static Run sh(Path cwd, Map<String, String> env, String... args) throws IOException, InterruptedException {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs /bin/sh");
        ProcessBuilder pb = new ProcessBuilder(args).directory(cwd.toFile());
        pb.environment().keySet().removeIf(k -> k.startsWith("CI_") || k.equals("COMPONENT_REGISTRY_TOKEN"));
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
        Run r = sh(pomWith("1.0.0-SNAPSHOT"), Map.of("CI_PIPELINE_ID", "4242"), "/bin/sh", REPO.resolve("scripts/ci/version.sh").toString());
        assertEquals(0, r.exit(), r.err());
        assertEquals("1.0.0-4242", r.out());
    }

    @Test
    void aReleaseTagGetsItsOwnNumber() throws Exception {
        Map<String, String> env = new HashMap<>(Map.of("CI_PIPELINE_ID", "4242", "CI_COMMIT_TAG", "v1.0.0"));
        Run r = sh(pomWith("1.0.0-SNAPSHOT"), env, "/bin/sh", REPO.resolve("scripts/ci/version.sh").toString());
        assertEquals(0, r.exit(), r.err());
        assertEquals("1.0.0", r.out());
    }

    @Test
    void aTagThatDoesNotMatchThePomBaseIsRefused() throws Exception {
        Run r = sh(pomWith("1.1.0-SNAPSHOT"), Map.of("CI_PIPELINE_ID", "1", "CI_COMMIT_TAG", "v1.0.0"), "/bin/sh", REPO.resolve("scripts/ci/version.sh").toString());
        assertNotEquals(0, r.exit());
        assertTrue(r.err().contains("v1.0.0") && r.err().contains("1.1.0"), r.err());
    }

    @Test
    void aTagInAnotherShapeIsRefused() throws Exception {
        Run r = sh(pomWith("1.0.0-SNAPSHOT"), Map.of("CI_PIPELINE_ID", "1", "CI_COMMIT_TAG", "release-1"), "/bin/sh", REPO.resolve("scripts/ci/version.sh").toString());
        assertNotEquals(0, r.exit());
        assertTrue(r.err().contains("vA.B.C"), r.err());
    }
}
```

- [ ] **Step 6: Run it to see it fail**

Run: `./mvnw -q test -pl viscorunner -Dtest=CiScriptsTest`
Expected: FAIL (the script does not exist; exit code 127 or 2).

- [ ] **Step 7: Write `scripts/ci/version.sh`**

```sh
#!/bin/sh
# The version this pipeline publishes (Maven packages, image tags, the release BOM).
#   a tag vA.B.C   -> A.B.C, which must equal the poms' A.B.C-SNAPSHOT base
#   a main commit  -> A.B.C-<CI_PIPELINE_ID>  (ordered by the registry's created_at, never by Maven)
# Run from the repository root: it reads the aggregator pom.xml.
set -eu
base=$(sed -n 's/.*<version>\([0-9][0-9.]*\)-SNAPSHOT<\/version>.*/\1/p' pom.xml | head -1)
[ -n "$base" ] || { echo "version.sh: pom.xml has no A.B.C-SNAPSHOT version" >&2; exit 1; }
if [ -n "${CI_COMMIT_TAG:-}" ]; then
  case "$CI_COMMIT_TAG" in
    v[0-9]*.[0-9]*.[0-9]*) tag="${CI_COMMIT_TAG#v}" ;;
    *) echo "version.sh: tag $CI_COMMIT_TAG is not vA.B.C" >&2; exit 1 ;;
  esac
  [ "$tag" = "$base" ] || { echo "version.sh: tag $CI_COMMIT_TAG does not match the poms' ${base}-SNAPSHOT; set the poms first" >&2; exit 1; }
  echo "$tag"
  exit 0
fi
echo "${base}-${CI_PIPELINE_ID:?CI_PIPELINE_ID is unset}"
```

Then `chmod +x scripts/ci/version.sh`.

- [ ] **Step 8: Run the script test**

Run: `./mvnw -q test -pl viscorunner -Dtest=CiScriptsTest`
Expected: PASS (4 tests).

- [ ] **Step 9: Commit**

```bash
git add viscolink/pom.xml viscolink/src/main/resources/META-INF/viscolink-version.properties \
  viscolink/src/main/java/com/viscosiety/components/ViscoLinkModule.java \
  viscolink/src/test/java/com/viscosiety/components/ViscoLinkModuleTest.java \
  scripts/ci/version.sh viscorunner/src/test/java/com/viscosiety/viscorunner/CiScriptsTest.java
git commit -m "build: the module version follows the Maven build; version.sh for CI"
```

---

### Task 2: Maven publishing wiring (registry, settings, the publish scripts)

**Files:**
- Modify: `viscolink/pom.xml`, `packs/health/pom.xml`, `viscostore/pom.xml` (each gains `<distributionManagement>`), `.mvn/settings.xml` (a `<servers>` block)
- Create: `scripts/ci/stage-publish.sh`, `scripts/ci/publish.sh`
- Create: `viscorunner/src/test/java/com/viscosiety/viscorunner/PublishWiringTest.java`

**Interfaces:**
- Produces: `viscorunner/target/publish/` with `viscolink.war`, `viscolink-classes.jar`, `viscolink.pom`, `viscolink-pack-health-overlay.zip`, `viscolink-pack-health.pom`, `viscostore.war`, `viscostore.pom`, `SHA256SUMS` (sha256sum format) and `VERSION`; `scripts/ci/publish.sh` consumes it and writes `viscorunner/target/components-registry-suite.list` and `viscorunner/target/components-registry-viscolink.list` (the list format of the Global Constraints, `https://` URLs). Task 10 wires both into the pipeline.

- [ ] **Step 1: Write the failing wiring test**

`viscorunner/src/test/java/com/viscosiety/viscorunner/PublishWiringTest.java`:

```java
package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The three published modules and the CI settings point at this project's GitLab Maven registry. */
class PublishWiringTest {

    private static final Path REPO = Path.of("..");
    private static final String REGISTRY_URL = "${env.CI_API_V4_URL}/projects/${env.CI_PROJECT_ID}/packages/maven";

    @Test
    void thePublishedModulesDeployToTheProjectRegistry() throws IOException {
        for (String pom : List.of("viscolink/pom.xml", "packs/health/pom.xml", "viscostore/pom.xml")) {
            String text = Files.readString(REPO.resolve(pom));
            assertTrue(text.contains("<distributionManagement>"), pom + " has distributionManagement");
            assertTrue(text.contains("<id>gitlab-maven</id>"), pom + " names the gitlab-maven server");
            assertTrue(text.contains("<url>" + REGISTRY_URL + "</url>"), pom + " deploys to the project's Maven registry");
        }
    }

    @Test
    void theModulesThatAreNotComponentsDoNotDeploy() throws IOException {
        for (String pom : List.of("viscorunner/pom.xml", "packs/health/util/hl7util/pom.xml", "pom.xml")) {
            String text = Files.readString(REPO.resolve(pom));
            assertTrue(!text.contains("<distributionManagement>"), pom + " is not published");
        }
    }

    @Test
    void ciSettingsAuthenticateTheRegistryWithTheJobToken() throws IOException {
        String settings = Files.readString(REPO.resolve(".mvn/settings.xml"));
        assertTrue(settings.contains("<id>gitlab-maven</id>"), "server gitlab-maven");
        assertTrue(settings.contains("<name>Job-Token</name>"), "Job-Token header");
        assertTrue(settings.contains("<value>${env.CI_JOB_TOKEN}</value>"), "from CI_JOB_TOKEN");
    }

    @Test
    void thePublishScriptsExistAndAreExecutable() {
        for (String script : List.of("scripts/ci/stage-publish.sh", "scripts/ci/publish.sh")) {
            assertTrue(Files.isExecutable(REPO.resolve(script)), script + " is executable");
        }
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -pl viscorunner -Dtest=PublishWiringTest`
Expected: FAIL (no distributionManagement, no server, no scripts).

- [ ] **Step 3: Add `distributionManagement` to the three poms**

In each of `viscolink/pom.xml`, `packs/health/pom.xml`, `viscostore/pom.xml`, directly before the closing `</project>`:

```xml
	<!-- Published to this project's GitLab Maven package registry by CI (scripts/ci/publish.sh,
	     deploy:deploy-file). The URL resolves only inside a pipeline; a local `mvn deploy` is not a
	     supported path. -->
	<distributionManagement>
		<repository>
			<id>gitlab-maven</id>
			<url>${env.CI_API_V4_URL}/projects/${env.CI_PROJECT_ID}/packages/maven</url>
		</repository>
		<snapshotRepository>
			<id>gitlab-maven</id>
			<url>${env.CI_API_V4_URL}/projects/${env.CI_PROJECT_ID}/packages/maven</url>
		</snapshotRepository>
	</distributionManagement>
```

- [ ] **Step 4: Add the server to `.mvn/settings.xml`**

After `</profiles>` and before `<activeProfiles>`:

```xml
  <servers>
    <!-- The GitLab Maven package registry takes the job token as a header (no username). -->
    <server>
      <id>gitlab-maven</id>
      <configuration>
        <httpHeaders>
          <property>
            <name>Job-Token</name>
            <value>${env.CI_JOB_TOKEN}</value>
          </property>
        </httpHeaders>
      </configuration>
    </server>
  </servers>
```

- [ ] **Step 5: Write `scripts/ci/stage-publish.sh`**

```sh
#!/bin/sh
# Collects what CI publishes into viscorunner/target/publish/ with version-free names, the module
# poms (already carrying this pipeline's version through versions:set) and a SHA256SUMS file. Runs in
# the package job after `./mvnw install -pl viscolink,packs/health,viscostore` so the files are the
# installed bytes; publish.sh uploads exactly these and the fat images fetch exactly these.
set -eu
VERSION="${1:?usage: stage-publish.sh <version>}"
out=viscorunner/target/publish
rm -rf "$out"; mkdir -p "$out"
cp "viscolink/target/viscolink-${VERSION}.war"                       "$out/viscolink.war"
cp "viscolink/target/viscolink-${VERSION}-classes.jar"               "$out/viscolink-classes.jar"
cp viscolink/pom.xml                                                 "$out/viscolink.pom"
cp "packs/health/target/viscolink-pack-health-${VERSION}-overlay.zip" "$out/viscolink-pack-health-overlay.zip"
cp packs/health/pom.xml                                              "$out/viscolink-pack-health.pom"
cp viscostore/target/viscostore.war                                  "$out/viscostore.war"
cp viscostore/pom.xml                                                "$out/viscostore.pom"
for f in viscolink.war viscolink-classes.jar viscolink-pack-health-overlay.zip viscostore.war; do
  size=$(stat -c%s "$out/$f" 2>/dev/null || stat -f%z "$out/$f")
  [ "$size" -gt 1000000 ] || [ "$f" = viscolink-classes.jar ] || { echo "stage-publish: $f is $size bytes: a stale stub (run install first)" >&2; exit 1; }
done
( cd "$out" && sha256sum viscolink.war viscolink-classes.jar viscolink-pack-health-overlay.zip viscostore.war > SHA256SUMS )
echo "$VERSION" > "$out/VERSION"
cat "$out/SHA256SUMS"
```

(`viscolink/target/viscolink-<v>.war` is the war plugin's output; `viscostore` uses `finalName` `viscostore`, hence no version in that name.)

- [ ] **Step 6: Write `scripts/ci/publish.sh`**

```sh
#!/bin/sh
# Uploads the staged artifacts (stage-publish.sh) to this project's Maven registry with
# deploy:deploy-file, then writes the components lists the docker jobs fetch from the registry:
#   viscorunner/target/components-registry-suite.list      store + pack-health
#   viscorunner/target/components-registry-viscolink.list  pack-health
# Needs CI_API_V4_URL, CI_PROJECT_ID, CI_JOB_TOKEN (the settings.xml server reads it) and MAVEN_CLI_OPTS.
set -eu
pub=viscorunner/target/publish
VERSION=$(cat "$pub/VERSION")
REPO_URL="${CI_API_V4_URL:?}/projects/${CI_PROJECT_ID:?}/packages/maven"
deploy() { # <main file> <packaging> <pom file> [extra mvn args]: one deploy-file call
  f="$1"; packaging="$2"; pom="$3"; shift 3
  ./mvnw ${MAVEN_CLI_OPTS:-} -q org.apache.maven.plugins:maven-deploy-plugin:3.1.3:deploy-file \
    -DrepositoryId=gitlab-maven -Durl="$REPO_URL" \
    -Dfile="$f" -Dpackaging="$packaging" -DpomFile="$pub/$pom" -Dversion="$VERSION" "$@"
}
# the core: the WAR, with its classes jar attached (packs compile against it)
deploy "$pub/viscolink.war" war viscolink.pom \
  -Dfiles="$pub/viscolink-classes.jar" -Dclassifiers=classes -Dtypes=jar
# the health pack: its jar is the main artifact (Maven consumers resolve the pom through it), the
# overlay zip is attached as classifier "overlay" — the file the fetcher downloads
deploy "packs/health/target/viscolink-pack-health-${VERSION}.jar" jar viscolink-pack-health.pom \
  -Dfiles="$pub/viscolink-pack-health-overlay.zip" -Dclassifiers=overlay -Dtypes=zip
# the store
deploy "$pub/viscostore.war" war viscostore.pom

sha() { grep " $1\$" "$pub/SHA256SUMS" | cut -d' ' -f1; }
url() { echo "$REPO_URL/com/visco/$1/$VERSION/$2"; }
{
  echo "release $VERSION"
  echo "store war $VERSION $(url viscostore viscostore-$VERSION.war) $(sha viscostore.war) fhir-store"
  echo "pack-health overlay $VERSION $(url viscolink-pack-health viscolink-pack-health-$VERSION-overlay.zip) $(sha viscolink-pack-health-overlay.zip) overlay/runtime"
} > viscorunner/target/components-registry-suite.list
{
  echo "release $VERSION"
  echo "pack-health overlay $VERSION $(url viscolink-pack-health viscolink-pack-health-$VERSION-overlay.zip) $(sha viscolink-pack-health-overlay.zip) overlay/runtime"
} > viscorunner/target/components-registry-viscolink.list
cat viscorunner/target/components-registry-suite.list
```

`chmod +x` both scripts. The pack's jar is read from `packs/health/target/` (the publish job runs after `package`, whose artifacts do not include it): add `packs/health/target/viscolink-pack-health-*.jar` to the `package` job's artifact paths in Task 10 — it is in the list there.

- [ ] **Step 7: Run the wiring test**

Run: `./mvnw -q test -pl viscorunner -Dtest=PublishWiringTest`
Expected: PASS. Also `./mvnw -q validate -pl viscolink,packs/health,viscostore` → BUILD SUCCESS (the poms still parse; the unresolved `${env.*}` in `distributionManagement` is fine outside `deploy`).

- [ ] **Step 8: Dry-run the staging locally**

Run: `./mvnw -q install -pl viscolink,packs/health,viscostore -DskipTests && sh scripts/ci/stage-publish.sh 1.0.0-SNAPSHOT && ls -la viscorunner/target/publish`
Expected: the seven files, `SHA256SUMS` with four lines, `VERSION`. (`publish.sh` itself only runs in CI; its list writing is covered by Task 10's pipeline run.)

- [ ] **Step 9: Commit**

```bash
git add viscolink/pom.xml packs/health/pom.xml viscostore/pom.xml .mvn/settings.xml \
  scripts/ci/stage-publish.sh scripts/ci/publish.sh \
  viscorunner/src/test/java/com/viscosiety/viscorunner/PublishWiringTest.java
git commit -m "build: publish viscolink, the health pack overlay and viscostore to the GitLab Maven registry"
```

---

### Task 3: The descriptor reports `release` and `components[]`

**Files:**
- Create: `viscolink/src/main/java/com/viscosiety/pack/ComponentManifest.java`
- Create: `viscolink/src/test/java/com/viscosiety/pack/ComponentManifestTest.java`
- Modify: `viscolink/src/main/java/com/viscosiety/pack/PackJson.java`, `viscolink/src/test/java/com/viscosiety/pack/PackJsonTest.java:30-37`

**Interfaces:**
- Produces: `ComponentManifest.current()` (reads the file named by system property `components.manifest`, default `/opt/frank/components/MANIFEST`; a missing file is `ComponentManifest.NONE`), `ComponentManifest.read(Path)`, `ComponentManifest.parse(List<String>)`, record `ComponentManifest.Component(String id, String kind, String version)`, `Optional<String> release()`, `List<Component> components()`. `PackJson.of(PackDescriptor)` keeps its signature and now ends the JSON with `"release": <string|null>` and `"components": [{"id","kind","version"}]`; `PackJson.of(PackDescriptor, ComponentManifest)` is the testable overload.
- Consumes: the `MANIFEST` format of the Global Constraints (written by Task 4's fetcher).

- [ ] **Step 1: Write the failing manifest test**

`viscolink/src/test/java/com/viscosiety/pack/ComponentManifestTest.java`:

```java
package com.viscosiety.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComponentManifestTest {

    @Test
    void parsesTheReleaseAndTheComponentsInOrder() {
        ComponentManifest m = ComponentManifest.parse(List.of(
                "release=1.0.0",
                "component=store war 1.0.0",
                "",
                "# a comment",
                "component=pack-health overlay 1.0.0",
                "component=postgres container 16"));
        assertEquals("1.0.0", m.release().orElseThrow());
        assertEquals(List.of(
                new ComponentManifest.Component("store", "war", "1.0.0"),
                new ComponentManifest.Component("pack-health", "overlay", "1.0.0"),
                new ComponentManifest.Component("postgres", "container", "16")), m.components());
    }

    @Test
    void anEmptyReleaseIsAbsent() {
        ComponentManifest m = ComponentManifest.parse(List.of("release=", "component=store war 1.0.0"));
        assertTrue(m.release().isEmpty());
    }

    @Test
    void aMalformedLineIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> ComponentManifest.parse(List.of("component=store war")));
        assertThrows(IllegalArgumentException.class, () -> ComponentManifest.parse(List.of("store war 1.0.0")));
    }

    @Test
    void aMissingFileIsNoManifest(@TempDir Path dir) {
        ComponentManifest m = ComponentManifest.read(dir.resolve("MANIFEST"));
        assertTrue(m.release().isEmpty());
        assertTrue(m.components().isEmpty());
    }

    @Test
    void readsTheFileThePropertyNames(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("MANIFEST");
        Files.writeString(file, "release=1.0.0-77\ncomponent=store war 1.0.0-77\n");
        String old = System.setProperty(ComponentManifest.PATH_PROPERTY, file.toString());
        try {
            assertEquals("1.0.0-77", ComponentManifest.current().release().orElseThrow());
        } finally {
            if (old == null) System.clearProperty(ComponentManifest.PATH_PROPERTY); else System.setProperty(ComponentManifest.PATH_PROPERTY, old);
        }
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -pl viscolink -Dtest=ComponentManifestTest`
Expected: compilation FAIL (`ComponentManifest` does not exist).

- [ ] **Step 3: Write `ComponentManifest`**

`viscolink/src/main/java/com/viscosiety/pack/ComponentManifest.java` (Apache-2.0 header like its neighbours):

```java
package com.viscosiety.pack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the component fetcher composed this runtime with: the release and every component, read
 * from the {@code MANIFEST} it writes. A runtime without the file (a fat image built before the
 * fetcher existed, a plain Tomcat with the WAR) reports no release and no components. The file's
 * format is the fetcher's contract: {@code release=<version>} then {@code component=<id> <kind>
 * <version>} per line; blank lines and {@code #} lines are ignored, anything else is an error
 * (the fetcher writes it, so a malformed file is a bug, not a condition to hide).
 */
public final class ComponentManifest {

    public static final String PATH_PROPERTY = "components.manifest";
    public static final String DEFAULT_PATH = "/opt/frank/components/MANIFEST";
    public static final ComponentManifest NONE = new ComponentManifest(null, List.of());

    public record Component(String id, String kind, String version) {
    }

    private final String release;
    private final List<Component> components;

    private ComponentManifest(String release, List<Component> components) {
        this.release = release;
        this.components = components;
    }

    public Optional<String> release() {
        return Optional.ofNullable(release);
    }

    public List<Component> components() {
        return components;
    }

    /** The manifest of this runtime: the file named by {@value #PATH_PROPERTY}, by default {@value #DEFAULT_PATH}. */
    public static ComponentManifest current() {
        return read(Path.of(System.getProperty(PATH_PROPERTY, DEFAULT_PATH)));
    }

    public static ComponentManifest read(Path file) {
        if (!Files.isRegularFile(file)) {
            return NONE;
        }
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the component manifest " + file, e);
        }
    }

    static ComponentManifest parse(List<String> lines) {
        String release = null;
        List<Component> found = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("release=")) {
                String value = line.substring("release=".length()).strip();
                release = value.isEmpty() ? null : value;
                continue;
            }
            if (line.startsWith("component=")) {
                String[] fields = line.substring("component=".length()).strip().split("\\s+");
                if (fields.length != 3) {
                    throw new IllegalArgumentException("MANIFEST line is not 'component=<id> <kind> <version>': " + line);
                }
                found.add(new Component(fields[0], fields[1], fields[2]));
                continue;
            }
            throw new IllegalArgumentException("MANIFEST line not understood: " + line);
        }
        return new ComponentManifest(release, List.copyOf(found));
    }
}
```

- [ ] **Step 4: Run the manifest test**

Run: `./mvnw -q test -pl viscolink -Dtest=ComponentManifestTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Extend the JSON test (fails first)**

In `PackJsonTest.java`, change `healthPackHasExactlyTheDocumentedKeysInTheDocumentedOrder` to expect
`"features", "engines", "release", "components"` at the end of the key list, and add:

```java
    @Test
    void withoutAManifestReleaseIsNullAndComponentsEmpty() throws Exception {
        JsonNode json = MAPPER.readTree(PackJson.of(new HealthValuesPack(), ComponentManifest.NONE));
        assertTrue(json.get("release").isNull(), "release is JSON null");
        assertEquals("[]", json.get("components").toString());
    }

    @Test
    void theManifestIsReportedVerbatim() throws Exception {
        ComponentManifest manifest = ComponentManifest.parse(List.of(
                "release=1.0.0", "component=store war 1.0.0", "component=pack-health overlay 1.0.0"));
        JsonNode json = MAPPER.readTree(PackJson.of(new HealthValuesPack(), manifest));
        assertEquals("1.0.0", json.get("release").asText());
        assertEquals("[{\"id\":\"store\",\"kind\":\"war\",\"version\":\"1.0.0\"},"
                + "{\"id\":\"pack-health\",\"kind\":\"overlay\",\"version\":\"1.0.0\"}]", json.get("components").toString());
    }
```

Run: `./mvnw -q test -pl viscolink -Dtest=PackJsonTest` → FAIL (no such overload; key list differs).

- [ ] **Step 6: Change `PackJson`**

In `PackJson.java`: keep `of(PackDescriptor pack)` as `return of(pack, ComponentManifest.current());` and add the overload; after the `engines` block and before `try`:

```java
        root.put("release", manifest.release().orElse(null));
        ArrayNode components = root.putArray("components");
        for (ComponentManifest.Component component : manifest.components()) {
            ObjectNode node = components.addObject();
            node.put("id", component.id());
            node.put("kind", component.kind());
            node.put("version", component.version());
        }
```

Update the class Javadoc: "...ends with `release` (the fetcher's, or null) and `components` (never omitted)."

- [ ] **Step 7: Run the viscolink tests**

Run: `./mvnw -q test -pl viscolink`
Expected: PASS (every test; `FlowController`'s `/flow-api/pack` and `PackServlet` share `PackJson.of(pack)` and need no change).

- [ ] **Step 8: Commit**

```bash
git add viscolink/src/main/java/com/viscosiety/pack/ComponentManifest.java viscolink/src/main/java/com/viscosiety/pack/PackJson.java \
  viscolink/src/test/java/com/viscosiety/pack/ComponentManifestTest.java viscolink/src/test/java/com/viscosiety/pack/PackJsonTest.java
git commit -m "feat(pack): the descriptor reports the release and the components the fetcher composed"
```

---

### Task 4: The fetcher and the placer

**Files:**
- Create: `viscorunner/src/scripts/fetch-components`, `viscorunner/src/scripts/place-components`
- Create: `viscorunner/src/test/java/com/viscosiety/viscorunner/FetchComponentsScriptTest.java`

**Interfaces:**
- Produces: `fetch-components <list-file>` (env: `VF_COMPONENTS_DIR`, default `/opt/frank/components`; `COMPONENT_REGISTRY_TOKEN`, `CI_JOB_TOKEN`) writing `<dir>/webapps/<context>.war`, `<dir>/overlay/<target>/…`, `<dir>/MANIFEST`, `<dir>/components.json`; `place-components` (env: `VF_COMPONENTS_DIR`, `VF_WEBAPPS_DIR` default `$CATALINA_HOME/webapps`, `VF_OVERLAY_DIR` default `/opt/frank/webapp-overlay`) copying WARs to `webapps/`, overlay `runtime` → `<overlay dir>/viscolink/`, any other target `<t>` → `<overlay dir>/<t>/`, and `components.json` → `webapps/ROOT/components.json`.
- Consumes: the list format and the `MANIFEST` format of the Global Constraints.

- [ ] **Step 1: Write the failing script test**

`viscorunner/src/test/java/com/viscosiety/viscorunner/FetchComponentsScriptTest.java`:

```java
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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fetcher and the placer, run with sh on file:// artifacts (no registry). The same scripts run
 * at image build (the fat images), in the pod's init container (M3) and in the casting bake.
 */
class FetchComponentsScriptTest {

    private static final Path FETCH = Path.of("src/scripts/fetch-components").toAbsolutePath();
    private static final Path PLACE = Path.of("src/scripts/place-components").toAbsolutePath();

    @BeforeAll
    static void tools() {
        for (String tool : new String[] {"sh", "curl", "unzip"}) {
            assumeTrue(onPath(tool), "needs " + tool);
        }
        assumeTrue(onPath("sha256sum") || onPath("shasum"), "needs sha256sum or shasum");
    }

    private static boolean onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, tool))) return true;
        }
        return false;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static Path war(Path dir, String name) throws IOException {
        Path war = dir.resolve(name);
        Files.writeString(war, "not really a war, but bytes are bytes: " + name);
        return war;
    }

    private static Path overlayZip(Path dir) throws IOException {
        Path zip = dir.resolve("pack.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("WEB-INF/lib/pack.jar"));
            out.write("jar".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("WEB-INF/classes/console/index.html"));
            out.write("<html>".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    record Run(int exit, String out, String err) {}

    private static Run run(Path script, Path cwd, Map<String, String> env, String... args) throws Exception {
        String[] cmd = new String[args.length + 2];
        cmd[0] = "sh"; cmd[1] = script.toString();
        System.arraycopy(args, 0, cmd, 2, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
        pb.environment().remove("CI_JOB_TOKEN");
        pb.environment().remove("COMPONENT_REGISTRY_TOKEN");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.waitFor(), out, err);
    }

    @Test
    void fetchesVerifiesPlacesAndWritesTheManifest(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path store = war(src, "viscostore.war");
        Path zip = overlayZip(src);
        Path list = tmp.resolve("components.list");
        Files.writeString(list, String.join("\n",
                "release 1.0.0",
                "store war 1.0.0 file://" + store + " " + sha256(store) + " fhir-store",
                "pack-health overlay 1.0.0 file://" + zip + " " + sha256(zip) + " overlay/runtime",
                "postgres container 16 docker.io/library/postgres@sha256:abc - -",
                ""));
        Path components = tmp.resolve("components");
        Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", components.toString()), list.toString());
        assertEquals(0, r.exit(), r.err());

        assertTrue(Files.isRegularFile(components.resolve("webapps/fhir-store.war")), "the war under its context name");
        assertTrue(Files.isRegularFile(components.resolve("overlay/runtime/WEB-INF/lib/pack.jar")), "the overlay unzipped");
        assertEquals("release=1.0.0\ncomponent=store war 1.0.0\ncomponent=pack-health overlay 1.0.0\ncomponent=postgres container 16\n",
                Files.readString(components.resolve("MANIFEST")));
        assertEquals("{\"release\":\"1.0.0\",\"components\":[{\"id\":\"store\",\"kind\":\"war\",\"version\":\"1.0.0\"},"
                + "{\"id\":\"pack-health\",\"kind\":\"overlay\",\"version\":\"1.0.0\"},{\"id\":\"postgres\",\"kind\":\"container\",\"version\":\"16\"}]}\n",
                Files.readString(components.resolve("components.json")));

        Path webapps = Files.createDirectories(tmp.resolve("tomcat/webapps/ROOT")).getParent();
        Path overlays = Files.createDirectories(tmp.resolve("webapp-overlay/viscolink")).getParent();
        Run placed = run(PLACE, tmp, Map.of("VF_COMPONENTS_DIR", components.toString(),
                "VF_WEBAPPS_DIR", webapps.toString(), "VF_OVERLAY_DIR", overlays.toString()));
        assertEquals(0, placed.exit(), placed.err());
        assertTrue(Files.isRegularFile(webapps.resolve("fhir-store.war")), "placed into webapps/");
        assertTrue(Files.isRegularFile(overlays.resolve("viscolink/WEB-INF/lib/pack.jar")), "overlay/runtime lands in webapp-overlay/viscolink (M1 dir name)");
        assertTrue(Files.isRegularFile(overlays.resolve("viscolink/WEB-INF/classes/console/index.html")));
        assertTrue(Files.isRegularFile(webapps.resolve("ROOT/components.json")), "the landing page's components.json");

        // Placing twice is harmless (every start runs it).
        assertEquals(0, run(PLACE, tmp, Map.of("VF_COMPONENTS_DIR", components.toString(),
                "VF_WEBAPPS_DIR", webapps.toString(), "VF_OVERLAY_DIR", overlays.toString())).exit());
    }

    @Test
    void aWrongSha256FailsAndDeploysNothing(@TempDir Path tmp) throws Exception {
        Path store = war(Files.createDirectory(tmp.resolve("src")), "viscostore.war");
        Path list = tmp.resolve("components.list");
        Files.writeString(list, "release 1.0.0\nstore war 1.0.0 file://" + store + " " + "0".repeat(64) + " fhir-store\n");
        Path components = tmp.resolve("components");
        Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", components.toString()), list.toString());
        assertNotEquals(0, r.exit());
        assertTrue(r.err().contains("store") && r.err().contains("sha256"), r.err());
        assertFalse(Files.exists(components.resolve("webapps/fhir-store.war")), "nothing placed");
        assertFalse(Files.exists(components.resolve("MANIFEST")), "no manifest on failure");
    }

    @Test
    void aMissingFileFails(@TempDir Path tmp) throws Exception {
        Path list = tmp.resolve("components.list");
        Files.writeString(list, "release 1.0.0\nstore war 1.0.0 file://" + tmp.resolve("absent.war") + " " + "0".repeat(64) + " fhir-store\n");
        Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", tmp.resolve("c").toString()), list.toString());
        assertNotEquals(0, r.exit());
        assertTrue(r.err().contains("store"), r.err());
    }

    @Test
    void aBadLineOrKindOrDeployAsFails(@TempDir Path tmp) throws Exception {
        Path store = war(Files.createDirectory(tmp.resolve("src")), "viscostore.war");
        String sha = sha256(store);
        for (String bad : new String[] {
                "store war 1.0.0 file://" + store + " " + sha,                       // five fields
                "store blob 1.0.0 file://" + store + " " + sha + " fhir-store",      // unknown kind
                "store war 1.0.0 file://" + store + " " + sha + " overlay/x",       // a war with an overlay target
                "pack overlay 1.0.0 file://" + store + " " + sha + " runtime",      // an overlay without overlay/
                "st ore war 1.0.0 file://" + store + " " + sha + " fhir-store x",   // id with a space (7 fields)
                "s\"t war 1.0.0 file://" + store + " " + sha + " fhir-store"}) {    // id with a quote
            Files.writeString(tmp.resolve("components.list"), "release 1.0.0\n" + bad + "\n");
            Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", tmp.resolve("c").toString()), tmp.resolve("components.list").toString());
            assertNotEquals(0, r.exit(), "accepted: " + bad);
        }
    }

    @Test
    void theTokenNeverReachesTheOutput(@TempDir Path tmp) throws Exception {
        Path list = tmp.resolve("components.list");
        Files.writeString(list, "release 1.0.0\nstore war 1.0.0 https://127.0.0.1:9/nothing.war " + "0".repeat(64) + " fhir-store\n");
        Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", tmp.resolve("c").toString(), "COMPONENT_REGISTRY_TOKEN", "glpat-SECRET-VALUE"), list.toString());
        assertNotEquals(0, r.exit());
        assertFalse((r.out() + r.err()).contains("SECRET-VALUE"), "the token is never printed");
    }

    @Test
    void aListWithOnlyAReleaseLineIsAnEmptyCompositionNotAnError(@TempDir Path tmp) throws Exception {
        Path list = tmp.resolve("components.list");
        Files.writeString(list, "release 1.0.0\n");
        Path components = tmp.resolve("components");
        Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", components.toString()), list.toString());
        assertEquals(0, r.exit(), r.err());
        assertEquals("release=1.0.0\n", Files.readString(components.resolve("MANIFEST")));
        assertEquals("{\"release\":\"1.0.0\",\"components\":[]}\n", Files.readString(components.resolve("components.json")));
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -pl viscorunner -Dtest=FetchComponentsScriptTest`
Expected: FAIL (the scripts do not exist: exit 127 / "No such file").

- [ ] **Step 3: Write `viscorunner/src/scripts/fetch-components`**

```sh
#!/bin/sh
# fetch-components <list>: compose this runtime from the components a list names.
#
# The list (written by the portal, by CI or by the Maven build) has one header line
#   release <version>
# and one line per component
#   <id> <kind> <version> <url> <sha256> <deployAs>
#   kind: war       -> downloaded, verified, kept as <dir>/webapps/<deployAs>.war (deployAs = context name)
#         overlay   -> downloaded, verified, unzipped into <dir>/overlay/<target>/ (deployAs = overlay/<target>)
#         container -> recorded only (url = <image>@<digest>, sha256 and deployAs are "-")
#   url: file://, http:// or https://. A registry URL is fetched with the Deploy-Token header from
#        COMPONENT_REGISTRY_TOKEN or the Job-Token header from CI_JOB_TOKEN, whichever is set, passed
#        to curl through a 0600 config file: the token is never on a command line and never printed.
# Every byte is verified against the list's sha256 before it is kept; any failure exits non-zero and
# writes no MANIFEST, so Tomcat (place-components, the entrypoint) never starts on a half composition.
# Output: <dir>/MANIFEST (release=<v>, component=<id> <kind> <version> per line) and <dir>/components.json,
# <dir> = $VF_COMPONENTS_DIR (default /opt/frank/components). place-components copies the result into Tomcat.
set -eu

LIST="${1:?usage: fetch-components <list-file>}"
ROOT="${VF_COMPONENTS_DIR:-/opt/frank/components}"

die() { echo "fetch-components: $*" >&2; exit 1; }

sha_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

[ -r "$LIST" ] || die "cannot read list $LIST"
rm -rf "$ROOT/webapps" "$ROOT/overlay" "$ROOT/files" "$ROOT/MANIFEST" "$ROOT/components.json"
mkdir -p "$ROOT/webapps" "$ROOT/overlay" "$ROOT/files"

curlcfg=$(mktemp)
chmod 600 "$curlcfg"
trap 'rm -f "$curlcfg" "$ROOT/MANIFEST.part"' EXIT
if [ -n "${COMPONENT_REGISTRY_TOKEN:-}" ]; then printf 'header = "Deploy-Token: %s"\n' "$COMPONENT_REGISTRY_TOKEN" >> "$curlcfg"
elif [ -n "${CI_JOB_TOKEN:-}" ]; then printf 'header = "Job-Token: %s"\n' "$CI_JOB_TOKEN" >> "$curlcfg"; fi

release=""
json=""
: > "$ROOT/MANIFEST.part"

while read -r id kind version url sha deploy_as extra; do
  case "$id" in ''|'#'*) continue ;; esac
  if [ "$id" = release ]; then
    [ -n "$kind" ] && [ -z "$version" ] || die "the release line is 'release <version>'"
    case "$kind" in *[!A-Za-z0-9._-]*) die "release is [A-Za-z0-9._-]+" ;; esac
    release="$kind"
    continue
  fi
  [ -n "$deploy_as" ] && [ -z "$extra" ] || die "$id: expected '<id> <kind> <version> <url> <sha256> <deployAs>', got: $id $kind $version $url $sha $deploy_as $extra"
  for field in "$id" "$kind" "$version"; do
    case "$field" in *[!A-Za-z0-9._-]*|'') die "$id: id, kind and version are [A-Za-z0-9._-]+" ;; esac
  done
  case "$kind" in
    container)
      ;;
    war|overlay)
      case "$kind" in war) file="$ROOT/files/$id.war" ;; *) file="$ROOT/files/$id.zip" ;; esac
      case "$url" in
        file://*) cp "${url#file://}" "$file" 2>/dev/null || die "$id: cannot read $url" ;;
        https://*|http://*)
          curl --fail --silent --show-error --location --retry 3 --config "$curlcfg" --output "$file" "$url" \
            || die "$id: download of $url failed" ;;
        *) die "$id: url must be file://, http:// or https://" ;;
      esac
      actual=$(sha_of "$file")
      [ "$actual" = "$sha" ] || die "$id: sha256 mismatch: expected $sha, got $actual; fetch aborted"
      if [ "$kind" = war ]; then
        case "$deploy_as" in ''|*/*|-) die "$id: deployAs of a war is a context name (e.g. fhir-store)" ;; esac
        cp "$file" "$ROOT/webapps/$deploy_as.war"
      else
        case "$deploy_as" in overlay/?*) ;; *) die "$id: deployAs of an overlay is overlay/<context>" ;; esac
        target="${deploy_as#overlay/}"
        case "$target" in *[!A-Za-z0-9._-]*) die "$id: overlay target is [A-Za-z0-9._-]+" ;; esac
        mkdir -p "$ROOT/overlay/$target"
        unzip -oq "$file" -d "$ROOT/overlay/$target" || die "$id: not a zip"
      fi
      ;;
    *) die "$id: unknown kind $kind (war, overlay or container)" ;;
  esac
  printf 'component=%s %s %s\n' "$id" "$kind" "$version" >> "$ROOT/MANIFEST.part"
  json="${json}{\"id\":\"$id\",\"kind\":\"$kind\",\"version\":\"$version\"},"
done < "$LIST"

{ printf 'release=%s\n' "$release"; cat "$ROOT/MANIFEST.part"; } > "$ROOT/MANIFEST"
if [ -n "$release" ]; then release_json="\"$release\""; else release_json=null; fi
printf '{"release":%s,"components":[%s]}\n' "$release_json" "${json%,}" > "$ROOT/components.json"
echo "fetch-components: release ${release:-(none)}, $(grep -c '^component=' "$ROOT/MANIFEST") component(s)"
```

- [ ] **Step 4: Write `viscorunner/src/scripts/place-components`**

```sh
#!/bin/sh
# place-components: copy what fetch-components left under $VF_COMPONENTS_DIR into Tomcat.
#   webapps/<context>.war   -> $CATALINA_HOME/webapps/<context>.war   (Tomcat deploys it by name)
#   overlay/runtime/        -> /opt/frank/webapp-overlay/viscolink/   (the PreResources dir of /runtime;
#                              its name stays viscolink until M4, when it is renamed after the context)
#   overlay/<other>/        -> /opt/frank/webapp-overlay/<other>/     (e.g. console)
#   components.json         -> $CATALINA_HOME/webapps/ROOT/components.json (the landing page reads it)
# Runs at image build (fat images) and at every start (entrypoint): plain copies, harmless to repeat.
# A missing components dir is a runtime without components (viscobase alone): nothing to do.
set -eu
ROOT="${VF_COMPONENTS_DIR:-/opt/frank/components}"
WEBAPPS="${VF_WEBAPPS_DIR:-${CATALINA_HOME:-/usr/local/tomcat}/webapps}"
OVERLAYS="${VF_OVERLAY_DIR:-/opt/frank/webapp-overlay}"
[ -d "$ROOT" ] || exit 0
for war in "$ROOT"/webapps/*.war; do
  [ -e "$war" ] || continue
  cp -p "$war" "$WEBAPPS/"
done
for dir in "$ROOT"/overlay/*/; do
  [ -d "$dir" ] || continue
  target=$(basename "$dir")
  case "$target" in runtime) dest="$OVERLAYS/viscolink" ;; *) dest="$OVERLAYS/$target" ;; esac
  mkdir -p "$dest"
  cp -Rp "$dir". "$dest"/
done
if [ -f "$ROOT/components.json" ] && [ -d "$WEBAPPS/ROOT" ]; then
  cp -p "$ROOT/components.json" "$WEBAPPS/ROOT/components.json"
fi
```

`chmod +x` both scripts.

- [ ] **Step 5: Run the script test**

Run: `./mvnw -q test -pl viscorunner -Dtest=FetchComponentsScriptTest`
Expected: PASS (6 tests). On macOS `shasum` is used; in CI (`maven:3.9-eclipse-temurin-21`) `sha256sum`, `curl` and `unzip` are present (`apt-get install -y unzip` is added to the test job's `before_script` in Task 10 in case the image lacks it).

- [ ] **Step 6: Commit**

```bash
git add viscorunner/src/scripts/fetch-components viscorunner/src/scripts/place-components \
  viscorunner/src/test/java/com/viscosiety/viscorunner/FetchComponentsScriptTest.java
git commit -m "feat(viscorunner): fetch-components and place-components, the one way components reach Tomcat"
```

---

### Task 5: `viscobase` and `fat`: the two-stage Dockerfile, one context, a page that reads `components.json`

**Files:**
- Modify: `viscorunner/Dockerfile` (rewritten), `viscorunner/Dockerfile.viscostore:28-34`, `viscorunner/src/scripts/entrypoint.sh`, `viscorunner/src/webapp/ROOT/index.html:141,151,167` (+ one script block)
- Rename: `viscorunner/conf/context-viscostore.xml` → `viscorunner/conf/context.xml`; delete `viscorunner/conf/context-none.xml`
- Modify: `viscorunner/src/test/java/com/viscosiety/viscorunner/DockerfileContractTest.java` (rewritten)

**Interfaces:**
- Produces: image stage `viscobase` (no components; dirs `/opt/frank/components`, `/opt/frank/bin`, `/opt/frank/webapp-overlay/{viscolink,console}`; `/opt/frank/demo-configurations/{health,core}`), stage `fat` with `ARG COMPONENTS_LIST` (a path inside the build context) and the BuildKit secret `job_token`; the entrypoint runs `place-components` before Tomcat.
- Consumes: Task 4's scripts; Task 6 produces `target/components/` and the local lists the compose files name.

- [ ] **Step 1: Rewrite the contract test (fails first)**

Replace `viscorunner/src/test/java/com/viscosiety/viscorunner/DockerfileContractTest.java` with (header and helpers `read`, `dockerfileInstructions`, `hasLine`, `matches`, `sqlStatements`, `contextResourceNames` unchanged from today's file):

```java
    private static List<String> stage(List<String> lines, String from, String to) {
        int start = lines.indexOf(from);
        int end = to == null ? lines.size() : lines.indexOf(to);
        assertTrue(start >= 0, from);
        assertTrue(end > start, to);
        return lines.subList(start, end);
    }

    // ---- the two stages ----

    @Test
    void oneDockerfileTwoStages() throws IOException {
        List<String> lines = dockerfileInstructions();
        assertTrue(hasLine(lines, "FROM tomcat:11.0.14-jre21 AS viscobase"), "stage viscobase on the Frank!Framework base image");
        assertTrue(hasLine(lines, "FROM viscobase AS fat"), "stage fat builds on viscobase");
        assertTrue(lines.stream().noneMatch(l -> l.contains("${PACK}") || l.contains("${STORE}") || l.startsWith("ARG PACK") || l.startsWith("ARG STORE")),
            "no PACK/STORE variants: composition is a components list");
    }

    @Test
    void viscobaseHoldsNoComponent() throws IOException {
        List<String> base = stage(dockerfileInstructions(), "FROM tomcat:11.0.14-jre21 AS viscobase", "FROM viscobase AS fat");
        for (String forbidden : List.of("target/packs/", "target/store/", "fhir-store.war", "webapp-overlay/viscolink/WEB-INF", "components.list")) {
            assertTrue(base.stream().noneMatch(l -> l.contains(forbidden)), "viscobase never copies " + forbidden);
        }
        assertTrue(hasLine(base, "COPY --chown=tomcat target/viscolink.war /usr/local/tomcat/webapps/runtime.war"), "the core WAR, served at /runtime");
        assertTrue(hasLine(base, "COPY --chown=tomcat conf/Catalina/localhost/viscolink.xml /usr/local/tomcat/conf/Catalina/localhost/runtime.xml"),
            "the overlay context file follows the WAR's name");
    }

    @Test
    void theOneGlobalContextDeclaresEveryDatasource() throws IOException {
        List<String> lines = dockerfileInstructions();
        assertTrue(hasLine(lines, "COPY --chown=tomcat conf/context.xml /usr/local/tomcat/conf/context.xml"), "one context for every composition");
        assertFalse(Files.exists(MODULE.resolve("conf/context-none.xml")), "context-none.xml is gone");
        assertFalse(Files.exists(MODULE.resolve("conf/context-viscostore.xml")), "renamed to context.xml");
        assertEquals(Set.of("jdbc/viscolink", "jdbc/viscostore", "jdbc/ladybug"), contextResourceNames("conf/context.xml"),
            "jdbc/viscostore stays declared: Tomcat creates it lazily and the Frank!Framework never enumerates JNDI, so a runtime without a store never sees it");
    }

    @Test
    void theFetcherAndThePlacerShipInViscobase() throws IOException {
        List<String> lines = dockerfileInstructions();
        List<String> base = stage(lines, "FROM tomcat:11.0.14-jre21 AS viscobase", "FROM viscobase AS fat");
        assertTrue(hasLine(base, "COPY --chown=tomcat src/scripts/fetch-components src/scripts/place-components /opt/frank/bin/"), "the two scripts");
        int unzip = -1, user = -1;
        for (int i = 0; i < base.size(); i++) {
            if (base.get(i).startsWith("RUN ") && base.get(i).contains("apt-get install") && base.get(i).contains("unzip")) unzip = i;
            if (base.get(i).equals("USER tomcat")) user = i;
        }
        assertTrue(unzip >= 0 && user > unzip, "unzip is installed as root, before USER tomcat");
        assertTrue(read("src/scripts/entrypoint.sh").contains("/opt/frank/bin/place-components"), "the entrypoint places the components at every start");
        assertTrue(Files.isExecutable(MODULE.resolve("src/scripts/fetch-components")) && Files.isExecutable(MODULE.resolve("src/scripts/place-components")),
            "both scripts are executable in git");
    }

    @Test
    void theFatStageFetchesFromTheListAndPlaces() throws IOException {
        List<String> fat = stage(dockerfileInstructions(), "FROM viscobase AS fat", null);
        assertTrue(hasLine(fat, "ARG COMPONENTS_LIST=target/components-suite.list"), "the list is a build argument (a path in the build context)");
        assertTrue(hasLine(fat, "COPY --chown=tomcat target/components/ /opt/frank/component-files/"), "local artifacts for file:// lists");
        assertTrue(hasLine(fat, "COPY --chown=tomcat ${COMPONENTS_LIST} /opt/frank/components.list"), "the list itself");
        assertTrue(fat.stream().anyMatch(l -> l.startsWith("RUN --mount=type=secret,id=job_token,uid=2000")
                && l.contains("/run/secrets/job_token") && l.contains("/opt/frank/bin/fetch-components /opt/frank/components.list")
                && l.contains("/opt/frank/bin/place-components")),
            "fetch then place, with the job token as a BuildKit secret (never a build argument, never a layer)");
        assertTrue(fat.stream().noneMatch(l -> l.startsWith("ARG") && l.contains("TOKEN")), "no token build argument");
    }

    @Test
    void theDemoSetsOfBothPacksShipInViscobase() throws IOException {
        List<String> base = stage(dockerfileInstructions(), "FROM tomcat:11.0.14-jre21 AS viscobase", "FROM viscobase AS fat");
        assertTrue(hasLine(base, "COPY --chown=tomcat target/demo/health/ /opt/frank/demo-configurations/health/"), "the health demo set");
        assertTrue(hasLine(base, "COPY --chown=tomcat target/demo/core/ /opt/frank/demo-configurations/core/"), "the core demo set");
    }

    // ---- neutral web paths (2026-10-28) ----
    // theOldPathsAreServedThroughTheHostRewriteValve, theRewriteRulesRedirectBrowsersAndServeEverythingElseInPlace,
    // theHealthchecksUseTheNewPaths: unchanged from today's file.

    // theLemmaNativeIsPrebuiltForTheBuildArchitecture: unchanged.

    @Test
    void theRuntimeDirectoriesTheOverlayAndTheJarNeedExist() throws IOException {
        String dockerfile = read("Dockerfile");
        for (String dir : List.of("/opt/frank/lib", "/opt/frank/bin", "/opt/frank/components", "/opt/frank/webapp-overlay/viscolink",
                "/opt/frank/webapp-overlay/console", "/opt/frank/demo-configurations")) {
            assertTrue(dockerfile.contains("mkdir -p " + dir), "mkdir -p " + dir);
        }
    }

    @Test
    void theWarStubCheckCoversTheRuntimeOnly() throws IOException {
        String dockerfile = read("Dockerfile");
        assertTrue(dockerfile.contains("stat -c%s /usr/local/tomcat/webapps/runtime.war"), "runtime.war is checked (a stale .m2 stub)");
        assertFalse(dockerfile.contains("fhir-store.war"), "a fetched WAR is sha256-verified instead");
    }

    @Test
    void oneDockerfileForTheRunnerAndNoMoreViscolinkDockerfile() {
        assertFalse(Files.exists(MODULE.resolve("Dockerfile.viscolink")), "Dockerfile.viscolink is replaced by Dockerfile");
        assertTrue(Files.exists(MODULE.resolve("Dockerfile.viscostore")), "Dockerfile.viscostore stays");
    }

    @Test
    void thePackIdentityFilesAreGone() {
        assertFalse(Files.exists(MODULE.resolve("src/packs")), "src/packs/core (its pack.properties) is gone: the core is the absence of an overlay");
        assertFalse(Files.exists(MODULE.resolve("../packs/health/src/main/overlay")), "the health pack carries no pack.properties: its identity is HealthPack.java");
    }

    // everyTomcatContextDeclaresTheLadybugDatasource: list becomes List.of("conf/context.xml", "demo-conf/context.xml").
    // theDemoContextDeclaresEveryDatasourceOfTheSuiteContextItReplaces: compare against "conf/context.xml".
    // thePreResourcesCommentsAreNotCalledPostResources: unchanged.

    // ---- compose ----

    @Test
    void suiteComposeBuildsTheFatStageWithTheSuiteList() throws IOException {
        String compose = read("docker-compose.yml");
        assertTrue(matches(compose, "^\\s+dockerfile:\\s*Dockerfile\\s*$"), "builds the one Dockerfile");
        assertTrue(matches(compose, "^\\s+target:\\s*fat\\s*$"), "target: fat");
        assertTrue(matches(compose, "^\\s+COMPONENTS_LIST:\\s*target/components-suite\\.list\\s*$"), "store + pack-health");
        assertFalse(compose.contains("PACK:") || compose.contains("STORE:"), "no PACK/STORE build arguments");
    }

    @Test
    void viscolinkComposeBuildsTheFatStageWithTheViscolinkList() throws IOException {
        String compose = read("docker-compose.viscolink.yml");
        assertTrue(matches(compose, "^\\s+target:\\s*fat\\s*$"), "target: fat");
        assertTrue(matches(compose, "^\\s+COMPONENTS_LIST:\\s*target/components-viscolink\\.list\\s*$"), "pack-health only");
        assertTrue(matches(compose, "^\\s+image:\\s*" + Pattern.quote(LINK_IMAGE) + ":latest\\s*$"), "image ...:latest");
        assertTrue(compose.contains("2575:2575"), "the MLLP port stays");
    }

    @Test
    void coreComposeBuildsViscobase() throws IOException {
        String compose = read("docker-compose.core.yml");
        assertTrue(matches(compose, "^\\s+target:\\s*viscobase\\s*$"), "the core IS viscobase");
        assertFalse(compose.contains("COMPONENTS_LIST"), "no list: no components");
        assertTrue(matches(compose, "^\\s+image:\\s*" + Pattern.quote(LINK_IMAGE) + ":latest-core\\s*$"), "image ...:latest-core (an alias of viscobase)");
        assertTrue(matches(compose, "^\\s{2}viscolink:\\s*$"), "the service keeps the name viscolink");
        assertTrue(compose.contains("8180:8080"), "console port");
        assertFalse(compose.contains("2575"), "no MLLP port: the core has no HL7v2 listener");
        assertTrue(compose.contains("/runtime/api/echo"), "the header says what the demo is");
    }

    // theStoreLessStacksGetALadybugDatabase, theLadybugInitSurvivesTheSuiteComposeThatMountsTheWholeDirectory: unchanged.

    // ---- landing page ----

    @Test
    void theLandingPageReadsThePackFromComponentsJsonAndOnlyTheBuildStampIsBaked() throws IOException {
        String page = read("src/webapp/ROOT/index.html");
        for (String gone : List.of("%%PACK_TAGLINE%%", "%%PACK_LINK_BLURB%%", "%%PACK_STAMP%%")) {
            assertFalse(page.contains(gone), "index.html no longer carries " + gone);
            assertFalse(read("Dockerfile").contains(gone), "the Dockerfile no longer fills " + gone);
            assertFalse(read("Dockerfile.viscostore").contains(gone), "Dockerfile.viscostore no longer fills " + gone);
        }
        assertTrue(page.contains("%%BUILD_TIMESTAMP%%") && read("Dockerfile").contains("%%BUILD_TIMESTAMP%%")
                && read("Dockerfile.viscostore").contains("%%BUILD_TIMESTAMP%%"), "the build stamp is still baked by both Dockerfiles");
        assertTrue(page.contains("<p data-pack-tagline>Integration platform</p>"), "the core's tagline is the page's default");
        assertTrue(page.contains("data-pack-blurb") && page.contains("<span data-pack-stamp></span>"), "pack blurb and stamp slots");
        assertTrue(page.contains("fetch('components.json'"), "the page reads components.json (written by the fetcher next to it)");
        assertTrue(page.contains("'pack-health'") && page.contains("Healthcare integration platform"), "the health pack's words live in the page");
        assertTrue(page.contains("Viscosiety B.V.</span><span data-pack-stamp></span>%%BUILD_TIMESTAMP%%"), "the footer: company (brandable), pack/release stamp, then build stamp");
    }

    // lemmaEngineVersionIsTheSameInTheViscolinkAndViscorunnerPoms: unchanged.
```

Delete from today's file: `packAndStoreAreDeclaredBeforeTheirFirstUse`, `packStoreAndContextAreCopiedFromTheirVariants`, `theLandingPageIsStampedFromThePackOnTheOverlayInTheImage`, `theWarIsOnlyCheckedForTheStoreThatIsInTheImage`, `contextFilesAreNamedAfterTheStore`, `bothPackPropertiesFilesAreSafeForTheDockerfileSedAndDefineTheFiveKeys`, `theLandingPageTakesItsWordsFromThePack`, `theViscostoreOnlyImageFillsThePlaceholdersToo`, `suiteComposeBuildsTheHealthPackWithTheStore`, `viscolinkComposeBuildsTheHealthPackWithoutAStore`, `coreComposeBuildsTheCorePackWithoutAStore`. Add `import java.util.Set;` is already there.

Run: `./mvnw -q test -pl viscorunner -Dtest=DockerfileContractTest`
Expected: FAIL on the new assertions (old Dockerfile, old compose, old page).

- [ ] **Step 2: Rename the context and write the Dockerfile**

`git mv viscorunner/conf/context-viscostore.xml viscorunner/conf/context.xml && git rm -q viscorunner/conf/context-none.xml`. In `conf/context.xml` replace the first comment-less lines with a comment above `<Resource name="jdbc/viscolink"`:

```xml
	<!-- The one global context of every image. All three datasources are declared from environment
	     variables (EnvironmentPropertySource, catalinaAdditional.properties) and created by Tomcat at
	     their FIRST JNDI lookup. The Frank!Framework lists only datasources it has looked up, so on a
	     runtime without the store jdbc/viscostore is never looked up and never shown; on a runtime with
	     the store the console browses the store's database through it. -->
```

Replace `viscorunner/Dockerfile` with:

```dockerfile
# ONE Dockerfile, two stages (viscoFoundry design 2026-11-04, the component model):
#   viscobase  Tomcat 11 / JRE 21 + the Frank!Framework WAR at /runtime + the component fetcher.
#              Nothing variant-specific: no pack, no store. The commercial runner IS this image
#              (components fetched at pod start) and viscolink:<tag>-core is this image under another name.
#   fat        FROM viscobase; runs the fetcher at BUILD time against a components list (file:// URLs
#              into target/components/ from the Maven staging for local builds; registry URLs in CI)
#              and places the result: the published viscorunner (health + store) and viscolink (health).
FROM tomcat:11.0.14-jre21 AS viscobase

ARG GID=2000
ARG UID=2000

# Harden file permissions (CIS-DI-0008)
RUN chmod -R 751 /usr/bin /usr/sbin

# unzip for the fetcher's overlay zips; curl and ca-certificates come with the base image.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*

# Create runtime directories and a non-root tomcat user
RUN set -eux && \
	mkdir -p /opt/frank/secrets && \
	mkdir -p /opt/frank/configurations && \
	mkdir -p /opt/frank/demo-configurations && \
	mkdir -p /opt/frank/drivers && \
	mkdir -p /opt/frank/lib && \
	mkdir -p /opt/frank/bin && \
	mkdir -p /opt/frank/components && \
	mkdir -p /opt/frank/plugins && \
	mkdir -p /opt/frank/resources && \
	mkdir -p /opt/frank/testtool && \
	mkdir -p /opt/frank/testtool-ext && \
	mkdir -p /opt/frank/webapp-overlay/viscolink && \
	mkdir -p /opt/frank/webapp-overlay/console && \
	groupadd -g ${GID} tomcat && \
	useradd -u ${UID} -g tomcat -s /usr/sbin/nologin -l tomcat -d ${CATALINA_HOME} && \
	chown -hR tomcat:tomcat ${CATALINA_HOME} && \
	chown -hR tomcat:tomcat /opt/frank

USER tomcat

# Append Frank!Framework Tomcat settings
COPY --chown=tomcat src/scripts/catalinaAdditional.properties /tmp/catalinaAdditional.properties
RUN cat /tmp/catalinaAdditional.properties >> /usr/local/tomcat/conf/catalina.properties && \
	rm /tmp/catalinaAdditional.properties

# The one global context: jdbc/viscolink, jdbc/ladybug AND jdbc/viscostore, each from env and created
# lazily at its first JNDI lookup (see the comment in the file). Override in production via a mounted context.xml.
COPY --chown=tomcat conf/context.xml /usr/local/tomcat/conf/context.xml

# Tomcat server.xml with the ContextFailureEventPublisher <Server> listener (emits a Kubernetes
# Warning Event when a WAR context fails to start). Pinned copy of the tomcat:11.0.14 default —
# re-sync on a base-image Tomcat bump.
COPY --chown=tomcat conf/server.xml /usr/local/tomcat/conf/server.xml

# Neutral web paths (2026-10-28): the webapps are served at /runtime (Frank!Framework), /fhir-store
# (the store) and, on a ViscoForge composition, /console. The old /viscolink, /viscostore and /viscoforge
# keep working through the Host's RewriteValve (server.xml) with these rules: a browser page load (GET,
# Accept text/html) is sent to the new address with a 302, because the session cookie is scoped to the new
# path; every other request (API calls, POSTs, scripts, probes) is served in place. A rule to a context
# that is not deployed answers 404, as before. The rules file must not contain comment lines: with them
# Tomcat 11 silently skipped the conditional rules (found in the 2026-10-28 spike).
COPY --chown=tomcat conf/Catalina/localhost/rewrite.config /usr/local/tomcat/conf/Catalina/localhost/rewrite.config

# Log retention (2026-10-23): the third file of log4j.configurationFile (catalinaAdditional.properties).
COPY --chown=tomcat src/scripts/log4j4retention.xml /opt/frank/log4j/log4j4retention.xml
# Tomcat's own daily files (catalina.*, localhost.*, ...): 30 days instead of the base image's 90 (2026-10-23).
RUN set -e; f=/usr/local/tomcat/conf/logging.properties; sed -i 's/\.maxDays = 90$/.maxDays = 30/' "$f"; if grep -q 'maxDays = 90' "$f" || ! grep -q '^1catalina\.org\.apache\.juli\.AsyncFileHandler\.maxDays = 30$' "$f"; then echo "ERROR: $f does not have the expected maxDays lines; re-check it after a base-image bump"; exit 1; fi

# ContextFailureEventPublisher: the shaded viscorunner jar (fabric8 Kubernetes client + transitives,
# ALL relocated under com.viscosiety.shaded, so nothing leaks onto the shared classloader) on common.loader
# (/opt/frank/lib/*.jar in catalinaAdditional.properties).
COPY --chown=tomcat target/viscorunner-*.jar /opt/frank/lib/

# Per-context config for the Frank!Framework webapp (/runtime): a PreResources overlay, so a pack's
# WEB-INF/lib and WEB-INF/classes (and any demo file injected at run time) are served from
# /opt/frank/webapp-overlay/viscolink/ without pre-creating webapps/runtime/ (which would cause
# Tomcat to skip WAR extraction and deploy an empty context in ~6 ms). The overlay folder keeps its
# name until M4 of the component model (viscoForge and the portal write into it by that name).
COPY --chown=tomcat conf/Catalina/localhost/viscolink.xml /usr/local/tomcat/conf/Catalina/localhost/runtime.xml

# JDBC drivers — shared between the WARs via common.loader in catalina.properties
COPY --chown=tomcat target/drivers/ /opt/frank/drivers/
# Lemma (com.viscosiety.pipes.LemmaPipe): the JNI library for THIS image's architecture, prebuilt so
# nothing is extracted at run time. The images are built on native runners per architecture
# (see .gitlab-ci.yml), so uname -m is the target; any other architecture fails the build.
COPY --chown=tomcat target/native/ /tmp/lemma-native/
RUN set -e; case "$(uname -m)" in x86_64) triple=x86_64-unknown-linux-gnu ;; aarch64) triple=aarch64-unknown-linux-gnu ;; *) echo "ERROR: no Lemma native for $(uname -m)"; exit 1 ;; esac; mkdir -p /opt/frank/native; cp "/tmp/lemma-native/${triple}/liblemma_jni.so" /opt/frank/native/liblemma_jni.so; rm -rf /tmp/lemma-native
ENV LEMMA_JNI_LIBRARY=/opt/frank/native/liblemma_jni.so

# Both packs' demo configurations (never loaded unless mounted over /opt/frank/configurations).
COPY --chown=tomcat target/demo/health/ /opt/frank/demo-configurations/health/
COPY --chown=tomcat target/demo/core/ /opt/frank/demo-configurations/core/

# Landing page. The pack's words and the release come from components.json, which the fetcher writes
# next to the page (place-components); only the build stamp is baked here.
COPY --chown=tomcat src/webapp/ROOT/ /usr/local/tomcat/webapps/ROOT/
ARG BUILD_TIMESTAMP=""
RUN set -e; BUILD_TS="${BUILD_TIMESTAMP:-$(date -u '+%Y-%m-%d %H:%M UTC')}"; \
    sed -i "s|%%BUILD_TIMESTAMP%%| \&middot; Built ${BUILD_TS}|" /usr/local/tomcat/webapps/ROOT/index.html

# The Frank!Framework WAR — Tomcat derives the context path from the filename: runtime.war → /runtime.
COPY --chown=tomcat target/viscolink.war /usr/local/tomcat/webapps/runtime.war

# Fail fast if the WAR is a stale empty stub from .m2/ (< 1 MB). copy-dependencies resolves from the
# local Maven repo, not the reactor; if `mvn install -pl viscolink,packs/health,viscostore` was not run
# first the 1-2 KB placeholder artifact ends up here and Tomcat deploys it silently with no routes.
# (Fetched components need no such check: the fetcher verifies their sha256.)
RUN set -e; \
    vl=$(stat -c%s /usr/local/tomcat/webapps/runtime.war); \
    [ "$vl" -gt 1000000 ] || { echo ""; echo "ERROR: runtime.war (viscolink.war) is ${vl} bytes — stale stub. Run:"; \
        echo "  ./mvnw install -pl viscolink,packs/health,viscostore -DskipTests && ./mvnw package -pl viscorunner -DskipTests"; echo ""; exit 1; }

# The component fetcher and placer (see the scripts' headers) and the entrypoint, which places at every start.
COPY --chown=tomcat src/scripts/fetch-components src/scripts/place-components /opt/frank/bin/
COPY --chown=tomcat src/scripts/entrypoint.sh /scripts/entrypoint.sh

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
	CMD curl --fail --silent http://localhost:8080/runtime/iaf/api/server/health || exit 1

ENTRYPOINT ["/scripts/entrypoint.sh"]
CMD ["catalina.sh", "run"]

# ---------------------------------------------------------------------------------------------------
# fat: viscobase plus the components a list names, fetched and placed at build time.
#   docker build --target fat --build-arg COMPONENTS_LIST=target/components-suite.list .
# A list with file:// URLs reads target/components/ (copied below); a list with registry URLs is fetched
# with the job token, handed in as a BuildKit secret (docker build --secret id=job_token,env=CI_JOB_TOKEN):
# never a build argument, never a layer.
# ---------------------------------------------------------------------------------------------------
FROM viscobase AS fat
ARG COMPONENTS_LIST=target/components-suite.list
COPY --chown=tomcat target/components/ /opt/frank/component-files/
COPY --chown=tomcat ${COMPONENTS_LIST} /opt/frank/components.list
RUN --mount=type=secret,id=job_token,uid=2000 set -e; \
    if [ -f /run/secrets/job_token ]; then CI_JOB_TOKEN=$(cat /run/secrets/job_token); export CI_JOB_TOKEN; fi; \
    /opt/frank/bin/fetch-components /opt/frank/components.list && /opt/frank/bin/place-components
```

- [ ] **Step 3: The entrypoint places the components**

`viscorunner/src/scripts/entrypoint.sh`, before `exec "$@"`:

```sh
# Components fetched into /opt/frank/components (at image build for the fat images, by the init
# container on the platform) are copied into Tomcat's dirs at every start. The store-only image has
# no placer: nothing to do there.
if [ -x /opt/frank/bin/place-components ]; then
	/opt/frank/bin/place-components
fi
```

- [ ] **Step 4: The landing page reads `components.json`**

In `viscorunner/src/webapp/ROOT/index.html`: line 141 `<p>%%PACK_TAGLINE%%</p>` → `<p data-pack-tagline>Integration platform</p>`; line 151 `<p>%%PACK_LINK_BLURB%%</p>` → `<p data-pack-blurb>Receives, converts, validates and routes messages between your systems.</p>`; in the footer `%%PACK_STAMP%%%%BUILD_TIMESTAMP%%` → `<span data-pack-stamp></span>%%BUILD_TIMESTAMP%%`. Add, right before `</body>` (after the existing script block that applies the product name):

```html
  <script>
    // The pack and the release come from components.json, written by the fetcher next to this page
    // (fat images at build, the commercial runner at start). Without it: the core's words, no stamp.
    (function () {
      var words = {
        'pack-health': { tagline: 'Healthcare integration platform',
                         blurb: 'Ingests HL7v2, FHIR, and custom source data. Converts, validates, and routes messages to the CDR.' }
      };
      fetch('components.json', { cache: 'no-store' })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (c) {
          if (!c) return;
          var stamp = [];
          if (c.release) stamp.push('release ' + c.release);
          (c.components || []).forEach(function (comp) {
            var w = words[comp.id];
            if (!w) return;
            document.querySelector('[data-pack-tagline]').textContent = w.tagline;
            document.querySelector('[data-pack-blurb]').textContent = w.blurb;
            stamp.push('pack ' + comp.id.replace('pack-', '') + ' ' + comp.version);
          });
          if (stamp.length) document.querySelector('[data-pack-stamp]').textContent = ' · ' + stamp.join(' · ');
        })
        .catch(function () {});
    })();
  </script>
```

In `viscorunner/Dockerfile.viscostore` lines 28-34 become:

```dockerfile
# The landing page is shared with the runner image; there it reads the pack from components.json, which
# this image never has, so it shows the core's words. Only the build stamp is baked.
COPY --chown=tomcat src/webapp/ROOT/ /usr/local/tomcat/webapps/ROOT/
ARG BUILD_TIMESTAMP=""
RUN BUILD_TS="${BUILD_TIMESTAMP:-$(date -u '+%Y-%m-%d %H:%M UTC')}"; \
    sed -i "s|%%BUILD_TIMESTAMP%%| \&middot; Built ${BUILD_TS}|" /usr/local/tomcat/webapps/ROOT/index.html
```

- [ ] **Step 5: Run the contract test**

Run: `./mvnw -q test -pl viscorunner -Dtest=DockerfileContractTest`
Expected: the Dockerfile, context, page and Dockerfile.viscostore tests PASS; the three compose tests and `thePackIdentityFilesAreGone` still FAIL (Task 6 and Task 7 finish them). Record which fail.

- [ ] **Step 6: Commit**

```bash
git add viscorunner/Dockerfile viscorunner/Dockerfile.viscostore viscorunner/conf/context.xml viscorunner/conf/context-none.xml \
  viscorunner/conf/context-viscostore.xml viscorunner/src/scripts/entrypoint.sh viscorunner/src/webapp/ROOT/index.html \
  viscorunner/src/test/java/com/viscosiety/viscorunner/DockerfileContractTest.java
git commit -m "feat(viscorunner): viscobase and fat stages; one global context; the landing page reads components.json"
```

---

### Task 6: Maven staging: components, local lists, no pack identity files

**Files:**
- Modify: `viscorunner/pom.xml:364-411` (the antrun plugin) and the `unpack-health-overlay` execution's neighbours
- Delete: `viscorunner/src/packs/core/WEB-INF/pack.properties`, `packs/health/src/main/overlay/WEB-INF/pack.properties`
- Modify: `packs/health/src/assembly/overlay.xml:28-32` (drop the `src/main/overlay` fileSet)
- Modify: `viscorunner/src/test/java/com/viscosiety/viscorunner/it/PackOverlayLayoutIT.java` (rewritten)

**Interfaces:**
- Produces: `viscorunner/target/components/viscostore.war`, `viscorunner/target/components/viscolink-pack-health-overlay.zip`, `viscorunner/target/components-suite.list`, `viscorunner/target/components-viscolink.list` (file:// URLs under `/opt/frank/component-files/`, sha256 from Ant's `<checksum>`); `target/packs/health/` and `target/demo/{health,core}/` stay for the ITs and the Dockerfile; `target/store/` and `target/packs/core/` are gone.

- [ ] **Step 1: Rewrite the layout IT (fails first)**

Replace `PackOverlayLayoutIT.java`'s body with:

```java
/**
 * The staging the Dockerfile and the ITs read from: {@code target/packs/health/} (the unpacked overlay the
 * launcher ITs run), {@code target/demo/{health,core}/} and, for the fat image, {@code target/components/}
 * plus the two local components lists.
 */
class PackOverlayLayoutIT {

    private static final Path TARGET = Path.of("target");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    @Test
    void healthOverlayIsUnpackedForTheLauncherIts() throws IOException {
        Path health = TARGET.resolve("packs/health");
        assertTrue(Files.isDirectory(health.resolve("WEB-INF/lib")), "target/packs/health/WEB-INF/lib");
        assertTrue(Files.isRegularFile(health.resolve("WEB-INF/classes/console/index.html")), "patched console index");
        try (Stream<Path> jars = Files.list(health.resolve("WEB-INF/lib"))) {
            assertTrue(jars.anyMatch(p -> p.getFileName().toString().startsWith("viscolink-pack-health-")),
                "the pack's own jar is in the overlay's WEB-INF/lib");
        }
        assertFalse(Files.exists(health.resolve("WEB-INF/pack.properties")), "no pack.properties: the identity is HealthPack.java");
        assertFalse(Files.exists(TARGET.resolve("packs/core")), "no core staging: the core is the absence of an overlay");
        assertFalse(Files.exists(TARGET.resolve("store")), "no store staging: the store is a component");
    }

    @Test
    void demoConfigurationsAreStagedPerPack() {
        assertTrue(Files.isDirectory(TARGET.resolve("demo/health/hl7v2-to-fhir")), "target/demo/health/hl7v2-to-fhir/");
        assertTrue(Files.isRegularFile(TARGET.resolve("demo/core/echo/Configuration.xml")), "target/demo/core/echo/Configuration.xml");
    }

    @Test
    void componentsAreStagedWithTheirLists() throws IOException {
        assertTrue(Files.isRegularFile(TARGET.resolve("components/viscostore.war")), "target/components/viscostore.war");
        assertTrue(Files.isRegularFile(TARGET.resolve("components/viscolink-pack-health-overlay.zip")), "target/components/viscolink-pack-health-overlay.zip");
        List<String> suite = Files.readAllLines(TARGET.resolve("components-suite.list"));
        List<String> link = Files.readAllLines(TARGET.resolve("components-viscolink.list"));
        assertTrue(suite.get(0).matches("release \\S+"), "suite list header: " + suite.get(0));
        assertEquals(3, suite.size(), "release + store + pack-health: " + suite);
        assertEquals(2, link.size(), "release + pack-health: " + link);
        assertEquals(suite.get(0), link.get(0), "same release");
        assertTrue(suite.get(1).matches("store war \\S+ file:///opt/frank/component-files/viscostore\\.war [0-9a-f]{64} fhir-store"), suite.get(1));
        assertTrue(suite.get(2).matches("pack-health overlay \\S+ file:///opt/frank/component-files/viscolink-pack-health-overlay\\.zip [0-9a-f]{64} overlay/runtime"), suite.get(2));
        assertEquals(suite.get(2), link.get(1), "the pack line is the same in both lists");
        String storeSha = suite.get(1).split(" ")[4];
        assertEquals(sha256(TARGET.resolve("components/viscostore.war")), storeSha, "the list's sha256 is the staged file's");
    }

    @Test
    void theOverlayZipCarriesNoPackProperties() throws IOException {
        try (ZipFile zip = new ZipFile(TARGET.resolve("components/viscolink-pack-health-overlay.zip").toFile())) {
            assertTrue(zip.stream().noneMatch(e -> e.getName().endsWith("WEB-INF/pack.properties")), "pack.properties is gone from the overlay");
            assertTrue(zip.stream().anyMatch(e -> e.getName().startsWith("WEB-INF/lib/viscolink-pack-health-")), "the pack jar is there");
        }
    }

    @Test
    void existingArtefactsStayWhereTheyWere() {
        assertTrue(Files.isRegularFile(TARGET.resolve("viscolink.war")), "target/viscolink.war");
        assertTrue(Files.isRegularFile(TARGET.resolve("viscostore.war")), "target/viscostore.war");
        assertTrue(Files.isDirectory(TARGET.resolve("drivers")), "target/drivers");
    }

    /** The staged list feeds the real fetcher (paths rewritten to this checkout): Ant's checksum and the fetcher's agree. */
    @Test
    void theStagedListFeedsTheFetcher(@TempDir Path tmp) throws Exception {
        String list = Files.readString(TARGET.resolve("components-suite.list"))
                .replace("file:///opt/frank/component-files/", "file://" + TARGET.resolve("components").toAbsolutePath() + "/");
        Path file = tmp.resolve("components.list");
        Files.writeString(file, list);
        ProcessBuilder pb = new ProcessBuilder("sh", Path.of("src/scripts/fetch-components").toAbsolutePath().toString(), file.toString())
                .redirectErrorStream(true).directory(tmp.toFile());
        pb.environment().put("VF_COMPONENTS_DIR", tmp.resolve("components").toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        assertTrue(Files.isRegularFile(tmp.resolve("components/webapps/fhir-store.war")));
        assertTrue(Files.isDirectory(tmp.resolve("components/overlay/runtime/WEB-INF/lib")));
    }

    private static String sha256(Path file) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

Imports: `java.nio.charset.StandardCharsets`, `java.util.List`, `java.util.regex.Pattern`, `java.util.zip.ZipFile`, `org.junit.jupiter.api.io.TempDir`, `assertEquals`/`assertFalse`/`assertTrue`.

Run: `./mvnw -q install -pl viscolink,packs/health,viscostore -DskipTests && ./mvnw -q verify -pl viscorunner -Dit.test=PackOverlayLayoutIT -DskipTests=false`
Expected: FAIL (`target/components` absent; `pack.properties` present; `target/store` present).

- [ ] **Step 2: Remove the identity files and the overlay fileSet**

`git rm -q viscorunner/src/packs/core/WEB-INF/pack.properties packs/health/src/main/overlay/WEB-INF/pack.properties`. In `packs/health/src/assembly/overlay.xml` delete the third `<fileSet>` (`src/main/overlay`, lines 28-32) and change the comment on line 4 to `<!-- The tree of this zip IS the webapp overlay (/opt/frank/webapp-overlay/viscolink/): WEB-INF/lib and the patched console index. -->`. In `packs/health/pom.xml` nothing references `src/main/overlay` besides the assembly (check: `grep -n "main/overlay" packs/health/pom.xml` → no output).

- [ ] **Step 3: Rewrite the staging in `viscorunner/pom.xml`**

Replace the antrun plugin's two executions (lines 364-411) with:

```xml
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-antrun-plugin</artifactId>
				<version>3.1.0</version>
				<executions>
					<!-- dependency:unpack and the copies below only add files: start from nothing so a jar a new
					     provided declaration dropped never survives an incremental build. -->
					<execution>
						<id>reset-staging</id>
						<phase>prepare-package</phase>
						<goals><goal>run</goal></goals>
						<configuration>
							<target>
								<delete dir="${project.build.directory}/packs"/>
								<delete dir="${project.build.directory}/demo"/>
								<delete dir="${project.build.directory}/components"/>
								<delete file="${project.build.directory}/components-suite.list"/>
								<delete file="${project.build.directory}/components-viscolink.list"/>
							</target>
						</configuration>
					</execution>
					<!-- The demo sets (both baked into viscobase) and the components a local fat build fetches:
					     target/components/ holds the store WAR and the health overlay zip, and the two lists name
					     them with file:// URLs under /opt/frank/component-files/ (where the Dockerfile's fat stage
					     copies target/components/) and their sha256. CI writes the same lists with registry URLs
					     (scripts/ci/publish.sh); the fetcher does not care which. -->
					<execution>
						<id>stage-demos-and-components</id>
						<phase>package</phase>
						<goals><goal>run</goal></goals>
						<configuration>
							<target>
								<copy todir="${project.build.directory}/demo/health" overwrite="true">
									<fileset dir="${project.basedir}/../packs/health/demo-configurations"/>
								</copy>
								<copy todir="${project.build.directory}/demo/core" overwrite="true">
									<fileset dir="${project.basedir}/../viscolink/demo-configurations"/>
								</copy>
								<mkdir dir="${project.build.directory}/components"/>
								<copy file="${project.build.directory}/viscostore.war"
								      tofile="${project.build.directory}/components/viscostore.war" overwrite="true"/>
								<checksum file="${project.build.directory}/components/viscostore.war" algorithm="SHA-256" property="sha.viscostore"/>
								<checksum file="${project.build.directory}/components/viscolink-pack-health-overlay.zip" algorithm="SHA-256" property="sha.pack"/>
								<echo file="${project.build.directory}/components-suite.list" append="false" message="release ${project.version}${line.separator}store war ${project.version} file:///opt/frank/component-files/viscostore.war ${sha.viscostore} fhir-store${line.separator}pack-health overlay ${project.version} file:///opt/frank/component-files/viscolink-pack-health-overlay.zip ${sha.pack} overlay/runtime${line.separator}"/>
								<echo file="${project.build.directory}/components-viscolink.list" append="false" message="release ${project.version}${line.separator}pack-health overlay ${project.version} file:///opt/frank/component-files/viscolink-pack-health-overlay.zip ${sha.pack} overlay/runtime${line.separator}"/>
							</target>
						</configuration>
					</execution>
				</executions>
			</plugin>
```

and add to the maven-dependency-plugin's executions (next to `unpack-health-overlay`, which stays for the ITs) a copy of the overlay zip into `target/components/`:

```xml
					<!-- The health overlay zip as a component file for local fat builds (file:// list). -->
					<execution>
						<id>copy-health-overlay-component</id>
						<phase>package</phase>
						<goals><goal>copy</goal></goals>
						<configuration>
							<overWriteSnapshots>true</overWriteSnapshots>
							<overWriteReleases>true</overWriteReleases>
							<stripVersion>true</stripVersion>
							<artifactItems>
								<artifactItem>
									<groupId>com.visco</groupId>
									<artifactId>viscolink-pack-health</artifactId>
									<version>${project.version}</version>
									<type>zip</type>
									<classifier>overlay</classifier>
									<outputDirectory>${project.build.directory}/components</outputDirectory>
								</artifactItem>
							</artifactItems>
						</configuration>
					</execution>
```

(`stripVersion` names it `viscolink-pack-health-overlay.zip`. Same-phase executions run in declaration order: the dependency plugin is declared before antrun, so the zip exists when `<checksum>` runs.) Update the comment above the antrun plugin ("Everything the one Dockerfile COPYs besides …") to name demos and components.

- [ ] **Step 4: Run the layout IT and the launcher ITs**

Run: `./mvnw -q verify -pl viscorunner -Dit.test=PackOverlayLayoutIT,HealthClasspathIT`
Expected: PASS. Then the full `./mvnw -q verify -pl viscorunner` → PASS (the launcher ITs still read `target/packs/health`, `target/viscolink.war`, `target/viscostore.war`).

- [ ] **Step 5: Build the fat image locally from the staged list**

Run (in `viscorunner/`): `docker build --target fat --build-arg COMPONENTS_LIST=target/components-suite.list -t viscorunner:m1-local . && docker run --rm viscorunner:m1-local cat /opt/frank/components/MANIFEST`
Expected: the build's fetch step prints `fetch-components: release 1.0.0-SNAPSHOT, 2 component(s)`; the `cat` prints `release=1.0.0-SNAPSHOT`, `component=store war 1.0.0-SNAPSHOT`, `component=pack-health overlay 1.0.0-SNAPSHOT`. Then `docker build --target viscobase -t viscobase:m1-local .` → succeeds; `docker run --rm viscobase:m1-local ls /opt/frank/components` → empty.

- [ ] **Step 6: Commit**

```bash
git add viscorunner/pom.xml packs/health/src/assembly/overlay.xml viscorunner/src/packs packs/health/src/main/overlay \
  viscorunner/src/test/java/com/viscosiety/viscorunner/it/PackOverlayLayoutIT.java
git commit -m "build(viscorunner): stage components and local lists; the pack identity lives in Java only"
```

---

### Task 7: The compose files build from the stages

**Files:**
- Modify: `viscorunner/docker-compose.yml:4-11`, `viscorunner/docker-compose.viscolink.yml:4-10`, `viscorunner/docker-compose.core.yml:1-16`

**Interfaces:** none new; the three compose tests of Task 5's `DockerfileContractTest` are the contract.

- [ ] **Step 1: Confirm the three compose tests fail**

Run: `./mvnw -q test -pl viscorunner -Dtest='DockerfileContractTest#suiteComposeBuildsTheFatStageWithTheSuiteList+viscolinkComposeBuildsTheFatStageWithTheViscolinkList+coreComposeBuildsViscobase'`
Expected: FAIL (PACK/STORE args).

- [ ] **Step 2: Edit the build blocks**

`docker-compose.yml` lines 4-10:

```yaml
    build:
      context: .
      dockerfile: Dockerfile
      target: fat
      args:
        COMPONENTS_LIST: target/components-suite.list
        BUILD_TIMESTAMP: "${BUILD_TIMESTAMP:-}"
```

`docker-compose.viscolink.yml` lines 4-9:

```yaml
    build:
      context: .
      dockerfile: Dockerfile
      target: fat
      args:
        COMPONENTS_LIST: target/components-viscolink.list
```

`docker-compose.core.yml`: the header comment becomes

```yaml
# The market-neutral core: ViscoLink without a vertical pack and without ViscoStore (no HL7v2, no FHIR).
# It IS the viscobase stage (no component fetched); the published viscolink:latest-core is viscobase under
# another name. /runtime/api/echo is its demo: mount ../viscolink/demo-configurations over
# /opt/frank/configurations (the image carries a copy in /opt/frank/demo-configurations/core) and POST to it.
#
#   docker compose -f docker-compose.core.yml up --build
#
```

and its build block (lines 10-15):

```yaml
    build:
      context: .
      dockerfile: Dockerfile
      target: viscobase
```

- [ ] **Step 3: Run the contract test and a compose build**

Run: `./mvnw -q test -pl viscorunner -Dtest=DockerfileContractTest` → PASS (every test, now that Tasks 5-7 are in).
Run (in `viscorunner/`, after `./mvnw install -pl viscolink,packs/health,viscostore -DskipTests && ./mvnw package -pl viscorunner -DskipTests` at the root): `docker compose -f docker-compose.core.yml build && docker compose -f docker-compose.viscolink.yml build`
Expected: both build; the viscolink build's log shows `fetch-components: release 1.0.0-SNAPSHOT, 1 component(s)`.

- [ ] **Step 4: Commit**

```bash
git add viscorunner/docker-compose.yml viscorunner/docker-compose.viscolink.yml viscorunner/docker-compose.core.yml
git commit -m "build(viscorunner): compose builds the fat or viscobase stage; no PACK/STORE arguments"
```

---

### Task 8: The release BOM: template, test, writer

**Files:**
- Create: `viscorunner/release/viscosuite-release.template.json`
- Create: `viscorunner/src/test/java/com/viscosiety/viscorunner/ReleaseTemplateTest.java`
- Modify: `viscorunner/pom.xml` (test dependency `com.fasterxml.jackson.core:jackson-databind:2.18.3`)
- Create: `scripts/ci/write-release-bom.sh`

**Interfaces:**
- Produces: `scripts/ci/write-release-bom.sh <version>` (env: `CI_API_V4_URL`, `CI_PROJECT_ID`, `CI_JOB_TOKEN`, `CI_REGISTRY_IMAGE`) writes `viscorunner/target/viscosuite-release-<version>.json` and uploads it to `…/packages/maven/com/visco/viscosuite-release/<version>/viscosuite-release-<version>.json`; the BOM's fields are spec §4's.
- Consumes: the published packages (Task 10's `publish` job), the `viscobase:<version>` manifest (Task 10's manifest job).

- [ ] **Step 1: Write the failing template test**

Add to `viscorunner/pom.xml` dependencies (test scope; the shaded fabric8 brings Jackson 3 `tools.jackson`, a different package, so there is no clash):

```xml
		<dependency>
			<groupId>com.fasterxml.jackson.core</groupId>
			<artifactId>jackson-databind</artifactId>
			<version>2.18.3</version>
			<scope>test</scope>
		</dependency>
```

`viscorunner/src/test/java/com/viscosiety/viscorunner/ReleaseTemplateTest.java`:

```java
package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The release BOM's static part. The placeholders (${VERSION}, ${SHA_*}, ${BASE_*}, ${POSTGRES_DIGEST}) are
 * filled by scripts/ci/write-release-bom.sh; everything else is the contract viscoFoundry renders from.
 */
class ReleaseTemplateTest {

    private static final Path TEMPLATE = Path.of("release/viscosuite-release.template.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode template() throws IOException {
        return MAPPER.readTree(Files.readString(TEMPLATE));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void theTopLevelShape() throws IOException {
        JsonNode t = template();
        assertEquals("${VERSION}", t.get("release").asText());
        assertTrue(t.get("suiteRelease").isNull(), "a Suite release builds on nothing");
        JsonNode base = t.get("base");
        assertEquals("${BASE_IMAGE}", base.get("image").asText());
        assertEquals("${BASE_DIGEST}", base.get("digest").asText());
        assertEquals("${VERSION}", base.get("version").asText());
        assertEquals(List.of("ApiListenerServlet", "WebContentServlet", "configRef", "larvaRun", "agentApi", "pack"), strings(base.get("servlets")),
            "the runtime's own servlets, PackServlet included (it lives in the core WAR, not in a pack)");
    }

    @Test
    void theComponentsAndTheirFields() throws IOException {
        JsonNode components = template().get("components");
        List<String> ids = new ArrayList<>();
        for (JsonNode c : components) {
            ids.add(c.get("id").asText());
            for (String field : List.of("id", "kind", "version", "requires", "excludes", "servlets", "properties")) {
                assertTrue(c.has(field), c.get("id") + " has " + field);
            }
            assertTrue(c.get("requires").isArray() && c.get("excludes").isArray() && c.get("servlets").isArray() && c.get("properties").isArray());
            switch (c.get("kind").asText()) {
                case "war", "overlay" -> {
                    assertTrue(c.has("artifact") && c.has("sha256") && c.has("deployAs"), c.get("id") + ": artifact, sha256, deployAs");
                    assertTrue(c.get("artifact").asText().startsWith("com.visco:"), "Maven coordinates");
                }
                case "container" -> assertTrue(c.has("image") && c.has("digest"), c.get("id") + ": image, digest");
                default -> throw new AssertionError("unknown kind " + c.get("kind"));
            }
        }
        assertEquals(List.of("store", "pack-health", "postgres"), ids, "exactly the Suite's components, in this order");
    }

    @Test
    void theStoreNeedsPostgresAndDeploysAsFhirStore() throws IOException {
        JsonNode store = template().get("components").get(0);
        assertEquals("com.visco:viscostore:${VERSION}:war", store.get("artifact").asText());
        assertEquals("fhir-store", store.get("deployAs").asText());
        assertEquals(List.of("postgres"), strings(store.get("requires")));
        assertEquals("${SHA_STORE}", store.get("sha256").asText());
    }

    @Test
    void theHealthPackDeclaresExactlyThePropertiesItsFileDefines() throws IOException {
        JsonNode pack = template().get("components").get(1);
        assertEquals("com.visco:viscolink-pack-health:${VERSION}:zip:overlay", pack.get("artifact").asText());
        assertEquals("overlay/runtime", pack.get("deployAs").asText());
        assertEquals("${SHA_PACK_HEALTH}", pack.get("sha256").asText());
        assertEquals(List.of(), strings(pack.get("servlets")), "the FHIR facade servlet registers itself with its own security; nothing to render");
        Set<String> fileKeys = new TreeSet<>();
        for (String line : Files.readAllLines(Path.of("../packs/health/src/main/resources/DeploymentSpecifics.properties"))) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("!") || !s.contains("=")) continue;
            fileKeys.add(s.substring(0, s.indexOf('=')).strip());
        }
        assertEquals(fileKeys, new TreeSet<>(strings(pack.get("properties"))),
            "properties[] = the keys of packs/health/src/main/resources/DeploymentSpecifics.properties (update both together)");
    }

    @Test
    void postgresIsPinnedByDigestAtReleaseTime() throws IOException {
        JsonNode pg = template().get("components").get(2);
        assertEquals("docker.io/library/postgres", pg.get("image").asText());
        assertEquals("16", pg.get("version").asText());
        assertEquals("${POSTGRES_DIGEST}", pg.get("digest").asText());
    }
}
```

Run: `./mvnw -q test -pl viscorunner -Dtest=ReleaseTemplateTest` → FAIL (no template).

- [ ] **Step 2: Write the template**

`viscorunner/release/viscosuite-release.template.json`:

```json
{
  "release": "${VERSION}",
  "base": {
    "image": "${BASE_IMAGE}",
    "digest": "${BASE_DIGEST}",
    "version": "${VERSION}",
    "servlets": ["ApiListenerServlet", "WebContentServlet", "configRef", "larvaRun", "agentApi", "pack"]
  },
  "suiteRelease": null,
  "components": [
    {
      "id": "store",
      "kind": "war",
      "artifact": "com.visco:viscostore:${VERSION}:war",
      "version": "${VERSION}",
      "sha256": "${SHA_STORE}",
      "deployAs": "fhir-store",
      "requires": ["postgres"],
      "excludes": [],
      "servlets": [],
      "properties": []
    },
    {
      "id": "pack-health",
      "kind": "overlay",
      "artifact": "com.visco:viscolink-pack-health:${VERSION}:zip:overlay",
      "version": "${VERSION}",
      "sha256": "${SHA_PACK_HEALTH}",
      "deployAs": "overlay/runtime",
      "requires": [],
      "excludes": [],
      "servlets": [],
      "properties": [
        "mllp.inbound.sendingApplication",
        "mllp.inbound.sendingFacility",
        "fhir.target.version",
        "viscostore.fhir.base.url",
        "mr.system.base"
      ]
    },
    {
      "id": "postgres",
      "kind": "container",
      "image": "docker.io/library/postgres",
      "version": "16",
      "digest": "${POSTGRES_DIGEST}",
      "requires": [],
      "excludes": [],
      "servlets": [],
      "properties": []
    }
  ]
}
```

Run: `./mvnw -q test -pl viscorunner -Dtest=ReleaseTemplateTest` → PASS (5 tests).

- [ ] **Step 3: Write `scripts/ci/write-release-bom.sh`**

```sh
#!/bin/sh
# write-release-bom.sh <version>: fills viscorunner/release/viscosuite-release.template.json with this
# pipeline's facts and uploads it as com.visco:viscosuite-release:<version> (one JSON file) to the
# project's Maven registry. Runs in the release job (image docker:27 + curl + jq) after the manifests:
#   SHA_*            from the Packages API (package_files[].file_sha256 of the published files), so the BOM
#                    pins what the registry really serves, not what we uploaded
#   BASE_DIGEST      the multi-arch manifest digest of viscobase:<version> (container registry API)
#   POSTGRES_DIGEST  docker buildx imagetools inspect of postgres:16 at release time
# Then proves anonymous pull (a public project; the Standard edition fetches without a token): one
# package file is fetched WITHOUT a token and must answer 200 with the pinned sha256.
set -eu
VERSION="${1:?usage: write-release-bom.sh <version>}"
API="${CI_API_V4_URL:?}/projects/${CI_PROJECT_ID:?}"
auth="JOB-TOKEN: ${CI_JOB_TOKEN:?}"
out="viscorunner/target/viscosuite-release-${VERSION}.json"

api() { curl --fail --silent --show-error --header "$auth" "$@"; }

package_id() { # <package_name> -> id of the package at $VERSION
  api "$API/packages?package_type=maven&package_name=$1&package_version=$VERSION&per_page=100" \
    | jq -r --arg n "$1" --arg v "$VERSION" '.[] | select(.name == $n and .version == $v) | .id' | head -1
}
file_sha() { # <package_name> <file_name>
  pid=$(package_id "$1"); [ -n "$pid" ] || { echo "write-release-bom: no package $1 $VERSION" >&2; exit 1; }
  api "$API/packages/$pid/package_files?per_page=100" | jq -r --arg f "$2" '.[] | select(.file_name == $f) | .file_sha256' | head -1
}

SHA_STORE=$(file_sha com/visco/viscostore "viscostore-${VERSION}.war")
SHA_PACK_HEALTH=$(file_sha com/visco/viscolink-pack-health "viscolink-pack-health-${VERSION}-overlay.zip")
[ -n "$SHA_STORE" ] && [ -n "$SHA_PACK_HEALTH" ] || { echo "write-release-bom: a package file sha256 is missing" >&2; exit 1; }

BASE_IMAGE="${CI_REGISTRY_IMAGE:?}/viscobase"
repo_id=$(api "$API/registry/repositories" | jq -r '.[] | select(.name == "viscobase") | .id')
BASE_DIGEST=$(api "$API/registry/repositories/$repo_id/tags/$VERSION" | jq -r '.digest')
case "$BASE_DIGEST" in sha256:*) ;; *) echo "write-release-bom: no digest for viscobase:$VERSION" >&2; exit 1 ;; esac
POSTGRES_DIGEST=$(docker buildx imagetools inspect docker.io/library/postgres:16 --format '{{json .Manifest.Digest}}' | tr -d '"')
case "$POSTGRES_DIGEST" in sha256:*) ;; *) echo "write-release-bom: no digest for postgres:16" >&2; exit 1 ;; esac

jq --arg v "$VERSION" --arg img "$BASE_IMAGE" --arg bd "$BASE_DIGEST" --arg ss "$SHA_STORE" --arg sp "$SHA_PACK_HEALTH" --arg pd "$POSTGRES_DIGEST" '
  walk(if type == "string" then
         gsub("\\$\\{VERSION\\}"; $v) | gsub("\\$\\{BASE_IMAGE\\}"; $img) | gsub("\\$\\{BASE_DIGEST\\}"; $bd)
         | gsub("\\$\\{SHA_STORE\\}"; $ss) | gsub("\\$\\{SHA_PACK_HEALTH\\}"; $sp) | gsub("\\$\\{POSTGRES_DIGEST\\}"; $pd)
       else . end)' viscorunner/release/viscosuite-release.template.json > "$out"
grep -q '\${' "$out" && { echo "write-release-bom: unfilled placeholder in $out" >&2; grep -n '\${' "$out" >&2; exit 1; }

url="$API/packages/maven/com/visco/viscosuite-release/$VERSION/viscosuite-release-$VERSION.json"
curl --fail --silent --show-error --header "$auth" --upload-file "$out" "$url" > /dev/null
echo "write-release-bom: uploaded $url"

# Anonymous pull proof (no token at all).
anon="$API/packages/maven/com/visco/viscostore/$VERSION/viscostore-$VERSION.war"
tmp=$(mktemp)
curl --fail --silent --show-error --location --output "$tmp" "$anon" || { echo "write-release-bom: anonymous pull of $anon failed: enable 'allow anyone to pull' on the package registry" >&2; exit 1; }
got=$(sha256sum "$tmp" | cut -d' ' -f1); rm -f "$tmp"
[ "$got" = "$SHA_STORE" ] || { echo "write-release-bom: anonymous pull served sha256 $got, the registry reports $SHA_STORE" >&2; exit 1; }
echo "write-release-bom: anonymous pull OK ($anon)"
cat "$out"
```

`chmod +x scripts/ci/write-release-bom.sh`. (The Maven registry's package name for a Maven artifact is its path, `com/visco/viscostore`; the `package_name` query matches it.)

- [ ] **Step 4: Dry-run the jq fill locally**

Run: `jq --arg v 1.0.0 --arg img x --arg bd sha256:a --arg ss s --arg sp p --arg pd sha256:b 'walk(if type == "string" then gsub("\\$\\{VERSION\\}"; $v) | gsub("\\$\\{BASE_IMAGE\\}"; $img) | gsub("\\$\\{BASE_DIGEST\\}"; $bd) | gsub("\\$\\{SHA_STORE\\}"; $ss) | gsub("\\$\\{SHA_PACK_HEALTH\\}"; $sp) | gsub("\\$\\{POSTGRES_DIGEST\\}"; $pd) else . end)' viscorunner/release/viscosuite-release.template.json | grep -c '\${'`
Expected: `0` (every placeholder replaced; `release`, `base.version`, both `artifact`s and both `version`s read `1.0.0`).

- [ ] **Step 5: Commit**

```bash
git add viscorunner/release/viscosuite-release.template.json viscorunner/pom.xml \
  viscorunner/src/test/java/com/viscosiety/viscorunner/ReleaseTemplateTest.java scripts/ci/write-release-bom.sh
git commit -m "build: the release BOM template and its writer"
```

---

### Task 9: The image smoke and the package cleanup

**Files:**
- Create: `scripts/ci/smoke-image.sh`, `scripts/ci/cleanup-packages.sh`
- Modify: `viscorunner/src/test/java/com/viscosiety/viscorunner/CiScriptsTest.java` (one more test)

**Interfaces:**
- Produces: `smoke-image.sh <image> [expected component ids…]` (boots the image with H2 for the runtime and, when `store` is expected, a `postgres:16` container; waits ≤ 240 s for `/runtime/iaf/api/server/health` 200; asserts `MANIFEST` lists exactly the expected ids; with `store`: `/fhir-store/fhir/metadata` 200; prints the pack descriptor is not checked (bearer-only)); `cleanup-packages.sh` (keeps every version without `-` and the newest 10 others per package name).

- [ ] **Step 1: Write the failing test for the cleanup rule**

The cleanup script takes its decisions in a pure function so the rule is testable without GitLab: `keep_or_delete` reads `created_at<TAB>version<TAB>id` lines on stdin, newest first, and prints `delete <id> <version>` for the ones to delete. Add to `CiScriptsTest`:

```java
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
        assertFalse(r.out().contains("0.22.0"), "a release is never deleted");
    }
```

(`import java.util.List;` and `assertFalse`.) Run: `./mvnw -q test -pl viscorunner -Dtest=CiScriptsTest` → FAIL (no script).

- [ ] **Step 2: Write `scripts/ci/cleanup-packages.sh`**

```sh
#!/bin/sh
# Deletes old main-commit package versions (A.B.C-<pipelineId>) from the project's Maven registry,
# keeping every release (a version without '-') and the newest KEEP (10) others per package name.
# Runs in the cleanup job with the job token. Nothing else is ever deleted.
# CLEANUP_DECIDE_ONLY=<file>: read "created_at<TAB>version<TAB>id" lines (newest first) from the file,
# print the decisions and exit (the unit test).
set -eu
KEEP="${KEEP:-10}"

decide() { # stdin: created_at \t version \t id, newest first
  awk -F'\t' -v keep="$KEEP" '
    { v = $2; id = $3 }
    v !~ /-/ { print "keep " id " " v " (release)"; next }
    { n++; if (n <= keep) print "keep " id " " v; else print "delete " id " " v }'
}

if [ -n "${CLEANUP_DECIDE_ONLY:-}" ]; then
  decide < "$CLEANUP_DECIDE_ONLY"
  exit 0
fi

API="${CI_API_V4_URL:?}/projects/${CI_PROJECT_ID:?}"
auth="JOB-TOKEN: ${CI_JOB_TOKEN:?}"
for name in com/visco/viscolink com/visco/viscolink-pack-health com/visco/viscostore com/visco/viscosuite-release; do
  curl --fail --silent --show-error --header "$auth" \
    "$API/packages?package_type=maven&package_name=$name&order_by=created_at&sort=desc&per_page=100" \
    | jq -r --arg n "$name" '.[] | select(.name == $n) | [.created_at, .version, .id] | @tsv' \
    | decide \
    | while read -r action id version; do
        echo "$name $version: $action"
        if [ "$action" = delete ]; then
          curl --fail --silent --show-error --header "$auth" --request DELETE "$API/packages/$id" > /dev/null
        fi
      done
done
```

`chmod +x`. Run the test → PASS.

- [ ] **Step 3: Write `scripts/ci/smoke-image.sh`**

```sh
#!/bin/sh
# smoke-image.sh <image> [component id ...]: boot a just-built runner image and check it serves.
#   - the Frank!Framework answers GET /runtime/iaf/api/server/health 200 within 240 s (H2 in memory)
#   - /opt/frank/components/MANIFEST lists exactly the given component ids (none = viscobase)
#   - with "store": a postgres:16 container is started first and GET /fhir-store/fhir/metadata answers 200
# Runs inside the docker jobs on the per-arch image just pushed. Everything it starts is removed.
set -eu
IMAGE="${1:?usage: smoke-image.sh <image> [component id ...]}"; shift
expected="$*"
net="smoke-$$"
cleanup() { docker rm -f smoke-runner smoke-postgres >/dev/null 2>&1 || true; docker network rm "$net" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker network create "$net" >/dev/null

env_args="-e dtap.stage=LOC -e ladybug.jdbc.datasource=jdbc/ladybug \
 -e VISCOLINK_JDBC_DRIVER=org.h2.Driver -e VISCOLINK_JDBC_URL=jdbc:h2:mem:viscolink;DB_CLOSE_DELAY=-1 -e VISCOLINK_JDBC_USERNAME=sa -e VISCOLINK_JDBC_PASSWORD= \
 -e LADYBUG_JDBC_DRIVER=org.h2.Driver -e LADYBUG_JDBC_URL=jdbc:h2:mem:ladybug;DB_CLOSE_DELAY=-1 -e LADYBUG_JDBC_USERNAME=sa -e LADYBUG_JDBC_PASSWORD= \
 -e JAVA_OPTS=-Dspring.profiles.active=ladybug-database"
case " $expected " in *" store "*)
  docker run -d --name smoke-postgres --network "$net" -e POSTGRES_USER=visco -e POSTGRES_PASSWORD=visco -e POSTGRES_DB=viscostore postgres:16 >/dev/null
  env_args="$env_args -e VISCOSTORE_JDBC_DRIVER=org.postgresql.Driver -e VISCOSTORE_JDBC_URL=jdbc:postgresql://smoke-postgres:5432/viscostore \
    -e VISCOSTORE_JDBC_USERNAME=visco -e VISCOSTORE_JDBC_PASSWORD=visco -e VISCOSTORE_JDBC_DIALECT=ca.uhn.fhir.jpa.model.dialect.HapiFhirPostgresDialect \
    -e VISCOSTORE_USERNAME=viscolink -e VISCOSTORE_PASSWORD=viscolink"
  ;;
esac
# shellcheck disable=SC2086
docker run -d --name smoke-runner --network "$net" $env_args "$IMAGE" >/dev/null

wait_200() { # <path> <seconds>
  i=0
  while [ "$i" -lt "$2" ]; do
    if docker exec smoke-runner curl --fail --silent --output /dev/null "http://localhost:8080$1"; then return 0; fi
    i=$((i + 5)); sleep 5
  done
  echo "smoke: $1 did not answer 200 within $2 s; last log lines:" >&2
  docker logs --tail 80 smoke-runner >&2
  return 1
}
wait_200 /runtime/iaf/api/server/health 240

actual=$(docker exec smoke-runner sh -c 'test -f /opt/frank/components/MANIFEST && sed -n "s/^component=\([^ ]*\) .*/\1/p" /opt/frank/components/MANIFEST | tr "\n" " "' || true)
actual=$(echo "$actual" | sed 's/ *$//')
[ "$actual" = "$expected" ] || { echo "smoke: MANIFEST lists '$actual', expected '$expected'" >&2; exit 1; }

case " $expected " in *" store "*) wait_200 /fhir-store/fhir/metadata 240 ;; esac
echo "smoke: $IMAGE OK (components: ${expected:-none})"
```

`chmod +x`. The ladybug H2 datasource needs the Ladybug schema: the `ladybug-database` profile runs Liquibase on it at start, which is what the compose stacks do on Postgres; H2 in memory is the same path with the H2 dialect. If the runtime refuses the in-memory URL in practice (seen in the first CI run as a `jdbc/ladybug` failure in the log tail), switch both URLs to `jdbc:h2:file:/usr/local/tomcat/temp/<name>` (a writable dir of the tomcat user) and record it here.

- [ ] **Step 4: Run the smoke locally on the images of Task 6 step 5**

Run (from the repo root): `sh scripts/ci/smoke-image.sh viscobase:m1-local && sh scripts/ci/smoke-image.sh viscorunner:m1-local store pack-health`
Expected: `smoke: viscobase:m1-local OK (components: none)` and `smoke: viscorunner:m1-local OK (components: store pack-health)`. If the store check fails only on the dialect or the database name, fix the env in the script (the compose file is the reference) and re-run.

- [ ] **Step 5: Commit**

```bash
git add scripts/ci/smoke-image.sh scripts/ci/cleanup-packages.sh viscorunner/src/test/java/com/viscosiety/viscorunner/CiScriptsTest.java
git commit -m "ci: image smoke and old-package cleanup scripts"
```

---

### Task 10: The pipeline: version, publish, build from the registry, release, trigger, cleanup

**Files:**
- Modify: `.gitlab-ci.yml` (rewritten; the full file follows)

**Interfaces:**
- Consumes: every script of Tasks 1, 2, 8, 9; the Dockerfile stages of Task 5.
- Produces: packages `com/visco/{viscolink,viscolink-pack-health,viscostore,viscosuite-release}` at `<version>`; images `viscobase`, `viscorunner`, `viscolink` (+ `viscolink:…-core` alias), `viscostore` tagged `<sha>`, `<version>`, `latest` (+ the `-health` aliases, kept until M4); the downstream trigger with `UPSTREAM_RELEASE` and `UPSTREAM_TAG`.

- [ ] **Step 1: Replace `.gitlab-ci.yml`**

```yaml
# ViscoSuite CI/CD pipeline
#
# Stages:
#   test              unit + integration tests, viscorunner's fast ITs and script tests (every branch, MR and tag)
#   package           versions:set to this pipeline's version, full Maven build, the staged publish files (main + release tags)
#   integration-test  viscorunner's launcher ITs (main + release tags)
#   publish           the three component artifacts to this project's Maven package registry + the registry components lists
#   docker            viscobase, viscorunner (fat), viscolink (fat), viscostore — per architecture, each smoke-tested before its push
#   manifest          multi-arch manifests; viscolink:<v>-core is viscobase under another name
#   release           the release BOM viscosuite-release:<v> (and the anonymous-pull proof)
#   downstream        trigger viscoForge (UPSTREAM_RELEASE + UPSTREAM_TAG); the GitHub release on a tag
#   cleanup           per-arch image tags; old main-commit packages
#
# Version (scripts/ci/version.sh): a tag vA.B.C publishes A.B.C (it must equal the poms' A.B.C-SNAPSHOT
# base); a main commit publishes A.B.C-<pipeline id>. Image tags: <sha>, <version>, latest.
#
# Runners: self-hosted Docker runners
#   amd64: tags [docker, x86-64, dind]
#   arm64: tags [docker, arm64, dind]  (Raspberry Pi 5)
# Registry: $CI_REGISTRY_IMAGE (GitLab project registry)
#
# Images (viscorunner/Dockerfile, two stages; the component model, viscoFoundry design 2026-11-04):
#   image        stage      components fetched at build        tags (all multi-arch)
#   viscobase    viscobase  none                               :<sha> :<version> :latest  (+ viscolink:<sha>-core, :<version>-core, :latest-core)
#   viscorunner  fat        store + pack-health                :<sha> :<version> :latest  :<sha>-health :latest-health
#   viscolink    fat        pack-health                        :<sha> :<version> :latest  :<sha>-health :latest-health
#   viscostore   -          -                                  :<sha> :<version> :latest  (viscorunner/Dockerfile.viscostore)
# The -health aliases stay until M4 of the component model. Per-arch build intermediates are <sha>-amd64 / <sha>-arm64.

stages:
  - test
  - package
  - integration-test
  - publish
  - docker
  - manifest
  - release
  - downstream
  - cleanup

variables:
  MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"
  # -B: batch mode. No -U: the F!F dependency is pinned to an immutable version (see the root pom).
  MAVEN_CLI_OPTS: "-B -s .mvn/settings.xml"
  IMAGE_VISCOBASE: $CI_REGISTRY_IMAGE/viscobase
  IMAGE:           $CI_REGISTRY_IMAGE/viscorunner   # the suite image (health + store)
  IMAGE_VISCOLINK: $CI_REGISTRY_IMAGE/viscolink     # health, no store; its -core tags are viscobase
  IMAGE_VISCOSTORE: $CI_REGISTRY_IMAGE/viscostore   # the store-only image
  VERSIONS_PLUGIN: org.codehaus.mojo:versions-maven-plugin:2.18.0
  # Disable TLS for Docker-in-Docker on self-hosted runners
  DOCKER_TLS_CERTDIR: ""

# Maven dependency cache — ONE shared key across all branches (see the comment in git history: the
# local repo is append-only in practice). Branch/MR jobs pull; only the main-only package job pushes.
.maven-cache: &maven-cache
  cache:
    key: maven-repo
    paths:
      - .m2/repository/
    policy: pull

.maven-cache-push: &maven-cache-push
  cache:
    key: maven-repo
    paths:
      - .m2/repository/
    policy: pull-push

# Node + pnpm for the UI builds, unzip for the fetcher's script tests.
.maven-before: &maven-before
  - apt-get update
  - apt-get install -y curl ca-certificates unzip
  - curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  - apt-get install -y nodejs
  - corepack enable
  - corepack prepare pnpm@9.14.2 --activate

# main commits and release tags publish; everything else only tests.
.on-main-or-release: &on-main-or-release
  rules:
    - if: '$CI_COMMIT_BRANCH == "main"'
    - if: '$CI_COMMIT_TAG =~ /^v\d+\.\d+\.\d+$/'

# ---------------------------------------------------------------------------
# Stage: test — every push, merge request and tag.
# One `install` (tests on) runs the three modules' tests and leaves their artifacts in .m2/, which
# viscorunner's copy-dependencies and unpack resolve from (not the reactor — see CLAUDE.md).
# `verify -pl viscorunner` runs its unit tests (Dockerfile contract, the fetcher and CI scripts, the
# release template, the event publisher) and the two fast ITs.
# ---------------------------------------------------------------------------
test:
  stage: test
  image: maven:3.9-eclipse-temurin-21
  <<: *maven-cache
  before_script: *maven-before
  tags: [docker]
  script:
    - ./mvnw $MAVEN_CLI_OPTS install -pl viscolink,packs/health,viscostore
    - ./mvnw $MAVEN_CLI_OPTS verify -pl viscorunner -Dit.test=HealthClasspathIT,PackOverlayLayoutIT
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
    - if: '$CI_COMMIT_BRANCH'
    - if: '$CI_COMMIT_TAG'

# ---------------------------------------------------------------------------
# Stage: package — pure artifact production at this pipeline's version.
# versions:set rewrites every module pom (they have no common parent, hence processAllModules), then
# install + package as before, then stage-publish.sh collects the bytes the publish job uploads and the
# fat images fetch. VERSION is exported through a dotenv report for the trigger job.
# ---------------------------------------------------------------------------
package:
  stage: package
  image: maven:3.9-eclipse-temurin-21
  <<: *maven-cache-push
  before_script: *maven-before
  tags: [docker]
  script:
    - VERSION=$(sh scripts/ci/version.sh) && echo "VERSION=$VERSION" > version.env && echo "version $VERSION"
    - ./mvnw $MAVEN_CLI_OPTS -q $VERSIONS_PLUGIN:set -DnewVersion="$VERSION" -DprocessAllModules=true -DgenerateBackupPoms=false
    - ./mvnw $MAVEN_CLI_OPTS install -pl viscolink,packs/health,viscostore -DskipTests
    - ./mvnw $MAVEN_CLI_OPTS package -pl viscorunner -DskipTests
    - sh scripts/ci/stage-publish.sh "$VERSION"
  artifacts:
    reports:
      dotenv: version.env
    paths:
      - viscorunner/target/viscolink.war
      - viscorunner/target/drivers/
      - viscorunner/target/native/
      - viscorunner/target/demo/
      - viscorunner/target/viscorunner-*.jar
      - viscorunner/target/publish/
      - packs/health/target/viscolink-pack-health-*.jar
    # target/viscostore.war and target/packs/ are no longer shipped: the images fetch the store and the pack
    # from the registry (publish.sh), and the ITs rebuild their own staging in integration-test.
    expire_in: 1 hour
  <<: *on-main-or-release

# ---------------------------------------------------------------------------
# Stage: integration-test — viscorunner's launcher ITs at the same version.
# ---------------------------------------------------------------------------
integration-test:
  stage: integration-test
  image: maven:3.9-eclipse-temurin-21
  <<: *maven-cache
  before_script: *maven-before
  tags: [docker]
  script:
    - VERSION=$(sh scripts/ci/version.sh)
    - ./mvnw $MAVEN_CLI_OPTS -q $VERSIONS_PLUGIN:set -DnewVersion="$VERSION" -DprocessAllModules=true -DgenerateBackupPoms=false
    - ./mvnw $MAVEN_CLI_OPTS install -pl viscolink,packs/health,viscostore -DskipTests
    - ./mvnw $MAVEN_CLI_OPTS verify   -pl viscorunner
  <<: *on-main-or-release

# ---------------------------------------------------------------------------
# Stage: publish — the three component artifacts, exactly the staged bytes, after the ITs passed.
# Writes the registry components lists the fat image builds fetch from.
# ---------------------------------------------------------------------------
publish:
  stage: publish
  image: maven:3.9-eclipse-temurin-21
  <<: *maven-cache
  tags: [docker]
  needs:
    - job: package
      artifacts: true
    - job: integration-test
      artifacts: false
  script:
    - sh scripts/ci/publish.sh
  artifacts:
    paths:
      - viscorunner/target/components-registry-suite.list
      - viscorunner/target/components-registry-viscolink.list
    expire_in: 1 hour
  <<: *on-main-or-release

# ---------------------------------------------------------------------------
# Stage: docker — one recipe (viscorunner/Dockerfile), a stage and a list per image, per architecture.
# A fat build fetches the just-published artifacts from the registry with the job token handed in as a
# BuildKit secret (never a build argument). Every image is booted and checked (scripts/ci/smoke-image.sh)
# before it is pushed.
# ---------------------------------------------------------------------------
.docker-build:
  stage: docker
  image: docker:27
  services:
    - docker:27-dind
  needs:
    - job: package
      artifacts: true
    - job: publish
      artifacts: true
  before_script:
    - docker login -u "$CI_REGISTRY_USER" -p "$CI_REGISTRY_PASSWORD" "$CI_REGISTRY"
  script:
    - VERSION=$(sh scripts/ci/version.sh)
    # The fat stage COPYs target/components/ (local artifacts for file:// lists); CI lists carry registry URLs, so it is empty here.
    - mkdir -p viscorunner/target/components
    - |
      LIST_ARG=""
      if [ "$TARGET" != viscobase ]; then LIST_ARG="--build-arg COMPONENTS_LIST=target/$COMPONENTS_LIST"; fi
    - docker build --platform "linux/$ARCH" --target "$TARGET" $LIST_ARG
        --secret id=job_token,env=CI_JOB_TOKEN
        --build-arg "BUILD_TIMESTAMP=$(date -u '+%Y-%m-%d %H:%M UTC') · $VERSION ($CI_COMMIT_SHORT_SHA)"
        -t "$CI_REGISTRY_IMAGE/$IMAGE_NAME:$CI_COMMIT_SHA-$ARCH"
        viscorunner/
    - sh scripts/ci/smoke-image.sh "$CI_REGISTRY_IMAGE/$IMAGE_NAME:$CI_COMMIT_SHA-$ARCH" $SMOKE_COMPONENTS
    - docker push "$CI_REGISTRY_IMAGE/$IMAGE_NAME:$CI_COMMIT_SHA-$ARCH"
  <<: *on-main-or-release

.viscobase: &viscobase
  TARGET: viscobase
  IMAGE_NAME: viscobase
  COMPONENTS_LIST: ""
  SMOKE_COMPONENTS: ""
.suite: &suite
  TARGET: fat
  IMAGE_NAME: viscorunner
  COMPONENTS_LIST: components-registry-suite.list
  SMOKE_COMPONENTS: "store pack-health"
.link: &link
  TARGET: fat
  IMAGE_NAME: viscolink
  COMPONENTS_LIST: components-registry-viscolink.list
  SMOKE_COMPONENTS: "pack-health"

docker:viscobase:amd64:   { extends: .docker-build, tags: [docker, x86-64, dind], variables: { <<: *viscobase, ARCH: amd64 } }
docker:viscobase:arm64:   { extends: .docker-build, tags: [docker, arm64, dind],  variables: { <<: *viscobase, ARCH: arm64 } }
docker:viscosuite:amd64:  { extends: .docker-build, tags: [docker, x86-64, dind], variables: { <<: *suite,     ARCH: amd64 } }
docker:viscosuite:arm64:  { extends: .docker-build, tags: [docker, arm64, dind],  variables: { <<: *suite,     ARCH: arm64 } }
docker:viscolink:amd64:   { extends: .docker-build, tags: [docker, x86-64, dind], variables: { <<: *link,      ARCH: amd64 } }
docker:viscolink:arm64:   { extends: .docker-build, tags: [docker, arm64, dind],  variables: { <<: *link,      ARCH: arm64 } }

# ---------------------------------------------------------------------------
# Stage: docker — standalone ViscoStore image (unchanged recipe; needs the staged store WAR from publish/).
# ---------------------------------------------------------------------------
.docker-viscostore:
  stage: docker
  image: docker:27
  services:
    - docker:27-dind
  needs:
    - job: package
      artifacts: true
    - job: integration-test
      artifacts: false
  before_script:
    - docker login -u "$CI_REGISTRY_USER" -p "$CI_REGISTRY_PASSWORD" "$CI_REGISTRY"
  script:
    - cp viscorunner/target/publish/viscostore.war viscorunner/target/viscostore.war
    - docker build --platform "linux/$ARCH"
        -f viscorunner/Dockerfile.viscostore
        --build-arg "BUILD_TIMESTAMP=$(date -u '+%Y-%m-%d %H:%M UTC') · $CI_COMMIT_SHORT_SHA"
        -t "$IMAGE_VISCOSTORE:$CI_COMMIT_SHA-$ARCH"
        viscorunner/
    - docker push "$IMAGE_VISCOSTORE:$CI_COMMIT_SHA-$ARCH"
  <<: *on-main-or-release

docker:viscostore:amd64: { extends: .docker-viscostore, tags: [docker, x86-64, dind], variables: { ARCH: amd64 } }
docker:viscostore:arm64: { extends: .docker-viscostore, tags: [docker, arm64, dind],  variables: { ARCH: arm64 } }

# ---------------------------------------------------------------------------
# Stage: manifest — multi-arch manifests. viscobase is also tagged as viscolink:…-core (the core IS the
# bare image). If imagetools cannot tag across repositories on this registry, replace the two
# viscolink -core lines with: docker pull/tag/push of each per-arch viscobase image under
# viscolink:<sha>-core-<arch>, then a second imagetools create from those.
# ---------------------------------------------------------------------------
.manifest:
  stage: manifest
  image: docker:27
  tags: [dind]
  services:
    - docker:27-dind
  before_script:
    - docker login -u "$CI_REGISTRY_USER" -p "$CI_REGISTRY_PASSWORD" "$CI_REGISTRY"
  <<: *on-main-or-release

manifest:viscobase:
  extends: .manifest
  needs:
    - docker:viscobase:amd64
    - docker:viscobase:arm64
  script:
    - VERSION=$(sh scripts/ci/version.sh)
    - docker buildx imagetools create
        -t "$IMAGE_VISCOBASE:$CI_COMMIT_SHA" -t "$IMAGE_VISCOBASE:$VERSION" -t "$IMAGE_VISCOBASE:latest"
        -t "$IMAGE_VISCOLINK:$CI_COMMIT_SHA-core" -t "$IMAGE_VISCOLINK:$VERSION-core" -t "$IMAGE_VISCOLINK:latest-core"
        "$IMAGE_VISCOBASE:$CI_COMMIT_SHA-amd64"
        "$IMAGE_VISCOBASE:$CI_COMMIT_SHA-arm64"

manifest:viscosuite:
  extends: .manifest
  needs:
    - docker:viscosuite:amd64
    - docker:viscosuite:arm64
  script:
    - VERSION=$(sh scripts/ci/version.sh)
    - docker buildx imagetools create
        -t "$IMAGE:$CI_COMMIT_SHA" -t "$IMAGE:$VERSION" -t "$IMAGE:latest"
        -t "$IMAGE:$CI_COMMIT_SHA-health" -t "$IMAGE:latest-health"
        "$IMAGE:$CI_COMMIT_SHA-amd64"
        "$IMAGE:$CI_COMMIT_SHA-arm64"

manifest:viscolink:
  extends: .manifest
  needs:
    - docker:viscolink:amd64
    - docker:viscolink:arm64
  script:
    - VERSION=$(sh scripts/ci/version.sh)
    - docker buildx imagetools create
        -t "$IMAGE_VISCOLINK:$CI_COMMIT_SHA" -t "$IMAGE_VISCOLINK:$VERSION" -t "$IMAGE_VISCOLINK:latest"
        -t "$IMAGE_VISCOLINK:$CI_COMMIT_SHA-health" -t "$IMAGE_VISCOLINK:latest-health"
        "$IMAGE_VISCOLINK:$CI_COMMIT_SHA-amd64"
        "$IMAGE_VISCOLINK:$CI_COMMIT_SHA-arm64"

manifest:viscostore:
  extends: .manifest
  needs:
    - docker:viscostore:amd64
    - docker:viscostore:arm64
  script:
    - VERSION=$(sh scripts/ci/version.sh)
    - docker buildx imagetools create
        -t "$IMAGE_VISCOSTORE:$CI_COMMIT_SHA" -t "$IMAGE_VISCOSTORE:$VERSION" -t "$IMAGE_VISCOSTORE:latest"
        "$IMAGE_VISCOSTORE:$CI_COMMIT_SHA-amd64"
        "$IMAGE_VISCOSTORE:$CI_COMMIT_SHA-arm64"

# ---------------------------------------------------------------------------
# Stage: release — the BOM. Pins the registry's own sha256 of each package file and viscobase's manifest
# digest, uploads viscosuite-release:<version>, and proves one package file is pullable without a token.
# ---------------------------------------------------------------------------
release:bom:
  stage: release
  image: docker:27
  tags: [dind]
  services:
    - docker:27-dind
  needs:
    - manifest:viscobase
    - manifest:viscosuite
    - manifest:viscolink
    - job: publish
      artifacts: false
  before_script:
    - apk add --no-cache curl jq
  script:
    - VERSION=$(sh scripts/ci/version.sh)
    - sh scripts/ci/write-release-bom.sh "$VERSION"
  artifacts:
    paths:
      - viscorunner/target/viscosuite-release-*.json
    expire_in: 1 week
  <<: *on-main-or-release

# ---------------------------------------------------------------------------
# Stage: downstream — trigger viscoForge once the release exists. UPSTREAM_RELEASE is the version
# (viscoForge resolves the Suite BOM from it, from M2 on); UPSTREAM_TAG stays for the Forge pipeline as
# it is today (it builds FROM viscorunner:<sha> until M2). VERSION comes from package's dotenv report.
# ---------------------------------------------------------------------------
trigger:viscoforge:
  stage: downstream
  needs:
    - job: package
      artifacts: true
    - release:bom
    - manifest:viscosuite
    - manifest:viscolink
    - manifest:viscobase
  allow_failure: true
  variables:
    UPSTREAM_TAG: "$CI_COMMIT_SHA"
    UPSTREAM_RELEASE: "$VERSION"
  trigger:
    project: commercial/viscoforge
    branch: main
  <<: *on-main-or-release

# ---------------------------------------------------------------------------
# Stage: cleanup — per-arch intermediates; old main-commit packages (releases are kept forever).
# ---------------------------------------------------------------------------
cleanup:arch-tags:
  stage: cleanup
  image: alpine:3
  tags: [docker]
  needs:
    - manifest:viscobase
    - manifest:viscosuite
    - manifest:viscolink
    - manifest:viscostore
  before_script:
    - apk add --no-cache curl jq
  script:
    - |
      REPOS=$(curl --silent --header "JOB-TOKEN: $CI_JOB_TOKEN" \
        "${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/registry/repositories")
      for IMAGE_NAME in viscobase viscorunner viscolink viscostore; do
        REPO_ID=$(echo "$REPOS" | jq -r ".[] | select(.name == \"${IMAGE_NAME}\") | .id")
        if [ -z "$REPO_ID" ] || [ "$REPO_ID" = "null" ]; then
          echo "WARNING: repo not found for ${IMAGE_NAME}, skipping"
          continue
        fi
        for ARCH in amd64 arm64; do
          TAG="${CI_COMMIT_SHA}-${ARCH}"
          STATUS=$(curl --silent --output /dev/null --write-out "%{http_code}" \
            --request DELETE \
            --header "JOB-TOKEN: $CI_JOB_TOKEN" \
            "${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/registry/repositories/${REPO_ID}/tags/${TAG}")
          echo "${IMAGE_NAME}:${TAG} → HTTP ${STATUS}"
        done
      done
  <<: *on-main-or-release

cleanup:packages:
  stage: cleanup
  image: alpine:3
  tags: [docker]
  needs:
    - release:bom
  before_script:
    - apk add --no-cache curl jq
  script:
    - sh scripts/ci/cleanup-packages.sh
  <<: *on-main-or-release

# ---------------------------------------------------------------------------
# Open-source distribution: the GitHub Release on a tag (unchanged; see git history for the token notes).
# ---------------------------------------------------------------------------
github-release:
  stage: downstream
  image: alpine:3.20
  tags: [docker]
  rules:
    - if: '$CI_COMMIT_TAG'
  script:
    - apk add --no-cache curl
    - |
      if [ -z "${GITHUB_TOKEN}" ]; then
        echo "ERROR: GITHUB_TOKEN is empty in this job (a Protected variable: protect tags matching 'v*')."
        exit 1
      fi
    - |
      STATUS=$(curl --silent --show-error --output /tmp/gh-release.json --write-out "%{http_code}" -X POST \
        -H "Authorization: Bearer ${GITHUB_TOKEN}" \
        -H "Accept: application/vnd.github+json" \
        https://api.github.com/repos/viscosiety/viscosuite/releases \
        -d "{\"tag_name\":\"${CI_COMMIT_TAG}\",\"target_commitish\":\"${CI_COMMIT_SHA}\",\"name\":\"ViscoSuite ${CI_COMMIT_TAG}\",\"generate_release_notes\":true}")
      cat /tmp/gh-release.json
      echo ""
      case "$STATUS" in
        201) echo "GitHub release created for ${CI_COMMIT_TAG}." ;;
        422) echo "GitHub release for ${CI_COMMIT_TAG} already exists - nothing to do." ;;
        401|403) echo "GitHub rejected the token (HTTP ${STATUS}) - check the PAT's scope/expiry."; exit 1 ;;
        404) echo "HTTP 404 - the PAT cannot see viscosiety/viscosuite (needs the 'repo' scope on a classic PAT)."; exit 1 ;;
        *) echo "GitHub API returned HTTP ${STATUS}."; exit 1 ;;
      esac
```

(The `github-release` job's long token comment from today's file is shortened above on purpose; keep today's full comment block if you prefer, the behaviour is identical.)

- [ ] **Step 2: Lint the pipeline**

Run: `GITLAB_HOST=git.viscosiety.com glab ci lint .gitlab-ci.yml` (from the worktree root).
Expected: `valid`. Fix YAML errors before committing (anchors inside `variables:` maps and the `<<: *on-main-or-release` rules merge are the usual culprits).

- [ ] **Step 3: Commit**

```bash
git add .gitlab-ci.yml
git commit -m "ci: publish the components, build viscobase and the fat images from the registry, write the release BOM"
```

- [ ] **Step 4: Open the MR and read the first pipeline**

Push the branch (per the memory `git_push_hardware_key_workflow`: `git -c credential.helper='!glab auth git-credential' push https://git.viscosiety.com/public-applications/viscosuite.git feat/component-model-m1` with the sandbox disabled), open the MR with `glab mr create`. Only `test` runs on the MR; it must be green (the new unit tests, the two fast ITs). The main-only stages run after the merge (Task 12).

---

### Task 11: Documentation (same MR)

**Files:**
- Modify: `CHANGELOG.md` (Unreleased), `CLAUDE.md` (Build Commands, viscorunner, Packs/Images, Configuration Files; a new "Releases and the component registry" section), `README.md`, `viscorunner/README.md` ("Images and compose files"), `docs/design/README.md`
- Create: `docs/design/2026-11-04-component-delivery-design.md`

**Interfaces:** none; the text below is the deliverable.

- [ ] **Step 1: CHANGELOG**

Under `## [Unreleased]` add before the existing `### Added` items:

```markdown
### Added
- **Components and releases.** Every Java component of ViscoSuite is a versioned Maven artifact in
  this project's GitLab package registry: `com.visco:viscolink` (the core WAR and its `classes` jar),
  `com.visco:viscolink-pack-health` (the health pack's overlay zip) and `com.visco:viscostore` (the
  store WAR). Each `main` commit publishes `A.B.C-<pipeline id>`, a tag `vA.B.C` publishes `A.B.C`,
  and a release BOM `com.visco:viscosuite-release:<version>` (JSON) pins every file's sha256, the
  `viscobase` image digest and the Postgres image digest. Old main-commit versions are cleaned up
  (the newest 10 per package stay; releases stay forever).
- **`viscobase`**, a new image: Tomcat + the Frank!Framework webapp at `/runtime` + the component
  fetcher, no pack, no store. `/opt/frank/bin/fetch-components <list>` downloads the components a
  list names (`file://` or registry URLs), verifies every byte against the list's sha256, unpacks
  overlays and writes `/opt/frank/components/MANIFEST`; `place-components` (also run by the
  entrypoint at every start) copies them into Tomcat. The fat images `viscorunner` and `viscolink`
  are `viscobase` plus components fetched at build time; `viscolink:…-core` is `viscobase` itself.
- `GET /api-service/pack` (and ViscoFlow's `/flow-api/pack`) report `release` and `components[]`
  (`id`, `kind`, `version`) from the fetcher's manifest; a runtime without one reports `null` and `[]`.

### Changed
- One two-stage `viscorunner/Dockerfile` (`viscobase`, `fat`); the `PACK` and `STORE` build arguments
  are gone, a fat build takes `COMPONENTS_LIST`. The global Tomcat context (`conf/context.xml`)
  declares `jdbc/viscolink`, `jdbc/ladybug` and `jdbc/viscostore` on every image (each created lazily
  at its first lookup; the Frank!Framework never lists a datasource it has not looked up).
- The landing page reads the pack and the release from `components.json` next to it instead of
  words baked at image build; the store-only image shows the core's words.
- Both packs' demo configurations ship in every image under `/opt/frank/demo-configurations/health/`
  and `/opt/frank/demo-configurations/core/` (was: one set at the root, per image).
- The module version (`ViscoLinkModule.IMPLEMENTATION_VERSION`, the core pack's version) follows the
  Maven build instead of a constant.

### Removed
- `viscorunner/conf/context-none.xml` and `context-viscostore.xml` (now one `context.xml`), the pack
  identity files `WEB-INF/pack.properties` (identity lives in `HealthPack.java` / `CorePack.java`),
  the separate `viscolink:-core` image build.
```

- [ ] **Step 2: CLAUDE.md**

Replace the Build Commands block's first lines with:

```bash
# Build all modules and install to local repo (required before docker compose up --build)
./mvnw install -pl viscolink,packs/health,viscostore
./mvnw package -pl viscorunner -DskipTests
# viscorunner's package also stages target/components/ (the store WAR and the health overlay zip) and the
# local components lists target/components-suite.list / target/components-viscolink.list (file:// URLs
# + sha256), which the fat stage of the Dockerfile fetches from. CI writes the same lists with registry URLs.
```

Replace the viscorunner section's items 3-4 with:

```markdown
3. Unpacks the health pack's overlay zip to `target/packs/health/` (for the launcher ITs), stages the demo sets `target/demo/{health,core}/`, the component files `target/components/` and the two local components lists
4. Builds Tomcat 11 / JRE 21 Docker images from ONE two-stage `Dockerfile`: stage `viscobase` (Tomcat + `runtime.war` + the fetcher; no pack, no store — the commercial runner and `viscolink:…-core`) and stage `fat` (`viscobase` + the components a list names, fetched and placed at build time: `viscorunner` = store + health pack, `viscolink` = health pack). `--target fat --build-arg COMPONENTS_LIST=target/components-suite.list`
```

In the runtime directories table replace the two rows and add three:

```markdown
| `/opt/frank/demo-configurations/{health,core}/` | Both packs' demo sets, baked into every image; never loaded unless mounted over `/opt/frank/configurations` |
| `/opt/frank/webapp-overlay/viscolink/` | The PreResources dir of `/runtime`: a pack's `WEB-INF/lib` and `WEB-INF/classes` (placed by `place-components`; the dir keeps its old name until M4 of the component model) |
| `/opt/frank/webapp-overlay/console/` | The PreResources dir of a ViscoForge console (`/console`), empty here |
| `/opt/frank/components/` | What `fetch-components` fetched: `webapps/<context>.war`, `overlay/<target>/`, `MANIFEST`, `components.json`; on the platform an emptyDir filled by an init container |
| `/opt/frank/bin/{fetch-components,place-components}` | The fetcher (download, sha256, unzip, manifest) and the placer (copies into Tomcat at build and at every start) |
```

Replace the Packs section's **Images** bullet with:

```markdown
- **Images and releases**: see "Releases and the component registry" below. CI publishes `viscobase:<sha>`/`:<version>`/`:latest` (no pack, no store; `/pack` answers `core` with `release` and an empty `components`), `viscorunner` (health + store) and `viscolink` (health) built `FROM viscobase` with the components fetched from the registry, `viscolink:…-core` = `viscobase` under another name, and `viscostore` (store-only). The `-health` aliases stay until M4. The landing page reads `components.json`; `BuildInfo` does not carry the pack.
```

In the Configuration Files table replace the context row with:

```markdown
| `viscorunner/conf/context.xml` | The one Tomcat global context: `jdbc/viscolink`, `jdbc/ladybug`, `jdbc/viscostore`, each from env and created at its first JNDI lookup |
| `viscorunner/release/viscosuite-release.template.json` | The release BOM's static part (component ids, kinds, `deployAs`, `requires`, `servlets`, `properties`); `ReleaseTemplateTest` ties `pack-health.properties` to the pack's `DeploymentSpecifics.properties` keys |
```

Add a section after "Packs":

```markdown
### Releases and the component registry (the component model, M1, 2026-11)
Design: viscoFoundry `docs/superpowers/specs/2026-11-04-component-model-design.md` (the Suite-side note is `docs/design/2026-11-04-component-delivery-design.md`).
- **A component is a Maven artifact** in this project's package registry: `com.visco:viscolink:<v>` (war + `classes` jar), `com.visco:viscolink-pack-health:<v>:zip:overlay`, `com.visco:viscostore:<v>:war`. Published by CI only (`scripts/ci/publish.sh`, `deploy:deploy-file` of the exact staged bytes, after the ITs). Never `mvn deploy` by hand.
- **Version**: `scripts/ci/version.sh`. A tag `vA.B.C` publishes `A.B.C` and MUST equal the poms' `A.B.C-SNAPSHOT` base; a main commit publishes `A.B.C-<pipeline id>`. CI runs `versions:set -DprocessAllModules` (the modules have no common parent). After a release, bump every pom to the next `-SNAPSHOT` (`./mvnw org.codehaus.mojo:versions-maven-plugin:2.18.0:set -DnewVersion=A.B+1.0-SNAPSHOT -DprocessAllModules=true -DgenerateBackupPoms=false`) in its own MR.
- **The release BOM** `com.visco:viscosuite-release:<v>` (one JSON file, `viscorunner/release/viscosuite-release.template.json` filled by `scripts/ci/write-release-bom.sh`): the sha256 the registry reports per package file, the `viscobase` manifest digest, the `postgres:16` digest at release time. viscoForge's release names a Suite release; the portal renders instances from a release. `base.servlets` lists the runtime's own `servlet.<name>.*` names (`pack` included: `PackServlet` is in the core WAR).
- **The fetcher** (`viscorunner/src/scripts/fetch-components`): list line `<id> <kind> <version> <url> <sha256> <deployAs>` after `release <version>`; `war` → `webapps/<deployAs>.war`, `overlay` → unzip into `overlay/<target>/` (`deployAs: overlay/<target>`), `container` → recorded only. Token from `COMPONENT_REGISTRY_TOKEN` (Deploy-Token) or `CI_JOB_TOKEN` (Job-Token) through a 0600 curl config, never printed. Fails closed. Same script at image build, in the platform's init container and in the casting bake.
- **Registry settings** (set at M1 rollout): Maven duplicates OFF (a version is immutable), a protected-package rule for `com/visco/*` (Maintainer pushes); anonymous pull of a public project's packages is what `release:bom` proves every pipeline (the Standard edition fetches without a token).
- **Cleanup**: `scripts/ci/cleanup-packages.sh` keeps releases forever and the newest 10 main-commit versions per package.
```

- [ ] **Step 3: README.md**

Line 78 (`docker-compose.core.yml` row) → `    └── docker-compose.core.yml       the market-neutral core = the viscobase stage, no pack, no store`. The `/runtime/api-service/pack` row → `| \`GET /runtime/api-service/pack\` | Pack descriptor: which pack the image runs (\`health\` or \`core\`), its subject identifier, the release and the components the runtime was composed with; bearer-token callers only |`. In the build section (after "After the build, `docker compose up --build` …") add:

```markdown
Every image is `viscobase` (Tomcat, the Frank!Framework webapp, a component fetcher) plus components:
the published `viscorunner` and `viscolink` images fetch the store WAR and the health pack overlay
from this project's Maven package registry at build time, pinned by sha256; a local `docker compose
build` fetches the same files from `viscorunner/target/components/`. Each tag `vA.B.C` publishes
the components as Maven artifacts and a release BOM (`com.visco:viscosuite-release:A.B.C`) that
pins all of them; the same BOM is what a hosted platform composes an instance from.
```

- [ ] **Step 4: viscorunner/README.md**

Replace "## Images and compose files" through the `docker compose … # the core` code block with:

```markdown
## Images and compose files

One `Dockerfile`, two stages:

| Stage | Content | Published as |
|---|---|---|
| `viscobase` | Tomcat 11 / JRE 21, the Frank!Framework webapp at `/runtime`, the drivers, the Lemma native, both demo sets, the component fetcher. No pack, no store. | `viscobase`, and `viscolink:…-core` (the core is the absence of a pack) |
| `fat` | `viscobase` plus the components a list names, fetched and placed at build time | `viscorunner` (store + health pack), `viscolink` (health pack) |

A **components list** has one header `release <version>` and one line per component, `<id> <kind> <version> <url> <sha256> <deployAs>`. The Maven build writes two local lists (`target/components-suite.list`, `target/components-viscolink.list`) with `file://` URLs into `target/components/`; CI writes the same lists with registry URLs. `/opt/frank/bin/fetch-components` verifies every byte against the sha256 before it keeps anything and writes `/opt/frank/components/MANIFEST`; `place-components` copies WARs into `webapps/` and overlays into `/opt/frank/webapp-overlay/<context>/` (the `runtime` overlay keeps the dir name `viscolink` for now).

| Compose file | Stage | List | Stack |
|---|---|---|---|
| `docker-compose.yml` | `fat` | `components-suite.list` | The suite: ViscoLink + ViscoStore + PostgreSQL (the `viscorunner` image) |
| `docker-compose.viscolink.yml` | `fat` | `components-viscolink.list` | ViscoLink with the health pack, no store (the `viscolink` image) |
| `docker-compose.core.yml` | `viscobase` | — | The core: no pack, no store, no FHIR (`viscolink:latest-core`) |

The other compose files are overlays or single-purpose stacks: `docker-compose.demo.yml` (Mode 1, layered on the suite), `docker-compose.debug.yml` and `docker-compose.viscolink.debug.yml` (JPDA on `5005`), `docker-compose.git.yml` (git-sourced configurations, layered on the demo) and `docker-compose.viscostore.yml` (the standalone ViscoStore image). All three stacks above mount `secrets/credentials.properties` (see Mode 2).

```bash
docker compose up --build                                  # the suite
docker compose -f docker-compose.viscolink.yml up --build  # health pack, no store
docker compose -f docker-compose.core.yml up --build       # the core
```
```

Keep the paragraphs that follow ("The core's demo …", "**Databases.**", "**Gotcha.**"); in the core paragraph replace `(the image also carries a copy in /opt/frank/demo-configurations)` with `(the image carries a copy in /opt/frank/demo-configurations/core)`. In "Directory layout" (line ~159 on) replace the `/opt/frank/demo-configurations/` entry's description with "both packs' demo sets, `health/` and `core/`".

- [ ] **Step 5: The design note and its row**

`docs/design/2026-11-04-component-delivery-design.md`:

```markdown
# Component delivery: artifacts in the registry, one bare image, a fetcher

**Status:** built in M1 of the component model (`feat/component-model-m1`, 2026-11); the release BOM is
`viscorunner/release/viscosuite-release.template.json`, the fetcher `viscorunner/src/scripts/fetch-components`,
the image stages `viscobase` and `fat` in `viscorunner/Dockerfile`. The full design, across the three
repositories, is viscoFoundry's `docs/superpowers/specs/2026-11-04-component-model-design.md`.

## Problem

Every optional part of the platform (the store, a pack, the commercial console) was baked into image
variants: one Dockerfile × `PACK` × `STORE` here, two more Dockerfiles downstream, 18 image builds per
commit across the two repositories, and a portal that chose images by tag suffix. Each new optional part
multiplied all of that. Pack identity was defined twice (Java and `pack.properties`), the Tomcat context
three times, the brand six times.

## Decision

A **component** is a versioned Maven artifact (a WAR, an overlay zip) or a container image. A **release
BOM** pins the exact set a release was tested with. The runtime is one bare image, `viscobase`, that a
**fetcher** composes with components: at build time for the open-source fat images, at pod start for the
commercial runner, in the tenant's CI for a casting. Non-Java components (Postgres, later an API gateway)
are containers next to the one Tomcat; Java components share it.

## Rejected

- *Keep baking, generate the matrix.* Writes the variants once but still builds and ships every
  combination; a fourth axis still doubles the builds.
- *A second Tomcat per Java component.* Clean isolation, but memory a single-tenant instance pays for nothing.
- *Components declaring their Kubernetes needs, a generic renderer.* A rewrite of two renderers and every
  golden for a first consumer whose needs are least like the others; the BOM therefore declares only what
  the deployer cannot know (`servlets`, `properties`), the deployer keeps one render block per component id.
- *Independent per-component versions.* A compatibility matrix nobody tests; a release BOM tests one set.
```

In `docs/design/README.md` add the row:

```markdown
| [Component delivery](2026-11-04-component-delivery-design.md) | Components as Maven artifacts pinned by a release BOM, one bare `viscobase` image and a fetcher that composes it at build or at start; the Suite-side of the platform's component model |
```

- [ ] **Step 6: Run everything once more, commit**

Run: `./mvnw -q install -pl viscolink,packs/health,viscostore && ./mvnw -q verify -pl viscorunner` → PASS.

```bash
git add CHANGELOG.md CLAUDE.md README.md viscorunner/README.md docs/design/README.md docs/design/2026-11-04-component-delivery-design.md
git commit -m "docs: the component model M1 — releases, viscobase, the fetcher"
```

---

### Task 12: Rollout: registry settings, merge, the first pipeline, v1.0.0, the bump

**Files:** none in this repository beyond `CHANGELOG.md` (the release heading) and the post-release pom bump; viscoFoundry `CLAUDE.md` ("Current status") and the spec's status line.

- [ ] **Step 1: Registry settings (before the merge; Tom's session or `glab api` with his token)**

```bash
export GITLAB_HOST=git.viscosiety.com
glab api -X PUT projects/2 -f maven_duplicates_allowed=false
glab api projects/2 | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["maven_duplicates_allowed"])'   # False
glab api -X POST projects/2/packages/protection/rules -f package_name_pattern='com/visco/*' -f package_type=maven -f minimum_access_level_for_push=maintainer
```

If the last call answers 404, protected packages are not on this CE build: record that in CLAUDE.md's registry bullet and rely on duplicates-off plus the sha pins. Check once that the main branch's pipelines run as a Maintainer (Tom): a protected rule refuses a job token acting for a Developer.

- [ ] **Step 2: Merge, watch the first main pipeline**

After the MR is merged (Tom): `glab ci status --branch main --live` (or `glab ci view`). Expected green jobs in order: test → package → integration-test → publish → the eight docker jobs (each log ends with `smoke: … OK`) → four manifests → release:bom (log: `write-release-bom: uploaded …`, `anonymous pull OK`) → trigger:viscoforge (Forge's pipeline must receive `UPSTREAM_RELEASE`; check its variables) → cleanup. Then:

```bash
glab api "projects/2/packages?per_page=20" | python3 -I -c 'import json,sys; [print(p["name"], p["version"]) for p in json.load(sys.stdin)]'
```

Expected: `com/visco/viscolink`, `com/visco/viscolink-pack-health`, `com/visco/viscostore`, `com/visco/viscosuite-release` at `1.0.0-<pipeline>`.

If `manifest:viscobase` fails on the cross-repository `-core` tags, apply the fallback written in the job's comment and re-run.

- [ ] **Step 3: Live proof (local, on the published images)**

```bash
docker pull registry.git.viscosiety.com/public-applications/viscosuite/viscorunner:1.0.0-<pipeline>
sh scripts/ci/smoke-image.sh registry.git.viscosiety.com/public-applications/viscosuite/viscorunner:1.0.0-<pipeline> store pack-health
docker run -d --rm --name m1 -p 8180:8080 <the same env as the smoke's runtime part> registry.git.viscosiety.com/public-applications/viscosuite/viscolink:1.0.0-<pipeline>
curl -s http://localhost:8180/components.json      # {"release":"1.0.0-<pipeline>","components":[{"id":"pack-health",...}]}
curl -s http://localhost:8180/ | grep -c 'data-pack-tagline'   # 1: the page carries the slot the script fills
docker buildx imagetools inspect registry.git.viscosiety.com/public-applications/viscosuite/viscolink:latest-core | head -3
docker buildx imagetools inspect registry.git.viscosiety.com/public-applications/viscosuite/viscobase:latest | head -3   # same digest
```

Also: viscoForge's triggered pipeline on `main` must build and its Dockerfiles must still find `/opt/frank/webapp-overlay/viscolink` and `rewrite.config` (the overlay dir and the rules are unchanged on purpose). Tom restarts one live ViscoLink test instance on the new Forge image; check its `/runtime/iaf/api/server/health` 200 and the admin instance page's Observed build (the descriptor now carries `release`/`components`; the portal ignores both until M3).

- [ ] **Step 4: Tag v1.0.0**

On `main`, one MR: `CHANGELOG.md` `## [Unreleased]` → `## [1.0.0] — <date>` (keep an empty `## [Unreleased]` above it). After its merge:

```bash
git fetch origin main && git tag -a v1.0.0 -m "ViscoSuite 1.0.0" origin/main
git -c credential.helper='!glab auth git-credential' push https://git.viscosiety.com/public-applications/viscosuite.git v1.0.0
```

The tag pipeline publishes `1.0.0` (packages, images `:1.0.0`, `viscosuite-release:1.0.0`), creates the GitHub Release, triggers Forge with `UPSTREAM_RELEASE=1.0.0`.

- [ ] **Step 5: Bump**

MR on main: `./mvnw org.codehaus.mojo:versions-maven-plugin:2.18.0:set -DnewVersion=1.1.0-SNAPSHOT -DprocessAllModules=true -DgenerateBackupPoms=false`, commit `chore: start 1.1.0-SNAPSHOT`. The next main commit publishes `1.1.0-<pipeline>`.

- [ ] **Step 6: Record**

viscoFoundry `CLAUDE.md` "Current status": an entry "Component model M1 — viscoSuite v1.0.0 (date)" with the facts a later session needs (the package names, the version scheme, the fetcher contract, the `-core` alias, the deviations below, the first live proof). The spec's status line: "M1 built (viscoSuite v1.0.0, date)". Both in a viscoFoundry MR.

---

## Deviations from the spec (decided while planning; recorded in the spec's as-built note)

1. **The `runtime` overlay dir stays `/opt/frank/webapp-overlay/viscolink` in M1** (spec §6 said `webapp-overlay/runtime`): viscoForge's Dockerfiles COPY into it and the portal mounts the brand there until M2/M3; M4 renames it with those callers. `place-components` maps `overlay/runtime` to it.
2. **No `META-INF/context.xml` in `viscostore.war`; the global `conf/context.xml` declares all three datasources** (spec §6 said the store WAR carries today's `context-viscostore.xml`): the store reads its database from Spring env, not JNDI; `jdbc/viscostore` in JNDI serves the *runtime* (the portal's Data tab browses the store's database through it), and the Frank!Framework never enumerates JNDI, so the extra resource is invisible on a runtime without a store.
3. **`viscobase` ships no `resources.yml`** (spec §6 listed a base one): today's Suite images ship none; viscoForge's copy stays its own concern until M2 and the portal renders the whole file since 2026-10-09.
4. **`servlet.pack.*` belongs to `base.servlets`**, not to `pack-health` (spec §4's example put `"servlets": ["pack"]` on the pack): `PackServlet` lives in the core WAR. The health pack declares no servlet.
5. **The fat images are built from the same Dockerfile in the same job (`--target fat`)**, not `FROM` the pushed `viscobase` manifest: the base layers are equal in content, not necessarily in digest. The BOM pins the pushed `viscobase` digest, which is what the commercial runner uses.
6. **The `-health` tag aliases stay** until M4 (spec §9 M4 drops unused aliases then).
7. **Both packs' demo sets ship in `viscobase`** under `/opt/frank/demo-configurations/{health,core}/` (the spec did not place them).
8. **The components list carries six fields** (`version` added; spec §5.3 listed five) so `MANIFEST` and the descriptor can name versions without a second source.
9. **The `BomIT` of spec §11 is the `release:bom` job plus `ReleaseTemplateTest`**: the sha256 comparison needs the registry, so it runs in the pipeline (`write-release-bom.sh` reads the registry's sha256 and proves anonymous pull), while the template's shape and the `properties[]` ↔ `DeploymentSpecifics.properties` tie are unit-tested.
10. **Main commits publish to the registry** (every one; spec §5.1 implied it): `cleanup:packages` keeps releases and the newest 10 others.

## Self-review (done while writing)

- Spec §5.1 steps 1-5 → Tasks 2, 5, 6, 8, 10; §5.3 fetcher → Task 4; §6 → Tasks 3, 5; §8 → Tasks 4, 8, 10, 12; §9 M1 list → Tasks 1-12 (`packs/core` module dropped per the spec's own correction); §11 viscoSuite tests → Tasks 5, 6, 8, 9 (`PackOverlayLayoutIT` and `HealthClasspathIT` stay "stage, then check" because the launcher ITs need the staging; the fetch path is covered by `theStagedListFeedsTheFetcher`, the script test and the CI smoke).
- Names used across tasks: `fetch-components` / `place-components` (Tasks 4, 5, 9, 10), `VF_COMPONENTS_DIR` / `VF_WEBAPPS_DIR` / `VF_OVERLAY_DIR` (4, 6), `components-suite.list` / `components-viscolink.list` (6, 7, 10), `components-registry-suite.list` / `components-registry-viscolink.list` (2, 10), `viscorunner/target/publish/` (2, 10), `ComponentManifest.PATH_PROPERTY` (3), `scripts/ci/{version,stage-publish,publish,write-release-bom,smoke-image,cleanup-packages}.sh` (1, 2, 8, 9, 10), the BOM placeholders `${VERSION} ${BASE_IMAGE} ${BASE_DIGEST} ${SHA_STORE} ${SHA_PACK_HEALTH} ${POSTGRES_DIGEST}` (8).
- Known risks the first pipeline settles (each with its fallback in the task): `imagetools create` across repositories (Task 10 comment), H2 in-memory for the smoke (Task 9 step 3), protected packages on CE (Task 12 step 1), dotenv `VERSION` reaching the trigger job (Task 10; if not, pass `UPSTREAM_RELEASE` by writing it into `version.env` as a pipeline variable through `artifacts:reports:dotenv` on a job the trigger `needs` — which is what is written — or compute it in viscoForge from `UPSTREAM_TAG` as a last resort).
