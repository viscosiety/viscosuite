# Vertical Packs — M2: the module split and the image matrix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The healthcare code leaves the core WAR: a new Maven module `packs/health` (jar `viscolink-pack-health`) carries `com.viscosiety.fhir`, `com.viscosiety.mllp`, the four pipes, their Spring files, `HealthPack`, the FHIR property defaults, the console script and the Ladybug `patientid` column; `viscorunner` builds every image from ONE Dockerfile with `PACK` and `STORE` build arguments and CI publishes `viscorunner:<sha>`/`:latest` and `viscolink:<sha>`/`:latest` meaning exactly what they mean today, plus `-health` and `-core` tags. The health images behave byte for byte as today (D8); `viscolink:<sha>-core` boots with no FHIR servlets and `/pack` answers `core`.

**Architecture:** The pack is an ordinary jar on the `/viscolink` webapp class path, laid into `/opt/frank/webapp-overlay/viscolink/WEB-INF/lib/` (the existing `PreResources` set) together with the runtime jars the WAR lacks. The pack ships as a Maven `zip` artifact with classifier `overlay` whose tree IS the overlay (`WEB-INF/lib/*.jar`, `WEB-INF/classes/console/index.html`, `WEB-INF/pack.properties`); `viscorunner` unpacks it into `target/packs/health/`, stages `target/packs/core/` (empty libs, its own `pack.properties`), `target/demo/<pack>/` and `target/store/{viscostore,none}/`, and the Dockerfile COPYs `target/packs/${PACK}/`, `target/demo/${PACK}/`, `target/store/${STORE}/` and `conf/context-${STORE}.xml`. The pack registers itself through two `META-INF/services` files (F!F `Module` and `PackDescriptor`); nothing in the core names the pack. A golden list of today's WAR libraries proves the class-path split: WAR libs ∪ pack libs = today's libs, WAR libs ∩ pack libs = ∅.

**Tech Stack:** Java 21, Maven (reactor + `maven-assembly-plugin`, `maven-dependency-plugin`, `maven-antrun-plugin`, `maven-resources-plugin`), Frank!Framework (BOM pinned in `viscolink/pom.xml`), Tomcat 11 `PreResources`, Liquibase changelogs (Ladybug), Docker (plain `docker build` on dind), GitLab CI, docker compose, JUnit 5 + failsafe ITs (embedded Tomcat launcher).

**Spec:** `docs/design/2026-10-07-vertical-packs-design.md` §3 (D1–D3, D6–D8), §4.1, §4.5, §4.6, §9 (M2), §10 (M1 spike result).

**Branch:** `feat/vertical-packs-m2` from `main` (43a1b27). One MR against `main`.

## Decisions this plan takes inside the approved design

The design leaves these open or describes them loosely; M2 settles them. Task 8 writes every one of them back into the design.

1. **The pack is published as an overlay zip** (`com.visco:viscolink-pack-health:zip:overlay`), not as a bare jar plus a staging rule in `viscorunner`. The zip's tree is exactly what lands under `/opt/frank/webapp-overlay/viscolink/`: `WEB-INF/lib/` (the pack jar + its runtime dependencies), `WEB-INF/classes/console/index.html` (the Frank!Console page with the pack's `<script>` tag), `WEB-INF/pack.properties` (id, display name, version, landing-page texts). `viscorunner` only unpacks it.
2. **Console script injection is build-time, not a runtime loader.** The design's §10 offered (a) a staging patch or (b) a core-owned loader script; this plan takes (a) in the form above: the `index.html` patch moves from `viscolink/pom.xml` to `packs/health/pom.xml`, and the patched page is served from the overlay's `WEB-INF/classes/`, which Tomcat's `WebappClassLoader` searches before every `WEB-INF/lib` jar regardless of resource-set order. The health console therefore loads `fhir-webservices.js` exactly as today (same tag, same timing). The core image serves the Frank!Console's own unpatched page. `consoleScripts` is NOT added to the SPI.
3. **`propertyDefaults()` is removed from `PackDescriptor`.** A pack's defaults are the `DeploymentSpecifics.properties` of its own jar (F!F merges every copy on the class path; the M1 spike proved it). Rule: a pack's file only ADDS keys the core's file does not define (Tomcat's resource-set order decides a clash and is not a contract). `consoleViews()` stays, as the mirror of the pack's `customViews.<name>.*` keys.
4. **`customViews.names` gets the indirection** `customViews.names=viscoLink,${pack.customViews.names:-}` in the core's `DeploymentSpecifics.properties`. The deployers that set `customViews.names` themselves (`viscorunner/docker-compose.yml`, viscoFoundry's manifest renderer and casting bundle, viscoForge's composes) keep doing so — the ViscoStore view is the deployer's (it depends on `STORE`, not on the pack). `HealthPack.consoleViews()` stays empty. A later pack with real views is M5's problem and is written down as such.
5. **A pack owns the Ladybug column of its `metadataName`; the core owns `subjectid`.** The core's `ladybug/DatabaseChangelog_Custom.xml` drops changeSet `LadybugCustom:2` (`patientid`), adds `LadybugCustom:5` (`subjectid`, guarded by a `columnExists` precondition) and `<include file="ladybug/DatabaseChangelog_Pack.xml" errorIfMissing="false"/>`. The health pack ships `ladybug/DatabaseChangelog_Pack.xml` with the `patientid` column under a new id, guarded by the same kind of precondition, so an existing health database (where `LadybugCustom:2` already ran under the old file name) is left alone and a fresh one gets the column. Without this the core image cannot store a Ladybug report (its `metadataNames` carry `subjectId`, see `springIbisTestToolVisco.xml`).
6. **The pack compiles against `viscolink:classes`.** `viscolink/pom.xml` sets `attachClasses=true` on the war plugin; the pack depends on `com.visco:viscolink:1.0.0-SNAPSHOT:classes` with scope `provided`. The only core types a pack may use are the SPI (`com.viscosiety.pack.PackDescriptor`, `SubjectIdentifier`, `ConsoleView`); `HealthPack` moves to package `com.viscosiety.pack.health` so no pack class sits in `com.viscosiety.pack` (that package has package-private test seams).
7. **The core's tests get a test-tree mirror of the health values** (`HealthValuesPack`, under `viscolink/src/test/java/com/viscosiety/pack/`). The core cannot test-depend on the pack (reactor cycle: pack → `viscolink:classes`), so the D8 pins that today use `new HealthPack()` use the mirror, and the pack's own `HealthPackTest` pins that the real `HealthPack` carries the same values. Discovery-by-services-file is tested in the pack module (where the services file is on the test class path); in the core's tests `PackRegistry.get()` now resolves `CorePack`.
8. **One Dockerfile = today's suite `Dockerfile`.** The standalone `viscolink` image therefore GAINS `conf/server.xml` (the `ContextFailureEventPublisher` listener) and the shaded runner jar on `/opt/frank/lib` — both no-ops outside Kubernetes and wanted inside it. The Tomcat context file is `conf/context-${STORE}.xml` (`context-viscostore.xml` = today's `context-viscosuite.xml`; `context-none.xml` = today's `context-viscolink.xml`), so a `STORE=none` image carries no `jdbc/viscostore` resource, as today. `Dockerfile.viscolink` is deleted; `Dockerfile.viscostore` stays.
9. **Tags.** CI publishes no version tags today, only `:<sha>` and `:latest`; the design's `<v>` means those. Matrix: `viscorunner:{<sha>,latest,<sha>-health,latest-health}` (PACK=health STORE=viscostore), `viscolink:{<sha>,latest,<sha>-health,latest-health}` (health, none), `viscolink:{<sha>-core,latest-core}` (core, none). The per-arch intermediates get the same suffixes (`<sha>-core-amd64`) and the cleanup job deletes them. viscoForge keeps building `FROM viscorunner:<sha>` / `viscolink:<sha>` unchanged.
10. **The ROOT landing page is stamped from `pack.properties` at image build** (`pack.id`, `pack.displayName`, `pack.version`, `pack.tagline`, `pack.linkBlurb`): the health image renders today's texts, the core image neutral ones. `BuildInfo` is NOT changed (closes the last §10 question: the ROOT stamp plus `/pack` are enough; castings keep baking `BuildInfo` as they do).
11. **`util/hl7util`, `util/fhirutil` and `util/demo` move under `packs/health/util/`.** `hl7util` stays a standalone CLI module (artifactId `hl7util`, no consumer, not staged into any image); it is health content, nothing more.
12. **Demo configurations**: `viscorunner/demo-configurations/` → `packs/health/demo-configurations/`; the core ships one neutral demo, `viscolink/demo-configurations/echo/` (one ApiListener adapter that records `subjectId`). Both are also baked into the images at `/opt/frank/demo-configurations/` (design §4.5); the demo compose keeps bind-mounting the health set over `/opt/frank/configurations`.

## Global Constraints

- **D8 — the health images behave byte for byte as today.** Same Java packages for every moved class (`com.viscosiety.fhir`, `com.viscosiety.mllp`, `com.viscosiety.pipes` — tenant configurations name them), same Spring bean names and files, same property keys and values, same `patientid` Ladybug column, same console page bytes except the file that carries them, same ROOT landing-page texts. The existing health tests move with the code and stay green unchanged except for package/path moves (say so in the report). The golden class-path IT (Task 5) must pass: WAR libs ∪ pack libs = today's WAR libs (the pack jar itself excepted), intersection empty.
- **Nothing in the core names the pack.** No `health`, `fhir`, `mllp`, `hl7`, `patient` literal in `viscolink/src/main` after Task 3 except: the `patientFilter` alias in `FlowController` (M1's one-release alias), the DOM ids/CSS classes in `flow/` (`patient-filter`, `td-patient`, `patient-id`), the word "PHI" in `ShareableReportDescriber`/`StubbedRunner`, and `/tools/health` (the liveness probe). Gate: `grep -rniE "healthpack|com\.viscosiety\.(fhir|mllp|pipes)|springFhir|springMllp|fhir-webservices|hapi" viscolink/src/main` is empty.
- **Nothing in the pack reaches into the core** beyond `com.viscosiety.pack.{PackDescriptor,SubjectIdentifier,ConsoleView}`. Gate: `grep -rnE "import com\.viscosiety\.(security|flow|ladybug|classloaders|components|tools)" packs/health/src` is empty, and no pack class is in package `com.viscosiety.pack` (only `com.viscosiety.pack.health`).
- **One pack or none** (M1): unchanged. The pack's services files are the only registration; the core's `META-INF/services/com.viscosiety.pack.PackDescriptor` file is deleted.
- **No duplicate classes on the webapp class path.** Every artifact the WAR already ships is `provided` in the pack's pom; the golden IT is the oracle.
- **Image names and today's tags keep their meaning** (D6): `viscorunner:<sha>`/`:latest` = health + ViscoStore; `viscolink:<sha>`/`:latest` = health, no store. viscoForge's `FROM` lines and its `UPSTREAM_TAG=$CI_COMMIT_SHA` trigger need no change.
- **Exact names:** module dir `packs/health`, artifact `com.visco:viscolink-pack-health:1.0.0-SNAPSHOT` (jar) + `:zip:overlay`; staging dirs `viscorunner/target/packs/{health,core}/`, `target/demo/{health,core}/`, `target/store/{viscostore,none}/`; image paths `/opt/frank/webapp-overlay/viscolink/` (overlay), `/opt/frank/demo-configurations/` (demo); build args `PACK` ∈ {`health`,`core`} (default `health`), `STORE` ∈ {`viscostore`,`none`} (default `viscostore`); tag suffixes `-health`, `-core`; Ladybug column `subjectid` (core), `patientid` (health); pack package `com.viscosiety.pack.health`.
- TDD per task: write the test, see it fail for the right reason, implement. Build gates: `./mvnw -q test -pl viscolink` and `./mvnw -q test -pl packs/health` green; `./mvnw -q install -pl viscolink,packs/health -DskipTests` after a task that changes resources or poms (the zip and the classes jar must build); Task 5+ also `./mvnw -q install -pl viscostore -DskipTests` once (ITs need the store WAR) and `./mvnw -q verify -pl viscorunner`. Never run `-pl viscostore` tests (unaffected, slow).
- Commit messages end with a `Co-Authored-By` line naming the model that did the work, as Claude Code reports it for that agent. Licence header on every new Java file (copy the one on `AbstractBearerServiceServlet`).
- Subagent constraints: no docker and no network beyond Maven's local repository for Tasks 1–5 and 7–8 (the controller runs Task 6's docker proofs); do not push; do not open merge requests; nothing new becomes public; no secret value anywhere (the demo credentials file stays a bind mount, never baked).

## File structure

| Path | Responsibility |
|---|---|
| `pom.xml` (root) | Reactor: `viscolink`, `packs/health`, `packs/health/util/hl7util`, `viscostore`, `viscorunner` |
| `viscolink/pom.xml` | Core WAR: `attachClasses`; HAPI/HL7 dependencies and the console-index executions removed |
| `viscolink/src/main/resources/{DeploymentSpecifics.properties,ladybug/DatabaseChangelog_Custom.xml}` | Core defaults (+ `customViews.names` indirection); core Ladybug columns (`subjectid`, include of the pack changelog) |
| `viscolink/src/main/java/com/viscosiety/components/ViscoLinkModule.java` | Core Spring files only |
| `viscolink/src/main/java/com/viscosiety/pack/{PackDescriptor,CorePack}.java` | `propertyDefaults()` dropped |
| `viscolink/src/test/java/com/viscosiety/pack/HealthValuesPack.java` | Test mirror of the health values for the core's D8 pins |
| `viscolink/demo-configurations/echo/Configuration.xml` | The core's neutral demo |
| `packs/health/pom.xml`, `packs/health/src/assembly/overlay.xml` | The pack jar, its runtime set, the console-index patch, the overlay zip |
| `packs/health/src/main/java/com/viscosiety/{fhir,mllp,pipes}/**`, `.../pack/health/{HealthPack,HealthPackModule,PackVersion}.java` | The moved health code; the pack's F!F `Module`; its descriptor |
| `packs/health/src/main/resources/{springFhir.xml,springMllp.xml,DeploymentSpecifics.properties,console/fhir-webservices.js,ladybug/DatabaseChangelog_Pack.xml,pack-version.properties,META-INF/services/*}` | The pack's resources and registrations |
| `packs/health/src/main/overlay/WEB-INF/pack.properties` | Landing-page texts and identity for the image build |
| `packs/health/demo-configurations/**`, `packs/health/util/{hl7util,fhirutil,demo}/**` | Moved health content |
| `viscorunner/pom.xml`, `viscorunner/src/packs/core/WEB-INF/pack.properties` | Staging of packs, demos, stores |
| `viscorunner/Dockerfile`, `viscorunner/conf/context-{viscostore,none}.xml`, `viscorunner/src/webapp/ROOT/index.html` | The one image recipe |
| `viscorunner/docker-compose{,.viscolink,.core,.demo}.yml`, `viscorunner/postgres/init-ladybug.sql` | Build args per stack; the `ladybug` database for the store-less stacks |
| `viscorunner/src/test/java/com/viscosiety/viscorunner/it/{ViscolinkLauncher,Hl7v2ToFhirIT,LabEnrichmentIT,HealthClasspathIT,PackOverlayLayoutIT}.java`, `viscorunner/src/test/resources/golden/health-webapp-libs.txt` | ITs on the staged layout; the golden class path |
| `.gitlab-ci.yml`, `build.sh` | Matrix, tags, cleanup, install lists |
| `docs/design/2026-10-07-vertical-packs-design.md`, `CLAUDE.md`, `README.md`, `viscorunner/README.md` | Docs |

---

### Task 1: The golden class path, the pack module skeleton and the overlay zip

**Files:**
- Create: `viscorunner/src/test/resources/golden/health-webapp-libs.txt`
- Create: `packs/health/pom.xml`, `packs/health/src/assembly/overlay.xml`, `packs/health/src/main/overlay/WEB-INF/pack.properties`, `packs/health/src/main/resources/pack-version.properties`, `packs/health/src/main/java/com/viscosiety/pack/health/PackVersion.java`
- Test: `packs/health/src/test/java/com/viscosiety/pack/health/PackVersionTest.java`
- Modify: `pom.xml` (root, modules), `viscolink/pom.xml` (`attachClasses`), `.gitignore` (nothing to add — `target/` is already ignored)
- Move: `util/hl7util` → `packs/health/util/hl7util`, `util/fhirutil` → `packs/health/util/fhirutil`, `util/demo` → `packs/health/util/demo`

**Interfaces:**
- Produces: artifact `com.visco:viscolink-pack-health:1.0.0-SNAPSHOT` (jar) and `:zip:overlay`; `PackVersion.get()` → `"1.0.0-SNAPSHOT"` (from the filtered `pack-version.properties`); `viscolink:classes` artifact.

- [ ] **Step 1: Record today's class path BEFORE touching anything.** On the untouched branch:

```bash
./mvnw -q install -pl viscolink -DskipTests
unzip -Z1 viscolink/target/viscolink-1.0.0-SNAPSHOT.war | grep '^WEB-INF/lib/.*\.jar$' | sed 's#^WEB-INF/lib/##' | sort > viscorunner/src/test/resources/golden/health-webapp-libs.txt
wc -l viscorunner/src/test/resources/golden/health-webapp-libs.txt   # expect a few hundred lines; viscolink-1.0.0-SNAPSHOT.jar must be among them
git add viscorunner/src/test/resources/golden/health-webapp-libs.txt
git commit -m "test(pack): golden list of today's viscolink webapp libraries"
```

This file is FROZEN for the rest of the branch (same rule as the viscoFoundry goldens): a later task may only edit it by hand, one line at a time, saying why in its report.

- [ ] **Step 2: Move the util directories** (pure `git mv`, no content change):

```bash
mkdir -p packs/health/util
git mv util/hl7util packs/health/util/hl7util
git mv util/fhirutil packs/health/util/fhirutil
git mv util/demo packs/health/util/demo
rmdir util
```

`packs/health/util/hl7util/scripts/_common.sh` finds the jar via `${SCRIPT_DIR}/../target/hl7util.jar` — still right. Its error text names `mvn package -pl util/hl7util`: change it to `-pl packs/health/util/hl7util`.

- [ ] **Step 3: Reactor.** In the root `pom.xml` replace the `<modules>` block with:

```xml
	<modules>
		<module>viscolink</module>
		<module>packs/health</module>
		<module>packs/health/util/hl7util</module>
		<module>viscostore</module>
		<module>viscorunner</module>
	</modules>
```

- [ ] **Step 4: `attachClasses` on the core WAR.** In `viscolink/pom.xml`, inside the `maven-war-plugin` `<configuration>`, directly after `<archiveClasses>true</archiveClasses>`, add:

```xml
					<!-- Also install the classes as viscolink-<v>-classes.jar: packs compile against the
					     pack SPI (com.viscosiety.pack) with scope provided. The WAR provides them at runtime. -->
					<attachClasses>true</attachClasses>
```

- [ ] **Step 5: Write the failing test** `packs/health/src/test/java/com/viscosiety/pack/health/PackVersionTest.java`:

```java
package com.viscosiety.pack.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PackVersionTest {

    @Test
    void versionIsTheMavenProjectVersion() {
        // pack-version.properties is filtered at build time; a module that forgets the filter
        // would ship the literal placeholder, which this catches.
        assertEquals("1.0.0-SNAPSHOT", PackVersion.get());
    }
}
```

- [ ] **Step 6: The pack pom** `packs/health/pom.xml` (no parent, like the other modules):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
		xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
		xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>

	<groupId>com.visco</groupId>
	<artifactId>viscolink-pack-health</artifactId>
	<version>1.0.0-SNAPSHOT</version>
	<packaging>jar</packaging>

	<name>ViscoLink healthcare pack</name>
	<description>FHIR facades, MLLP, HL7v2 pipes, the patient subject identifier — laid over the ViscoLink core WAR as a webapp overlay.</description>

	<properties>
		<!-- Keep these three in lock-step with viscolink/pom.xml: the pack's provided jars must be
		     the exact versions the WAR ships, or the class path is not the one D8 promises. -->
		<frankframework.version>10.3.0-20260924.042323</frankframework.version>
		<hapi.version>8.8.1</hapi.version>
		<junit.version>5.14.1</junit.version>
		<maven.compiler.source>21</maven.compiler.source>
		<maven.compiler.target>21</maven.compiler.target>
		<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
	</properties>

	<dependencyManagement>
		<!-- Copy the whole <dependencyManagement> block of viscolink/pom.xml here verbatim
		     (the frankframework-parent BOM import, the JUnit 5 pins, the ladybug pins). -->
	</dependencyManagement>

	<dependencies>
		<!-- The pack SPI: compile against the core's classes, never ship them. -->
		<dependency>
			<groupId>com.visco</groupId>
			<artifactId>viscolink</artifactId>
			<version>${project.version}</version>
			<classifier>classes</classifier>
			<scope>provided</scope>
		</dependency>
		<!-- Frank!Framework: provided by the WAR. Add further frankframework-* artifacts here with
		     scope provided until the moved code compiles (Task 2); never with scope compile. -->
		<dependency>
			<groupId>org.frankframework</groupId>
			<artifactId>frankframework-core</artifactId>
			<version>${frankframework.version}</version>
			<scope>provided</scope>
		</dependency>
		<dependency>
			<groupId>jakarta.servlet</groupId>
			<artifactId>jakarta.servlet-api</artifactId>
			<version>6.1.0</version>
			<scope>provided</scope>
		</dependency>

		<!-- HAPI FHIR and HAPI HL7v2: moved verbatim from viscolink/pom.xml in Task 2 (compile scope,
		     these are the jars the WAR will lack). -->

		<!-- Test -->
		<dependency>
			<groupId>org.junit.jupiter</groupId>
			<artifactId>junit-jupiter</artifactId>
			<version>${junit.version}</version>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.mockito</groupId>
			<artifactId>mockito-junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework</groupId>
			<artifactId>spring-test</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>

	<build>
		<resources>
			<resource>
				<directory>src/main/resources</directory>
				<filtering>false</filtering>
			</resource>
			<resource>
				<directory>src/main/resources</directory>
				<filtering>true</filtering>
				<includes><include>pack-version.properties</include></includes>
			</resource>
		</resources>
		<plugins>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-compiler-plugin</artifactId>
				<version>3.14.0</version>
			</plugin>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-jar-plugin</artifactId>
				<configuration>
					<archive>
						<manifest>
							<!-- Implementation-Title/-Version: F!F's ModuleInformation reads them (HealthPackModule). -->
							<addDefaultImplementationEntries>true</addDefaultImplementationEntries>
						</manifest>
					</archive>
				</configuration>
			</plugin>
			<!-- Redirect F!F's log directory during tests (same reason as viscolink/pom.xml). -->
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-surefire-plugin</artifactId>
				<configuration>
					<systemPropertyVariables>
						<log.dir>${project.build.directory}/logs</log.dir>
					</systemPropertyVariables>
				</configuration>
			</plugin>
			<!-- The overlay zip: WEB-INF/lib = this jar + runtime dependencies; WEB-INF/classes and
			     WEB-INF/pack.properties come from the assembly descriptor. -->
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-assembly-plugin</artifactId>
				<version>3.7.1</version>
				<executions>
					<execution>
						<id>overlay</id>
						<phase>package</phase>
						<goals><goal>single</goal></goals>
						<configuration>
							<descriptors><descriptor>src/assembly/overlay.xml</descriptor></descriptors>
							<appendAssemblyId>true</appendAssemblyId>
						</configuration>
					</execution>
				</executions>
			</plugin>
		</plugins>
	</build>
	<repositories>
		<!-- Copy the <repositories> block of viscolink/pom.xml verbatim (the Frank!Framework Nexus). -->
	</repositories>
</project>
```

Where the comment says "copy verbatim", copy from `viscolink/pom.xml` — the BOM import is what makes `mockito-junit-jupiter`/`spring-test` versions resolve.

- [ ] **Step 7: The assembly descriptor** `packs/health/src/assembly/overlay.xml`:

```xml
<assembly xmlns="http://maven.apache.org/ASSEMBLY/2.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.2.0 http://maven.apache.org/xsd/assembly-2.2.0.xsd">
	<!-- The tree of this zip IS the webapp overlay (/opt/frank/webapp-overlay/viscolink/). -->
	<id>overlay</id>
	<formats><format>zip</format></formats>
	<includeBaseDirectory>false</includeBaseDirectory>
	<dependencySets>
		<dependencySet>
			<outputDirectory>WEB-INF/lib</outputDirectory>
			<useProjectArtifact>true</useProjectArtifact>
			<!-- runtime = compile + runtime transitives, minus everything marked provided:
			     exactly the jars the core WAR lacks. -->
			<scope>runtime</scope>
			<useTransitiveFiltering>false</useTransitiveFiltering>
		</dependencySet>
	</dependencySets>
	<fileSets>
		<fileSet>
			<directory>${project.build.directory}/console-patched</directory>
			<outputDirectory>WEB-INF/classes</outputDirectory>
			<!-- Task 3 fills this directory; until then the fileSet is empty and that is fine. -->
		</fileSet>
		<fileSet>
			<directory>src/main/overlay</directory>
			<outputDirectory>/</outputDirectory>
			<filtered>true</filtered>
		</fileSet>
	</fileSets>
</assembly>
```

- [ ] **Step 8: Version and identity resources.** `packs/health/src/main/resources/pack-version.properties`:

```properties
version=${project.version}
```

`packs/health/src/main/overlay/WEB-INF/pack.properties` (the landing-page texts are today's `viscorunner/src/webapp/ROOT/index.html` lines 140 and 151–152, verbatim):

```properties
pack.id=health
pack.displayName=Healthcare
pack.version=${project.version}
pack.tagline=Healthcare integration platform
pack.linkBlurb=Ingests HL7v2, FHIR, and custom source data. Converts, validates, and routes messages to the CDR.
```

- [ ] **Step 9: `PackVersion`** `packs/health/src/main/java/com/viscosiety/pack/health/PackVersion.java`:

```java
package com.viscosiety.pack.health;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The pack's own version, from the build-filtered {@code pack-version.properties}. */
public final class PackVersion {

    private static final String RESOURCE = "/pack-version.properties";

    private PackVersion() {
    }

    public static String get() {
        try (InputStream in = PackVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " missing from the pack jar");
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "").trim();
            if (v.isEmpty() || v.startsWith("${")) {
                throw new IllegalStateException(RESOURCE + " was not filtered at build time: " + v);
            }
            return v;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }
}
```

- [ ] **Step 10: Run, verify, commit.**

```bash
./mvnw -q install -pl viscolink -DskipTests               # produces viscolink-1.0.0-SNAPSHOT-classes.jar in ~/.m2
./mvnw -q test -pl packs/health                           # RED before Step 9 (PackVersion missing), GREEN after
./mvnw -q install -pl packs/health -DskipTests
unzip -Z1 packs/health/target/viscolink-pack-health-1.0.0-SNAPSHOT-overlay.zip
```

Expected zip entries: `WEB-INF/lib/viscolink-pack-health-1.0.0-SNAPSHOT.jar`, `WEB-INF/pack.properties` (with `pack.version=1.0.0-SNAPSHOT`, i.e. filtered), nothing else yet. `./mvnw -q test -pl viscolink` still green (nothing moved yet). Commit: `build(pack): the packs/health module, its overlay zip and the core's classes artifact`.

---

### Task 2: Move the health code, register the pack, split the tests

**Files:**
- Move (git mv, package names unchanged): `viscolink/src/main/java/com/viscosiety/{fhir,mllp,pipes}/**` → `packs/health/src/main/java/com/viscosiety/{fhir,mllp,pipes}/**`; `viscolink/src/main/resources/{springFhir.xml,springMllp.xml,META-INF/services/javax.xml.parsers.DocumentBuilderFactory}` → `packs/health/src/main/resources/...`; `viscolink/src/test/java/com/viscosiety/{fhir,pipes}/**` → `packs/health/src/test/java/com/viscosiety/{fhir,pipes}/**`; `viscolink/src/test/java/com/viscosiety/pack/HealthPackTest.java` → `packs/health/src/test/java/com/viscosiety/pack/health/HealthPackTest.java`
- Move + edit: `viscolink/src/main/java/com/viscosiety/pack/HealthPack.java` → `packs/health/src/main/java/com/viscosiety/pack/health/HealthPack.java`
- Create: `packs/health/src/main/java/com/viscosiety/pack/health/HealthPackModule.java`, `packs/health/src/main/resources/META-INF/services/org.frankframework.components.Module`, `packs/health/src/main/resources/META-INF/services/com.viscosiety.pack.PackDescriptor`, `packs/health/src/test/java/com/viscosiety/pack/health/HealthPackDiscoveryTest.java`, `viscolink/src/test/java/com/viscosiety/pack/HealthValuesPack.java`
- Delete: `viscolink/src/main/resources/META-INF/services/com.viscosiety.pack.PackDescriptor`
- Modify: `viscolink/pom.xml` (HAPI/HL7 dependency blocks out), `packs/health/pom.xml` (they come in), `viscolink/src/main/java/com/viscosiety/components/ViscoLinkModule.java`, `viscolink/src/main/java/com/viscosiety/pack/CorePack.java` (import), core tests listed below

**Interfaces:**
- Consumes: `PackVersion.get()` (Task 1).
- Produces: `HealthPackModule` (F!F `Module` listing `springMllp.xml`, `springFhir.xml`), `HealthPack` in `com.viscosiety.pack.health`; `HealthValuesPack` (core test tree) with the health subject values.

- [ ] **Step 1: Write the pack-side failing tests first.**

`packs/health/src/test/java/com/viscosiety/pack/health/HealthPackDiscoveryTest.java`:

```java
package com.viscosiety.pack.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ServiceLoader;

import org.frankframework.components.Module;
import org.junit.jupiter.api.Test;

import com.viscosiety.pack.PackDescriptor;
import com.viscosiety.pack.PackRegistry;

/** The pack registers itself through its two services files; nothing in the core names it. */
class HealthPackDiscoveryTest {

    @Test
    void theRegistryResolvesTheHealthPackFromThePacksServicesFile() {
        assertInstanceOf(HealthPack.class, PackRegistry.get());
        assertEquals("health", PackRegistry.get().id());
    }

    @Test
    void thePackIsAFrankFrameworkModuleThatBringsItsSpringFiles() {
        List<Module> modules = ServiceLoader.load(Module.class).stream().map(ServiceLoader.Provider::get).toList();
        Module pack = modules.stream().filter(HealthPackModule.class::isInstance).findFirst().orElseThrow();
        assertEquals(List.of("springMllp.xml", "springFhir.xml"), pack.getSpringConfigurationFiles());
        assertTrue(pack.getModuleInformation().getVersion().startsWith("1.0.0"), "version from the pack jar");
    }

    @Test
    void thePackJarCarriesItsSpringFiles() {
        for (String file : List.of("springMllp.xml", "springFhir.xml")) {
            assertTrue(HealthPack.class.getResource("/" + file) != null, file + " must be on the pack's class path");
        }
    }
}
```

(`ModuleInformation.getVersion()` — check the F!F API name with `javap` on `frankframework-core`; use whatever accessor returns `Implementation-Version`. Running the test from `target/classes` has no manifest, so `HealthPackModule.getModuleInformation()` must build its manifest by hand from `PackVersion.get()` the way `ViscoLinkModule` does.)

Move `HealthPackTest` to the pack (`git mv`, package `com.viscosiety.pack.health`); change `versionIsTheViscoLinkModuleVersion` to assert `assertEquals(PackVersion.get(), pack.version())`. Delete `propertyDefaultsAreEmptyInM1` only in Task 3 (when the method goes).

Run `./mvnw -q test -pl packs/health` → RED: `HealthPack`, `HealthPackModule` do not exist in the pack.

- [ ] **Step 2: Move the code.**

```bash
mkdir -p packs/health/src/main/java/com/viscosiety packs/health/src/main/resources/META-INF/services packs/health/src/test/java/com/viscosiety
git mv viscolink/src/main/java/com/viscosiety/fhir  packs/health/src/main/java/com/viscosiety/fhir
git mv viscolink/src/main/java/com/viscosiety/mllp  packs/health/src/main/java/com/viscosiety/mllp
git mv viscolink/src/main/java/com/viscosiety/pipes packs/health/src/main/java/com/viscosiety/pipes
git mv viscolink/src/main/resources/springFhir.xml packs/health/src/main/resources/springFhir.xml
git mv viscolink/src/main/resources/springMllp.xml packs/health/src/main/resources/springMllp.xml
git mv viscolink/src/main/resources/META-INF/services/javax.xml.parsers.DocumentBuilderFactory packs/health/src/main/resources/META-INF/services/javax.xml.parsers.DocumentBuilderFactory
git mv viscolink/src/test/java/com/viscosiety/fhir  packs/health/src/test/java/com/viscosiety/fhir
git mv viscolink/src/test/java/com/viscosiety/pipes packs/health/src/test/java/com/viscosiety/pipes
mkdir -p packs/health/src/main/java/com/viscosiety/pack/health
git mv viscolink/src/main/java/com/viscosiety/pack/HealthPack.java packs/health/src/main/java/com/viscosiety/pack/health/HealthPack.java
git rm viscolink/src/main/resources/META-INF/services/com.viscosiety.pack.PackDescriptor
```

The Xerces services file goes with the pack because the HAPI validator is why it exists (`FhirValidatorPipe` Javadoc); the core falls back to the JDK's DOM factory, which is what it used before the FHIR work. Say so in the report.

`FhirValidatorPackageTest` (now under the pack) reads `System.getProperty("fhir.packages.dir", "../viscorunner/fhir-packages")` — the module moved one level deeper, so the default becomes `"../../viscorunner/fhir-packages"`.

- [ ] **Step 3: Dependencies.** Cut the two blocks `<!-- HAPI FHIR — server runtime ... -->` (`viscolink/pom.xml` ~lines 183–229) and `<!-- HAPI HL7v2 — ... -->` (~lines 245–287) out of `viscolink/pom.xml` and paste them verbatim into `packs/health/pom.xml` at the marked place (compile scope). Then make the pack compile: `./mvnw -q compile -pl packs/health`; for every "package … does not exist" that names a Frank!Framework artifact (e.g. `frankframework-console-backend` for a servlet registrar, `frankframework-ladybug-debugger`), add that artifact with `<scope>provided</scope>` and `${frankframework.version}`. Never add a `compile` dependency on anything the WAR ships.

- [ ] **Step 4: `HealthPack` and `HealthPackModule`.** `HealthPack.java`: package `com.viscosiety.pack.health`; imports `com.viscosiety.pack.{ConsoleView,PackDescriptor,SubjectIdentifier}`; `version()` returns `PackVersion.get()`; everything else unchanged (`id`, `displayName`, subject, `/fhir/`, `fhir-patient`/`hl7v2`, empty `consoleViews`). Fix the Javadoc that said the console view block is a script injected into `index.html` "by the pom" → "by this pack's build (Task 3)".

`packs/health/src/main/java/com/viscosiety/pack/health/HealthPackModule.java`:

```java
package com.viscosiety.pack.health;

import java.io.IOException;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.frankframework.components.Module;
import org.frankframework.components.ModuleInformation;
import org.springframework.lang.NonNull;

/**
 * The healthcare pack as a Frank!Framework module: brings {@code springMllp.xml} (the MLLP
 * connection-factory factory, auto-wired into {@code MllpFacade} subclasses) and
 * {@code springFhir.xml} (the FHIR bridge and servlet registrar). Discovered through
 * {@code META-INF/services/org.frankframework.components.Module} like {@code ViscoLinkModule}.
 */
public class HealthPackModule implements Module {

    @Override
    @NonNull
    public ModuleInformation getModuleInformation() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Implementation-Title", "ViscoLink healthcare pack");
        attrs.putValue("Implementation-Version", PackVersion.get());
        attrs.putValue("Implementation-Vendor", "Viscosiety");
        attrs.putValue("groupId", "com.visco");
        attrs.putValue("artifactId", "viscolink-pack-health");
        return new ModuleInformation(manifest);
    }

    @Override
    public List<String> getSpringConfigurationFiles() {
        return List.of("springMllp.xml", "springFhir.xml");
    }
}
```

Services files (one line each): `packs/health/src/main/resources/META-INF/services/org.frankframework.components.Module` → `com.viscosiety.pack.health.HealthPackModule`; `.../META-INF/services/com.viscosiety.pack.PackDescriptor` → `com.viscosiety.pack.health.HealthPack`.

`ViscoLinkModule.getSpringConfigurationFiles()` → `List.of("springStubbedRun.xml", "springConsoleSecurity.xml")`; delete the `{@link com.viscosiety.mllp.MllpFacade}` Javadoc paragraph (the class must not name the pack). `CorePack` keeps its `ViscoLinkModule` import.

- [ ] **Step 5: The core's tests.** Create `viscolink/src/test/java/com/viscosiety/pack/HealthValuesPack.java`:

```java
package com.viscosiety.pack;

import java.util.List;
import java.util.Optional;

/**
 * Test mirror of the health pack's values. The core cannot test-depend on the pack (the pack
 * compiles against the core's classes), so the D8 pins in this module use this mirror and the
 * pack's own HealthPackTest pins that the real HealthPack carries the same values. Keep both
 * in lock-step when one changes.
 */
public final class HealthValuesPack implements PackDescriptor {
    @Override public String id() { return "health"; }
    @Override public String displayName() { return "Healthcare"; }
    @Override public String version() { return "test"; }
    @Override public SubjectIdentifier subject() {
        return new SubjectIdentifier("patientId", "patientId", "PatientId", "Patient", Optional.empty());
    }
    @Override public List<ConsoleView> consoleViews() { return List.of(); }
    @Override public List<String> frankOwnedPaths() { return List.of("/fhir/"); }
    @Override public List<String> deidentificationStrategyIds() { return List.of("fhir-patient", "hl7v2"); }
}
```

(Keep `propertyDefaults()` returning `Map.of()` until Task 3 removes it from the SPI.) Then:
- `FlowControllerPackTest`, `LadybugWiringTest`, `SubjectMetadataFieldExtractorTest`, `PackJsonTest`: replace `new HealthPack()` / the `HealthPack` import with `new HealthValuesPack()`; every literal (`patientId`, `PatientId`, `Patient`, `/fhir/`, `fhir-patient`, `hl7v2`) stays — those are the D8 pins.
- `PackRegistryTest`: `getResolvesTheHealthPackFromTheServicesFile` → `getResolvesTheCorePackWhenNoPackIsOnTheClassPath` asserting `assertInstanceOf(CorePack.class, PackRegistry.get())` and `"core"`; the two `assertInstanceOf(HealthPack.class, ...)` after `reset()` become `CorePack`; the `"/fhir/"` stub paths may stay (they are just strings).
- `ConsoleSecurityRegistrarTest.fhirPathIsFrankOwned` → install `new HealthValuesPack()` via `PackRegistryTestSupport.override` first (the test then proves the registrar honours a pack's path, which is the point); add a sibling `fhirIsNotFrankOwnedOnTheCore` with `CorePack` expecting the console chain.
- `PackServletTest` (lines ~174–175: `id == "health"`, `sessionKey == "patientId"`) → expect `"core"` / `"subjectId"`, or install `HealthValuesPack` explicitly — pick the one that keeps the test's intent (it is "the servlet serves whatever the registry resolved"; the explicit install is clearer).

- [ ] **Step 6: Gates.**

```bash
./mvnw -q install -pl viscolink -DskipTests && ./mvnw -q test -pl viscolink      # green, no HealthPack anywhere
./mvnw -q test -pl packs/health                                                    # green incl. the moved FHIR/pipe tests
./mvnw -q install -pl packs/health -DskipTests
grep -rniE "healthpack|com\.viscosiety\.(fhir|mllp|pipes)|springFhir|springMllp|hapi" viscolink/src/main viscolink/pom.xml   # only the pom's <hapi.version>? NO: remove that property too → empty
grep -rnE "import com\.viscosiety\.(security|flow|ladybug|classloaders|components|tools)" packs/health/src                   # empty
```

`ViscoLinkModuleTest` asserts `springK8sEvents.xml` is absent — extend it with `assertEquals(List.of("springStubbedRun.xml", "springConsoleSecurity.xml"), module.getSpringConfigurationFiles())`.

Commit: `refactor(pack): move the healthcare code into packs/health, registered through its own Module and descriptor`.

---

### Task 3: Properties, the console script, the Ladybug column, and the SPI trim

**Files:**
- Modify: `viscolink/src/main/resources/DeploymentSpecifics.properties`, `viscolink/src/main/resources/ladybug/DatabaseChangelog_Custom.xml`, `viscolink/pom.xml` (remove `unpack-console-index` and `patch-console-index`; keep `stage-oauth2-override`, the `console-patched` directory and the `webResources` entry), `viscolink/src/main/java/com/viscosiety/pack/{PackDescriptor,CorePack}.java`, `viscolink/src/test/java/com/viscosiety/pack/{HealthValuesPack,DistinctSubjectPack,PackJsonTest}.java`
- Move: `viscolink/src/main/resources/console/fhir-webservices.js` → `packs/health/src/main/resources/console/fhir-webservices.js`
- Create: `packs/health/src/main/resources/DeploymentSpecifics.properties`, `packs/health/src/main/resources/ladybug/DatabaseChangelog_Pack.xml`; tests `packs/health/src/test/java/com/viscosiety/pack/health/{ConsoleIndexPatchTest,HealthDefaultsTest,HealthLadybugColumnTest}.java`, `viscolink/src/test/java/com/viscosiety/pack/CoreDefaultsTest.java`, `viscolink/src/test/java/com/viscosiety/ladybug/CoreLadybugChangelogTest.java`
- Modify: `packs/health/pom.xml` (the two executions arrive, patch at `process-resources`), `packs/health/src/test/java/com/viscosiety/pack/health/HealthPackTest.java` (drop `propertyDefaultsAreEmptyInM1`)

- [ ] **Step 1: Failing tests.**

`viscolink/src/test/java/com/viscosiety/pack/CoreDefaultsTest.java` — the core file carries no health key and the indirection:

```java
package com.viscosiety.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class CoreDefaultsTest {

    private static Properties core() throws Exception {
        try (InputStream in = CoreDefaultsTest.class.getResourceAsStream("/DeploymentSpecifics.properties")) {
            Properties p = new Properties();
            p.load(in);
            return p;
        }
    }

    @Test
    void theCoreDefaultsNameNoHealthKey() throws Exception {
        Properties p = core();
        for (String key : p.stringPropertyNames()) {
            assertFalse(key.startsWith("mllp.") || key.startsWith("fhir.") || key.startsWith("viscostore.") || key.startsWith("mr.system"),
                    "health key in the core defaults: " + key);
        }
    }

    @Test
    void customViewsTakeThePacksNamesThroughTheIndirection() throws Exception {
        assertEquals("viscoLink,${pack.customViews.names:-}", core().getProperty("customViews.names"));
    }
}
```

`packs/health/src/test/java/com/viscosiety/pack/health/HealthDefaultsTest.java` — the pack's file carries exactly today's health keys and nothing the core defines:

```java
package com.viscosiety.pack.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

class HealthDefaultsTest {

    private static Properties load(String resource) throws Exception {
        try (InputStream in = HealthDefaultsTest.class.getResourceAsStream(resource)) {
            Properties p = new Properties();
            p.load(in);
            return p;
        }
    }

    @Test
    void thePackDefaultsAreTodaysHealthKeysWithTodaysValues() throws Exception {
        // Find the pack's copy explicitly: the core's classes jar also has a DeploymentSpecifics.properties.
        Properties p = load("/pack-DeploymentSpecifics.properties");
        assertEquals(Set.of("mllp.inbound.sendingApplication", "mllp.inbound.sendingFacility",
                "fhir.target.version", "viscostore.fhir.base.url", "mr.system.base"), p.stringPropertyNames());
        assertEquals("r4", p.getProperty("fhir.target.version"));
    }

    @Test
    void thePackAddsOnlyKeysTheCoreDoesNotDefine() throws Exception {
        Properties pack = load("/pack-DeploymentSpecifics.properties");
        Properties core = load("/DeploymentSpecifics.properties");   // the core's, from viscolink:classes (provided)
        for (String key : pack.stringPropertyNames()) {
            assertTrue(core.getProperty(key) == null, "the core already defines " + key + "; a pack may only add keys");
        }
    }
}
```

Because the pack's test class path holds BOTH files under the same name (the pack's `target/classes` and the core's classes jar), the test reads the pack's copy through a second, test-only name: add to the pack pom's `<resources>` a `testResources` entry? Simpler and robust: the pack's `src/main/resources/DeploymentSpecifics.properties` is the real file, and `src/test/resources/pack-DeploymentSpecifics.properties` is NOT a copy but a symlink-free equivalent produced by the build: add a `maven-resources-plugin` execution (`copy-resources`, phase `process-test-resources`) that copies `src/main/resources/DeploymentSpecifics.properties` to `target/test-classes/pack-DeploymentSpecifics.properties`. State in the report that this is why the test reads a different name.

Copy the exact lines 13–28 of today's `viscolink/src/main/resources/DeploymentSpecifics.properties` (the `mllp.inbound.*`, `fhir.target.version`, `viscostore.fhir.base.url`, `mr.system.base` keys with their comments) into `packs/health/src/main/resources/DeploymentSpecifics.properties` with a header:

```properties
# Healthcare pack defaults. Merged into the Frank!Framework property chain next to the core's
# DeploymentSpecifics.properties (every copy on the class path is loaded). RULE: this file only
# ADDS keys the core does not define — on a clash the resource-set order decides, and that is
# Tomcat's, not a contract. Tenants' StageSpecifics, environment and system properties still win.
```

and delete those lines from the core's file. In the core's file replace `customViews.names=viscoLink` with:

```properties
# A pack appends its own console views through pack.customViews.names (see the design's §10 spike
# result). Deployers that set customViews.names themselves (compose, the portal) bypass this.
customViews.names=viscoLink,${pack.customViews.names:-}
```

`packs/health/src/test/java/com/viscosiety/pack/health/ConsoleIndexPatchTest.java`:

```java
package com.viscosiety.pack.health;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The pack's build stages the Frank!Console page with its script tag (served from the overlay's WEB-INF/classes). */
class ConsoleIndexPatchTest {

    @Test
    void theStagedConsolePageLoadsTheFhirScript() throws Exception {
        Path page = Path.of("target/console-patched/console/index.html");
        assertTrue(Files.exists(page), "the pom's patch-console-index execution must run before the tests (process-resources)");
        String html = Files.readString(page);
        assertTrue(html.contains("<script src=\"fhir-webservices.js\"></script></body>"), "script tag before </body>");
        assertTrue(Files.exists(Path.of("target/classes/console/fhir-webservices.js")), "the script ships in the pack jar");
    }
}
```

`packs/health/src/test/java/com/viscosiety/pack/health/HealthLadybugColumnTest.java` and `viscolink/src/test/java/com/viscosiety/ladybug/CoreLadybugChangelogTest.java`: read the two XML files as text and assert — core: contains `<include file="ladybug/DatabaseChangelog_Pack.xml"`, contains `name="subjectid"`, does NOT contain `patientid`; pack: contains `name="patientid"`, contains `<columnExists tableName="LADYBUG" columnName="patientid"/>` inside a `<preConditions onFail="MARK_RAN">`. (Text assertions are enough: Liquibase itself runs them live in Task 6 and the ITs in Task 5.)

Run both modules' tests → RED (files not yet changed).

- [ ] **Step 2: Move the console patch.** In `viscolink/pom.xml` delete the `maven-dependency-plugin` execution `unpack-console-index` and the antrun execution `patch-console-index` (keep `stage-oauth2-override` and the war plugin's `webResources` — the core still needs `console-patched` for the OAuth2 override). Delete `<hapi.version>` from the core's properties. In `packs/health/pom.xml` add the same two executions verbatim from today's core pom, with ONE change: `patch-console-index` runs at phase `process-resources` (so the staged page exists when the tests run and when the assembly packs it). `git mv viscolink/src/main/resources/console/fhir-webservices.js packs/health/src/main/resources/console/fhir-webservices.js`.

- [ ] **Step 3: The Ladybug changelogs.** Core `ladybug/DatabaseChangelog_Custom.xml`: delete changeSet `LadybugCustom:2`; after `LadybugCustom:4` add:

```xml
    <changeSet id="LadybugCustom:5" author="Tom Peeters">
        <preConditions onFail="MARK_RAN">
            <not><columnExists tableName="LADYBUG" columnName="subjectid"/></not>
        </preConditions>
        <comment>The core's subject column (CorePack metadataName subjectId). A pack owns the column of its own
                 metadataName through ladybug/DatabaseChangelog_Pack.xml (the health pack: patientid). Lowercase to
                 avoid the PostgreSQL quoted-identifier mismatch.</comment>
        <addColumn tableName="LADYBUG">
            <column name="subjectid" type="java.sql.Types.VARCHAR(255)"/>
        </addColumn>
    </changeSet>
    <!-- The pack on the class path, if any, adds the column of its subject identifier. -->
    <include file="ladybug/DatabaseChangelog_Pack.xml" errorIfMissing="false"/>
```

Pack `ladybug/DatabaseChangelog_Pack.xml`:

```xml
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.26.xsd">

    <changeSet id="LadybugHealth:1" author="Tom Peeters">
        <preConditions onFail="MARK_RAN">
            <!-- Existing health databases got this column from the core's LadybugCustom:2 before the pack split. -->
            <not><columnExists tableName="LADYBUG" columnName="patientid"/></not>
        </preConditions>
        <comment>Add column patientid — the health pack's subject identifier (lowercase to avoid the PostgreSQL
                 quoted-identifier mismatch)</comment>
        <addColumn tableName="LADYBUG">
            <column name="patientid" type="java.sql.Types.VARCHAR(255)"/>
        </addColumn>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 4: SPI trim.** Remove `Map<String, String> propertyDefaults();` from `PackDescriptor` (and its Javadoc), the overrides from `CorePack`, `HealthPack`, `HealthValuesPack`, `DistinctSubjectPack`, the test `PackJsonTest.propertyDefaultsAreNeverSerialised` and `HealthPackTest.propertyDefaultsAreEmptyInM1`; `PackJson` never serialised it, so it changes only if it referenced the method. Update the `PackDescriptor` class Javadoc: "A pack's property defaults are the `DeploymentSpecifics.properties` of its jar (add-only keys)".

- [ ] **Step 5: Gates and commit.** `./mvnw -q install -pl viscolink -DskipTests && ./mvnw -q test -pl viscolink && ./mvnw -q test -pl packs/health && ./mvnw -q install -pl packs/health -DskipTests`; `unzip -Z1 packs/health/target/*-overlay.zip` now lists `WEB-INF/classes/console/index.html` too. Commit: `refactor(pack): the pack brings its defaults, its console script, its Ladybug column; propertyDefaults leaves the SPI`.

---

### Task 4: Demo configurations and the core's echo demo

**Files:**
- Move: `viscorunner/demo-configurations/` → `packs/health/demo-configurations/` (git mv, everything incl. `FrankConfig.xsd` and `README.md`)
- Create: `viscolink/demo-configurations/echo/Configuration.xml`, `viscolink/demo-configurations/README.md`, `viscolink/demo-configurations/FrankConfig.xsd` (copy of `viscorunner/configurations/FrankConfig.xsd`)
- Modify: `viscorunner/docker-compose.demo.yml` (mount path), `viscorunner/docker-compose.git.yml` (nothing — it names configuration names, not paths; verify), `viscorunner/scripts/update-frankconfig-xsd.sh` (the three xsd paths), `viscorunner/README.md` (the "What loads" table's path mention), `.gitlab-ci.yml` NOT yet (Task 7)
- Test: `viscolink/src/test/java/com/viscosiety/EchoDemoConfigurationTest.java`

- [ ] **Step 1: Failing test** — the echo demo parses as a Frank!Framework configuration and names the subject key:

```java
package com.viscosiety;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class EchoDemoConfigurationTest {

    @Test
    void theCoreDemoRecordsTheSubjectIdentifier() throws Exception {
        String xml = Files.readString(Path.of("demo-configurations/echo/Configuration.xml"));
        assertTrue(xml.contains("name=\"subjectId\""), "the echo adapter must put subjectId in the session so Ladybug records it");
        assertTrue(xml.contains("uriPattern=\"echo\""));
        assertTrue(!xml.toLowerCase().contains("fhir") && !xml.toLowerCase().contains("hl7"), "neutral demo");
    }
}
```

- [ ] **Step 2: Move and create.**

```bash
git mv viscorunner/demo-configurations packs/health/demo-configurations
mkdir -p viscolink/demo-configurations/echo
cp viscorunner/configurations/FrankConfig.xsd viscolink/demo-configurations/FrankConfig.xsd
```

`viscolink/demo-configurations/echo/Configuration.xml`:

```xml
<Configuration
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="../FrankConfig.xsd">
    <!-- The core's neutral demo: POST /viscolink/api/echo?subjectId=... echoes the body and records the
         subject identifier the way every pack's demo does (session key = the pack's sessionKey; on the
         core that is subjectId, which Ladybug shows as the Subject column and ViscoFlow filters on). -->
    <Adapter name="Echo" description="Echoes the request body; records the subject identifier">
        <Receiver name="EchoReceiver">
            <ApiListener name="EchoListener" uriPattern="echo" method="POST" allowedParameters="subjectId"/>
        </Receiver>
        <Pipeline>
            <Exits>
                <Exit name="Exit" state="SUCCESS" code="200"/>
            </Exits>
            <PutInSessionPipe name="recordSubject">
                <Param name="subjectId" sessionKey="subjectId"/>
                <Forward name="success" path="echo"/>
            </PutInSessionPipe>
            <EchoPipe name="echo">
                <Forward name="success" path="Exit"/>
            </EchoPipe>
        </Pipeline>
    </Adapter>
</Configuration>
```

(The ApiListener puts allowed query parameters in the session under their own name; `PutInSessionPipe` with a `Param` reading `sessionKey="subjectId"` re-stores it so the key exists even when the parameter is absent — if the F!F digester rejects a `Param` without `value`/`xpath`, use `<Param name="subjectId" sessionKey="subjectId" defaultValue=""/>`; the live check in Task 6 is the proof.) `README.md`: three lines on what it is and the curl to try.

- [ ] **Step 3: Paths.** `docker-compose.demo.yml`: `./demo-configurations:/opt/frank/configurations` → `../packs/health/demo-configurations:/opt/frank/configurations`. `update-frankconfig-xsd.sh`: the list of XSD copies becomes `viscorunner/configurations/FrankConfig.xsd`, `packs/health/demo-configurations/FrankConfig.xsd`, `viscolink/demo-configurations/FrankConfig.xsd` (fix the header comment too). `viscorunner/README.md`: every `demo-configurations/` mention → `packs/health/demo-configurations/` (the tree listing and the "What loads" text). Grep the repo for `viscorunner/demo-configurations` and `./demo-configurations` and fix every hit except the ITs (Task 5) and the CI (Task 7).

- [ ] **Step 4: Gates and commit.** `./mvnw -q test -pl viscolink` green; `git status` shows renames, not deletes+adds (`git mv`). Commit: `refactor(pack): the demo configurations move under the health pack; the core ships an echo demo`.

---

### Task 5: viscorunner staging and the integration tests on the staged layout

**Files:**
- Modify: `viscorunner/pom.xml`, `viscorunner/src/test/java/com/viscosiety/viscorunner/it/{ViscolinkLauncher,Hl7v2ToFhirIT,LabEnrichmentIT}.java`
- Create: `viscorunner/src/packs/core/WEB-INF/pack.properties`, `viscorunner/src/test/java/com/viscosiety/viscorunner/it/{PackOverlayLayoutIT,HealthClasspathIT}.java`

**Interfaces:**
- Produces after `./mvnw package -pl viscorunner`: `target/packs/health/` (the unpacked overlay zip), `target/packs/core/WEB-INF/pack.properties` (+ empty `WEB-INF/lib/`), `target/demo/health/`, `target/demo/core/`, `target/store/viscostore/viscostore.war`, `target/store/none/` (empty), next to today's `target/viscolink.war`, `target/drivers/`, `target/viscorunner-*.jar`. (`target/viscostore.war` stays where it is for the ITs.)

- [ ] **Step 1: Failing ITs.** `PackOverlayLayoutIT` asserts the tree above exists (every path, `pack.properties` of both packs parse and carry `pack.id` = `health`/`core` and a filtered `pack.version`, `target/packs/core/WEB-INF/lib` exists and is empty, `target/store/none` exists and is empty, `target/demo/health/hl7v2-to-fhir/` and `target/demo/core/echo/Configuration.xml` exist). `HealthClasspathIT`:

```java
package com.viscosiety.viscorunner.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * D8 for the class path: the core WAR's libraries plus the health overlay's libraries are exactly
 * the libraries the one WAR shipped before the split (golden/health-webapp-libs.txt, frozen), and
 * no jar is in both (duplicate classes on the webapp class loader).
 */
class HealthClasspathIT {

    private static final String PACK_JAR_PREFIX = "viscolink-pack-health-";

    @Test
    void warLibsAndPackLibsAreTodaysLibsWithNoOverlap() throws Exception {
        Set<String> war = new TreeSet<>();
        try (ZipFile zip = new ZipFile("target/viscolink.war")) {
            zip.stream().map(e -> e.getName())
                .filter(n -> n.startsWith("WEB-INF/lib/") && n.endsWith(".jar"))
                .map(n -> n.substring("WEB-INF/lib/".length()))
                .forEach(war::add);
        }
        Set<String> pack = new TreeSet<>();
        try (Stream<Path> files = Files.list(Path.of("target/packs/health/WEB-INF/lib"))) {
            files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar")).forEach(pack::add);
        }
        Set<String> golden = new TreeSet<>(Files.readAllLines(Path.of("src/test/resources/golden/health-webapp-libs.txt")));

        Set<String> overlap = new HashSet<>(war);
        overlap.retainAll(pack);
        assertTrue(overlap.isEmpty(), "jars in BOTH the WAR and the pack overlay (mark them provided in packs/health/pom.xml): " + overlap);

        Set<String> union = new TreeSet<>(war);
        union.addAll(pack);
        union.removeIf(n -> n.startsWith(PACK_JAR_PREFIX));
        assertEquals(golden, union, "WAR ∪ pack must be exactly today's libraries (missing = lost at runtime; extra = a new transitive)");
    }
}
```

Expect the first run to FAIL on `overlap` (HAPI's transitives the WAR already has: typically `commons-lang3`, `commons-codec`, `commons-io`, `commons-text`, `guava`, `jackson-*`, `slf4j-api`, `caffeine`, …) and possibly on `union` (a transitive that only HAPI pulled and that the WAR now lacks — that one must NOT be provided). For every overlap jar add an explicit `<dependency>` with `<scope>provided</scope>` to `packs/health/pom.xml` (same groupId/artifactId; version via the BOM or the exact version the WAR carries — the file name tells). Repeat until green. Record the final provided list in the report.

- [ ] **Step 2: Staging in `viscorunner/pom.xml`.** Add the dependency (next to the two WAR dependencies):

```xml
		<dependency>
			<groupId>com.visco</groupId>
			<artifactId>viscolink-pack-health</artifactId>
			<version>${project.version}</version>
			<type>zip</type>
			<classifier>overlay</classifier>
		</dependency>
```

and `com.visco:viscolink-pack-health` to the shade plugin's `<excludes>` (lines ~309–313). Add to the `maven-dependency-plugin` executions (after `copy-drivers`):

```xml
					<!-- The health pack's overlay (WEB-INF/lib + classes + pack.properties) → target/packs/health/ -->
					<execution>
						<id>unpack-health-overlay</id>
						<phase>package</phase>
						<goals><goal>unpack</goal></goals>
						<configuration>
							<overWriteSnapshots>true</overWriteSnapshots>
							<artifactItems>
								<artifactItem>
									<groupId>com.visco</groupId>
									<artifactId>viscolink-pack-health</artifactId>
									<version>${project.version}</version>
									<type>zip</type>
									<classifier>overlay</classifier>
									<outputDirectory>${project.build.directory}/packs/health</outputDirectory>
								</artifactItem>
							</artifactItems>
						</configuration>
					</execution>
```

Add an antrun plugin (3.1.0) execution `stage-packs-demos-stores`, phase `package`, declared AFTER the dependency plugin in `<plugins>` (same-phase executions run in declaration order; it needs `target/viscostore.war` from `copy-wars`):

```xml
							<target>
								<!-- core: no libs, its own identity -->
								<mkdir dir="${project.build.directory}/packs/core/WEB-INF/lib"/>
								<copy file="${project.basedir}/src/packs/core/WEB-INF/pack.properties"
								      todir="${project.build.directory}/packs/core/WEB-INF" overwrite="true">
									<filterset><filter token="PACK_VERSION" value="${project.version}"/></filterset>
								</copy>
								<!-- demo configurations per pack -->
								<copy todir="${project.build.directory}/demo/health" overwrite="true">
									<fileset dir="${project.basedir}/../packs/health/demo-configurations"/>
								</copy>
								<copy todir="${project.build.directory}/demo/core" overwrite="true">
									<fileset dir="${project.basedir}/../viscolink/demo-configurations"/>
								</copy>
								<!-- store: the WAR or nothing (COPY cannot be conditional) -->
								<mkdir dir="${project.build.directory}/store/none"/>
								<copy file="${project.build.directory}/viscostore.war"
								      tofile="${project.build.directory}/store/viscostore/viscostore.war" overwrite="true"/>
							</target>
```

`viscorunner/src/packs/core/WEB-INF/pack.properties` (Ant `@TOKEN@` filtering):

```properties
pack.id=core
pack.displayName=Core
pack.version=@PACK_VERSION@
pack.tagline=Integration platform
pack.linkBlurb=Receives, converts, validates and routes messages between your systems.
```

- [ ] **Step 3: The launcher learns the overlay.** `ViscolinkLauncher`: read `System.getProperty("viscolink.overlay.dir")`; when set and a directory, before `tomcat.start()`:

```java
        String overlayDir = System.getProperty("viscolink.overlay.dir");
        if (overlayDir != null && Files.isDirectory(Path.of(overlayDir))) {
            // The runner image's PreResources set (conf/Catalina/localhost/viscolink.xml), reproduced:
            // the pack's WEB-INF/lib and WEB-INF/classes join the webapp class path.
            WebResourceRoot resources = new StandardRoot(ctx);
            resources.addPreResources(new DirResourceSet(resources, "/", Path.of(overlayDir).toAbsolutePath().toString(), "/"));
            ctx.setResources(resources);
        }
```

(`org.apache.catalina.WebResourceRoot`, `org.apache.catalina.webresources.{StandardRoot,DirResourceSet}` — in `tomcat-embed-core`, already on the launcher class path.) Both ITs: `Paths.get("demo-configurations")` → `Paths.get("../packs/health/demo-configurations")`, and where they build the launcher JVM's command line add `"-Dviscolink.overlay.dir=" + Paths.get("target/packs/health").toAbsolutePath()`. Their "run `mvn install -pl viscolink,viscostore`" messages → `-pl viscolink,packs/health,viscostore`.

- [ ] **Step 4: Gates.** `./mvnw -q install -pl viscolink,packs/health,viscostore -DskipTests && ./mvnw -q verify -pl viscorunner` — all four ITs green (the two existing ones prove the overlay-lib mechanism: FHIR servlets, pipes and MLLP now come from the pack through `PreResources`, with no Docker). `ls -R viscorunner/target/packs viscorunner/target/demo viscorunner/target/store | head -40` in the report. Commit: `build(runner): stage the pack overlays, the demo sets and the stores; the ITs run the health pack from the overlay`.

---

### Task 6: One Dockerfile, the compose files, the landing page — and the docker proofs

**Files:**
- Rewrite: `viscorunner/Dockerfile`; Delete: `viscorunner/Dockerfile.viscolink`; Keep: `viscorunner/Dockerfile.viscostore`
- Rename: `viscorunner/conf/context-viscosuite.xml` → `viscorunner/conf/context-viscostore.xml`, `viscorunner/conf/context-viscolink.xml` → `viscorunner/conf/context-none.xml`
- Modify: `viscorunner/src/webapp/ROOT/index.html` (placeholders), `viscorunner/docker-compose.yml`, `viscorunner/docker-compose.viscolink.yml`; Create: `viscorunner/docker-compose.core.yml`, `viscorunner/postgres/init-ladybug.sql`
- Test: `viscorunner/src/test/java/com/viscosiety/viscorunner/DockerfileContractTest.java` (unit, surefire)

The subagent does Steps 1–5 (no docker); the controller runs Step 6 and 7 (docker) and reports back.

- [ ] **Step 1: Failing unit test** `DockerfileContractTest` (plain text assertions on `Dockerfile`, `docker-compose*.yml`, `src/webapp/ROOT/index.html`): the Dockerfile declares `ARG PACK=health` and `ARG STORE=viscostore` before their first use; copies `target/packs/${PACK}/` to `/opt/frank/webapp-overlay/viscolink/`, `target/demo/${PACK}/` to `/opt/frank/demo-configurations/`, `target/store/${STORE}/` to `/usr/local/tomcat/webapps/`, `conf/context-${STORE}.xml` to `/usr/local/tomcat/conf/context.xml`; `Dockerfile.viscolink` does not exist; `docker-compose.yml` passes `PACK: health` + `STORE: viscostore`, `docker-compose.viscolink.yml` `health`/`none`, `docker-compose.core.yml` `core`/`none` with image `…/viscolink:latest-core`; the ROOT page contains `%%PACK_TAGLINE%%`, `%%PACK_LINK_BLURB%%`, `%%PACK_STAMP%%` and no longer the literal "Healthcare integration platform"; the two store-less composes mount `./postgres/init-ladybug.sql`.

- [ ] **Step 2: The Dockerfile** (today's suite `Dockerfile` lines 1–27 unchanged, then):

```dockerfile
ARG PACK=health        # health | core
ARG STORE=viscostore   # viscostore | none

# Append Frank!Framework Tomcat settings
COPY --chown=tomcat src/scripts/catalinaAdditional.properties /tmp/catalinaAdditional.properties
RUN cat /tmp/catalinaAdditional.properties >> /usr/local/tomcat/conf/catalina.properties && \
	rm /tmp/catalinaAdditional.properties

# JNDI datasources: context-viscostore.xml carries jdbc/viscostore, context-none.xml does not.
COPY --chown=tomcat conf/context-${STORE}.xml /usr/local/tomcat/conf/context.xml

# Tomcat server.xml with the ContextFailureEventPublisher <Server> listener (Kubernetes Warning
# Event when a WAR context fails to start). Pinned copy of the tomcat:11.0.14 default.
COPY --chown=tomcat conf/server.xml /usr/local/tomcat/conf/server.xml
COPY --chown=tomcat target/viscorunner-*.jar /opt/frank/lib/

# The /viscolink context: /opt/frank/webapp-overlay/viscolink/ is a PreResources set. The pack's
# WEB-INF/lib and WEB-INF/classes land there, so the pack joins the webapp class path.
COPY --chown=tomcat conf/Catalina/localhost/viscolink.xml /usr/local/tomcat/conf/Catalina/localhost/viscolink.xml
COPY --chown=tomcat target/drivers/ /opt/frank/drivers/

# The pack (core = an empty WEB-INF/lib), its demo configurations and its identity.
COPY --chown=tomcat target/packs/${PACK}/ /opt/frank/webapp-overlay/viscolink/
COPY --chown=tomcat target/demo/${PACK}/  /opt/frank/demo-configurations/

# Landing page, stamped with the pack and the build.
COPY --chown=tomcat src/webapp/ROOT/ /usr/local/tomcat/webapps/ROOT/
ARG BUILD_TIMESTAMP=""
RUN set -e; P=/opt/frank/webapp-overlay/viscolink/WEB-INF/pack.properties; \
    val() { grep "^$1=" "$P" | cut -d= -f2- ; }; \
    BUILD_TS="${BUILD_TIMESTAMP:-$(date -u '+%Y-%m-%d %H:%M UTC')}"; \
    sed -i "s|%%PACK_TAGLINE%%|$(val pack.tagline)|; s|%%PACK_LINK_BLURB%%|$(val pack.linkBlurb)|; \
            s|%%PACK_STAMP%%| \&middot; pack $(val pack.id) $(val pack.version)|; \
            s|%%BUILD_TIMESTAMP%%| \&middot; Built ${BUILD_TS}|" /usr/local/tomcat/webapps/ROOT/index.html

# The core WAR and the store (target/store/none/ is empty, so the COPY copies nothing).
COPY --chown=tomcat target/viscolink.war /usr/local/tomcat/webapps/viscolink.war
COPY --chown=tomcat target/store/${STORE}/ /usr/local/tomcat/webapps/

# Fail fast on a stale stub WAR from .m2/ (< 1 MB); the store only when it is part of the image.
RUN set -e; \
    vl=$(stat -c%s /usr/local/tomcat/webapps/viscolink.war); \
    [ "$vl" -gt 1000000 ] || { echo ""; echo "ERROR: viscolink.war is ${vl} bytes — stale stub. Run:"; \
        echo "  ./mvnw install -pl viscolink,packs/health,viscostore -DskipTests && ./mvnw package -pl viscorunner -DskipTests"; echo ""; exit 1; }; \
    if [ "${STORE}" != "none" ]; then \
        vs=$(stat -c%s /usr/local/tomcat/webapps/viscostore.war); \
        [ "$vs" -gt 1000000 ] || { echo ""; echo "ERROR: viscostore.war is ${vs} bytes — stale stub (same command)."; exit 1; }; \
    fi

COPY --chown=tomcat src/scripts/entrypoint.sh /scripts/entrypoint.sh

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
	CMD curl --fail --silent http://localhost:8080/viscolink/iaf/api/server/health || exit 1

ENTRYPOINT ["/scripts/entrypoint.sh"]
CMD ["catalina.sh", "run"]
```

Keep today's comment lines where they still apply (the mkdir list must include `/opt/frank/lib`, `/opt/frank/webapp-overlay/viscolink` and a new `/opt/frank/demo-configurations`). `git rm viscorunner/Dockerfile.viscolink`; `git mv` the two context files; fix the "PostResources" comments to "PreResources" while there.

- [ ] **Step 3: ROOT page.** `src/webapp/ROOT/index.html`: line 140 `<p>Healthcare integration platform</p>` → `<p>%%PACK_TAGLINE%%</p>`; the ViscoLink card's `<p>` (lines 151–152) → `<p>%%PACK_LINK_BLURB%%</p>`; the footer → `…Viscosiety B.V.%%PACK_STAMP%%%%BUILD_TIMESTAMP%%`. The ViscoStore card stays as it is (probe-hidden when there is no store).

- [ ] **Step 4: Compose.** `docker-compose.yml` build args: add `PACK: health` and `STORE: viscostore`. `docker-compose.viscolink.yml`: `dockerfile: Dockerfile`, args `PACK: health`, `STORE: none`; postgres gains `- ./postgres/init-ladybug.sql:/docker-entrypoint-initdb.d/init-ladybug.sql:ro` (today a fresh volume has no `ladybug` database and the context never starts — found in M1's live check). `docker-compose.core.yml` = a copy of the viscolink compose with `PACK: core`, `image: registry.git.viscosiety.com/public-applications/viscosuite/viscolink:latest-core`, no `2575` port, and a header comment "the market-neutral core; `/viscolink/api/echo` is its demo". `postgres/init-ladybug.sql`:

```sql
-- The store-less stacks (viscolink, core): Ladybug's own database. The suite stack uses init-databases.sql.
CREATE DATABASE ladybug OWNER visco;
```

- [ ] **Step 5: Gate.** `./mvnw -q test -pl viscorunner -Dtest=DockerfileContractTest` green; commit: `build(runner): one Dockerfile with PACK and STORE; a core compose; the landing page stamped from the pack`.

- [ ] **Step 6 (controller, docker): the health proof.** From `viscorunner/`: `docker compose -p m2health -f docker-compose.viscolink.yml -f <scratch overlay mounting ../packs/health/demo-configurations, demo-tools, demo-secrets; amqp off> up --build -d`; then the M1 live-check script: `/viscolink/flow-api/pack` → `id: health`, `/viscolink/api-service/pack` → 401, `/viscolink/iaf/api/fhir-facades` reachable (console session) and `/viscolink/fhir/r4/metadata`-style facade path answers (not 404), an ADT^A04 through `hl7v2-to-fhir` → Ladybug row with `patientId=PAT-001`, ViscoFlow "Patient"; the console's Webservices page shows the "Available FHIR Facades" block (screenshot); `docker exec … ls /opt/frank/webapp-overlay/viscolink/WEB-INF/lib | head`; no `BeanExpressionException`, no `NoClassDefFoundError`, no Liquibase error (`LadybugHealth:1` MARK_RAN or ran). Then the suite: `docker compose -p m2suite up --build -d` (both WARs, `/viscostore/fhir/metadata` 200). Tear both down with `down -v`.

- [ ] **Step 7 (controller, docker): the core proof.** `docker compose -p m2core -f docker-compose.core.yml up --build -d`: `/viscolink/flow-api/pack` → `{"id":"core",…"subject":{"sessionKey":"subjectId",…"label":"Subject"}…"frankOwnedPaths":[]}`; `/viscolink/fhir/r4/metadata` → 404; `/viscolink/iaf/api/fhir-facades` → 404; `ls /opt/frank/webapp-overlay/viscolink/WEB-INF/lib` empty; the ROOT page says "Integration platform" and "pack core 1.0.0-SNAPSHOT"; Liquibase ran `LadybugCustom:5` (column `subjectid`); `curl -X POST 'http://localhost:8180/viscolink/api/echo?subjectId=S-42' -d hello` (with the core demo mounted over `/opt/frank/configurations`) → 200 and a Ladybug row with `subjectId = S-42` via `/iaf/ladybug/api/metadata/DatabaseDebugStorage?metadataNames=storageId,subjectId`; ViscoFlow shows "Subject" / "Subject ID…". `down -v`. Anything found here goes back as a fix round on the task that owns it.

---

### Task 7: CI — the matrix, the tags, the cleanup

**Files:** `.gitlab-ci.yml`, `build.sh`

- [ ] **Step 1: Install lists.** `test`: `verify -pl viscolink,packs/health,viscostore`. `package`: `install -pl viscolink,packs/health,viscostore -DskipTests` then `package -pl viscorunner -DskipTests`; artifacts add `viscorunner/target/packs/`, `viscorunner/target/demo/`, `viscorunner/target/store/`. `integration-test`: `install -pl viscolink,packs/health,viscostore -DskipTests` then `verify -pl viscorunner`. `build.sh` line 26: `-pl viscolink,packs/health,viscostore`; its comments accordingly.

- [ ] **Step 2: One build template, six image jobs.** Replace the four `docker:viscosuite:*` / `docker:viscolink:*` jobs with:

```yaml
# One recipe (viscorunner/Dockerfile) × PACK × STORE × arch. Image name and tag suffix per variant:
#   health + viscostore → viscorunner:<sha>[-arch]         (today's meaning, kept)
#   health + none       → viscolink:<sha>[-arch]           (today's meaning, kept)
#   core   + none       → viscolink:<sha>-core[-arch]
.docker-build:
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
    - docker build --platform "linux/$ARCH"
        --build-arg "PACK=$PACK" --build-arg "STORE=$STORE"
        --build-arg "BUILD_TIMESTAMP=$(date -u '+%Y-%m-%d %H:%M UTC') · $CI_COMMIT_SHORT_SHA"
        -t "$CI_REGISTRY_IMAGE/$IMAGE_NAME:$CI_COMMIT_SHA$TAG_SUFFIX-$ARCH"
        viscorunner/
    - docker push "$CI_REGISTRY_IMAGE/$IMAGE_NAME:$CI_COMMIT_SHA$TAG_SUFFIX-$ARCH"
  rules:
    - if: '$CI_COMMIT_BRANCH == "main"'

.health-suite: &health-suite
  PACK: health
  STORE: viscostore
  IMAGE_NAME: viscorunner
  TAG_SUFFIX: ""
.health-link: &health-link
  PACK: health
  STORE: none
  IMAGE_NAME: viscolink
  TAG_SUFFIX: ""
.core-link: &core-link
  PACK: core
  STORE: none
  IMAGE_NAME: viscolink
  TAG_SUFFIX: "-core"

docker:viscosuite:amd64:      { extends: .docker-build, tags: [docker, x86-64, dind], variables: { <<: *health-suite, ARCH: amd64 } }
docker:viscosuite:arm64:      { extends: .docker-build, tags: [docker, arm64, dind],  variables: { <<: *health-suite, ARCH: arm64 } }
docker:viscolink:amd64:       { extends: .docker-build, tags: [docker, x86-64, dind], variables: { <<: *health-link,  ARCH: amd64 } }
docker:viscolink:arm64:       { extends: .docker-build, tags: [docker, arm64, dind],  variables: { <<: *health-link,  ARCH: arm64 } }
docker:viscolink-core:amd64:  { extends: .docker-build, tags: [docker, x86-64, dind], variables: { <<: *core-link,    ARCH: amd64 } }
docker:viscolink-core:arm64:  { extends: .docker-build, tags: [docker, arm64, dind],  variables: { <<: *core-link,    ARCH: arm64 } }
```

(If `glab ci lint` rejects the anchor-merge inside `variables`, write the six jobs out in full — explicit beats clever here.) The two `docker:viscostore:*` jobs stay as they are.

- [ ] **Step 3: Manifests.** `manifest:viscosuite` and `manifest:viscolink` add two tags each: `-t "$IMAGE:$CI_COMMIT_SHA-health" -t "$IMAGE:latest-health"` (resp. `$IMAGE_VISCOLINK`). New `manifest:viscolink-core` (needs `docker:viscolink-core:amd64/arm64`): `-t "$IMAGE_VISCOLINK:$CI_COMMIT_SHA-core" -t "$IMAGE_VISCOLINK:latest-core"` from `…:$CI_COMMIT_SHA-core-amd64` and `-arm64`. `trigger:viscoforge` is unchanged (it needs the health manifests). `cleanup:arch-tags` needs `manifest:viscolink-core` too and loops over `(viscorunner "") (viscolink "") (viscolink "-core") (viscostore "")`, deleting `${CI_COMMIT_SHA}${SUFFIX}-amd64/-arm64`. Update the header comment table of tags.

- [ ] **Step 4: Lint and commit.** `GITLAB_HOST=git.viscosiety.com glab ci lint` (from the worktree; network, allowed for this one command — say so) must pass. Commit: `ci: build the image matrix (PACK × STORE) from one Dockerfile; -health and -core tags`.

---

### Task 8: Docs, the design write-back, the merge request

**Files:** `docs/design/2026-10-07-vertical-packs-design.md`, `CLAUDE.md`, `README.md`, `viscorunner/README.md`, `packs/health/README.md` (new, short), `packs/health/demo-configurations/README.md` (path mentions)

- [ ] **Step 1: Design write-back.** Status line: "M2 shipped (date): `packs/health` (`viscolink-pack-health`, overlay zip), one `viscorunner/Dockerfile` (`PACK`/`STORE`), tags `-health`/`-core`". §4.1: the real tree (`packs/health/{src,demo-configurations,util/{hl7util,fhirutil,demo}}`, `viscolink/demo-configurations/echo`), hl7util as a standalone CLI module that no image stages. §4.2: `propertyDefaults()` removed, the add-only rule for a pack's `DeploymentSpecifics.properties`, the Ladybug column rule (core `subjectid`, pack `DatabaseChangelog_Pack.xml`). §4.5: the overlay zip replaces the `copy-dependencies` staging description; `WEB-INF/classes/console/index.html` as the console-script mechanism (option (a) of §10 taken, and why: deterministic class-path order, D8 timing); `conf/context-${STORE}.xml`; the viscolink image now carries `server.xml` + the runner jar; `pack.properties` and the ROOT stamps; the tag table with `<sha>`/`latest` (no `<v>` exists) plus `-health`/`-core`, the per-arch intermediates and the cleanup; the two store-less composes and `init-ladybug.sql`. §10: BuildInfo question answered (not changed); the `customViews.names` deployer caveat now reads as a standing rule for M5. §9 M2 row: proof as done (local docker proofs; the tenant-instance Larva run is a post-merge rollout step, see below).

- [ ] **Step 2: Repo docs.** `CLAUDE.md`: module list (`packs/health`, the util move), build commands (`install -pl viscolink,packs/health,viscostore`), the "Packs" section (M2 shipped: where things live, the add-only properties rule, the Ladybug column rule, the overlay zip, `PACK`/`STORE`, tags, "a class in `com.viscosiety.pack` is core; packs use `com.viscosiety.pack.<id>`"), and fix the stale facts the inventory found: viscorunner packaging is `jar` not `pom`; there is no `target/configurations/`; the context files are `conf/context-{viscostore,none}.xml`; no `SimpleFhirServer`, no `src/main/configurations`. `README.md`: the build commands and a "Images" paragraph (names, tags, what `-core` is). `viscorunner/README.md`: the compose files incl. `docker-compose.core.yml`, `PACK`/`STORE`, the demo path, the context file names (line ~122). `packs/health/README.md`: five lines — what the pack is, how it reaches the image, the two rules.

- [ ] **Step 3: Commit, final review, MR.** Commit `docs(pack): M2 — the module split, the image matrix and the pack rules`. The controller then runs the final whole-branch review (sonnet), one fix wave, the scoped re-review, pushes `feat/vertical-packs-m2` and opens the MR against `main`, title `feat(pack): the module split and the image matrix (vertical packs M2)`, with the docker-proof screenshots (console FHIR block on health; ROOT + ViscoFlow "Subject" on core).

**Post-merge rollout (not part of the branch, written in the MR):** main's pipeline publishes the new `viscorunner:<sha>`/`viscolink:<sha>` and triggers viscoForge, which rebuilds `FROM` them; one stop/resume of a tenant instance pulls the new Forge image (`:latest`, pull Always); the M2 proof on a tenant instance is the existing eval scenario `cocktail-larva-tests` on `bo/eval-larva` (DeepSeek, as always) plus the demo flows on demo-test-2 — the health image must behave exactly as before. viscoFoundry's M4 adds the catalogue row for `viscolink:<sha>-core`; until then nothing in the portal selects a core image.

---

## Self-review

**Spec coverage (§9 M2 row + §4.1 + §4.5 + §4.6):** module split with `packs/health` (Tasks 1–3); hl7util moved (Task 1; as a standalone module — deviation written back in Task 8); demo configurations moved + core demo (Task 4); `viscorunner` image matrix with `PACK`/`STORE` (Tasks 5–6); CI tags (Task 7); `viscolink:<sha>`/`viscorunner:<sha>` behave as before (golden class-path IT in Task 5, the two existing ITs on the overlay, the docker health proof in Task 6, the post-merge tenant run); `viscolink:<sha>-core` boots with no FHIR servlets and `/pack` = core (Task 6 Step 7). §10's spike recommendations applied: property defaults via the pack's file (Task 3), `customViews.names` indirection (Task 3), the console script by option (a) (Task 3), BuildInfo left alone (Task 8).

**Deviations from the design to write back:** overlay zip artifact instead of a `copy-dependencies` staging rule; option (a) for the console script; `propertyDefaults()` dropped; hl7util not a pack dependency; `conf/context-${STORE}.xml` and the viscolink image gaining `server.xml` + the runner jar; no `<v>` tags exist (sha/latest); the Ladybug column rule; `pack.properties` + ROOT stamps; `init-ladybug.sql` for the store-less composes.

**Type consistency:** `PackVersion.get()` (Task 1) ← `HealthPack.version()`, `HealthPackModule` (Task 2); `HealthValuesPack` (Task 2) loses `propertyDefaults()` in Task 3 with the SPI; the zip layout (Task 1) = what Task 5 unpacks = what Task 6 COPYs; `target/{packs,demo,store}` (Task 5) = the Dockerfile's sources (Task 6) = the CI artifacts (Task 7); build args `PACK`/`STORE` (Task 6) = the compose args (Task 6) = the CI variables (Task 7); `viscolink.overlay.dir` (Task 5) is private to the ITs.
