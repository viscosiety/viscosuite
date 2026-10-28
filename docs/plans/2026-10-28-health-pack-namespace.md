# The health pack in its own Java namespace — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every health pack class lives under `com.viscosiety.pack.health`, old `className` values keep loading (with a deprecation warning), and an upstream-style Frank!Framework PR adds class-name aliases.

**Architecture:** `git mv` the pack's packages under `com.viscosiety.pack.health`; seven empty `@Deprecated` + `@ConfigurationWarning` subclasses stay at the old names. Cross-repo texts (portal manual pages, The Way, demo templates) switch to the new names only after the runner images carry them. Separately, F!F's `ClassNameRewriter` learns aliases from `META-INF/frankframework/class-name-aliases.properties`.

**Tech Stack:** Java 21, Maven, Frank!Framework 10.3 (pinned `10.3.0-20260924.042323`), JUnit 5, failsafe ITs with the embedded `ViscolinkLauncher`; portal Next.js/vitest; GitLab + `glab api`; GitHub + `gh`.

**Spec:** `docs/design/2026-10-28-health-pack-namespace-design.md`.

**Branches:** viscoSuite `feat/health-pack-namespace` (worktree `.worktrees/health-namespace`, from `main` 37f04d4); F!F `feat/class-name-aliases` in a worktree of `/Users/tom/IdeaProjects/frankframework`, PR against `tommy2d/frankframework` `master` (synced from `frankframework/frankframework` first — Tom, 2026-10-28); viscoFoundry `claude/vigilant-snyder-4539da` (session worktree); The Way `docs/health-namespace`.

## Global Constraints

- New packages exactly: `com.viscosiety.pack.health.fhir`, `com.viscosiety.pack.health.mllp`, `com.viscosiety.pack.health.pipes`, `com.viscosiety.pack.health.hl7util`.
- Shims exactly seven: `com.viscosiety.fhir.FhirListener`, `com.viscosiety.mllp.MllpListener`, `com.viscosiety.mllp.MllpSender`, `com.viscosiety.pipes.Hl7v2ToXmlPipe`, `com.viscosiety.pipes.XmlToHl7v2Pipe`, `com.viscosiety.pipes.FhirFormatPipe`, `com.viscosiety.pipes.FhirValidatorPipe`. Annotations: `@Deprecated(since = "0.21.0", forRemoval = true)` and `@ConfigurationWarning("Use className=\"<new fully qualified name>\"")`. Nothing else in the old packages from the pack.
- Spring bean ids in `springFhir.xml` / `springMllp.xml` unchanged; only `class=` values move.
- No jar added or removed (`HealthClasspathIT`, `PackOverlayLayoutIT` unchanged and green).
- Portal manual pages, The Way and the demo templates are merged/changed only after rollout step 2 of spec §4 (images rebuilt, Healthcare instances restarted). Their MRs may be opened earlier, marked "merge after rollout".
- Never `git stash`; commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: Move the pack's classes (no behaviour change)

**Files:**
- Move: `packs/health/src/main/java/com/viscosiety/{fhir,mllp}/*.java` → `.../com/viscosiety/pack/health/{fhir,mllp}/`; `.../com/viscosiety/pipes/*.java` → `.../pack/health/pipes/`
- Move: `packs/health/src/test/java/com/viscosiety/{fhir,pipes}/*.java` → `.../pack/health/{fhir,pipes}/`
- Move: `packs/health/util/hl7util/src/main/java/com/viscosiety/hl7util/*.java` → `.../pack/health/hl7util/`
- Modify: `packs/health/src/main/resources/springFhir.xml`, `springMllp.xml`, `packs/health/util/hl7util/pom.xml` (`mainClass`)

**Interfaces:** Produces the new fully qualified names used by every later task.

- [ ] **Step 1:** `git mv` each directory's files; then rewrite `package com.viscosiety.X;` → `package com.viscosiety.pack.health.X;` and every `com.viscosiety.(fhir|mllp|hl7util)\.` / `com.viscosiety.pipes.(Hl7v2ToXmlPipe|XmlToHl7v2Pipe|FhirFormatPipe|FhirValidatorPipe)` occurrence inside `packs/health/src`, the two Spring XMLs and the hl7util pom with a scripted `sed` (Perl regex, word-anchored). Classes that used package-private access across the old packages: none expected — the compiler is the check.
- [ ] **Step 2:** Run `./mvnw -q install -pl viscolink,packs/health,packs/health/util/hl7util` → BUILD SUCCESS, all pack tests green (same count as before the move: record it first with `./mvnw -q test -pl packs/health` on main).
- [ ] **Step 3:** `grep -rnE 'com\.viscosiety\.(fhir|mllp|hl7util)\.|com\.viscosiety\.pipes\.' packs/health/src packs/health/util/hl7util` → only Javadoc/comments that Task 2 replaces, nothing else.
- [ ] **Step 4:** Commit `refactor(health): move the pack's classes under com.viscosiety.pack.health`.

### Task 2: Shims at the old names + guard test

**Files:**
- Create: `packs/health/src/main/java/com/viscosiety/fhir/FhirListener.java`, `.../mllp/MllpListener.java`, `.../mllp/MllpSender.java`, `.../pipes/{Hl7v2ToXmlPipe,XmlToHl7v2Pipe,FhirFormatPipe,FhirValidatorPipe}.java`
- Test: `packs/health/src/test/java/com/viscosiety/pack/health/LegacyClassNamesTest.java`

- [ ] **Step 1: failing test**

```java
package com.viscosiety.pack.health;

class LegacyClassNamesTest {
	static final Map<String, String> RENAMED = Map.of(
		"com.viscosiety.fhir.FhirListener", "com.viscosiety.pack.health.fhir.FhirListener",
		"com.viscosiety.mllp.MllpListener", "com.viscosiety.pack.health.mllp.MllpListener",
		"com.viscosiety.mllp.MllpSender", "com.viscosiety.pack.health.mllp.MllpSender",
		"com.viscosiety.pipes.Hl7v2ToXmlPipe", "com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe",
		"com.viscosiety.pipes.XmlToHl7v2Pipe", "com.viscosiety.pack.health.pipes.XmlToHl7v2Pipe",
		"com.viscosiety.pipes.FhirFormatPipe", "com.viscosiety.pack.health.pipes.FhirFormatPipe",
		"com.viscosiety.pipes.FhirValidatorPipe", "com.viscosiety.pack.health.pipes.FhirValidatorPipe");

	@ParameterizedTest @MethodSource("pairs")
	void oldNameIsADeprecatedSubclassThatWarns(String oldName, String newName) throws Exception {
		Class<?> old = Class.forName(oldName), now = Class.forName(newName);
		assertEquals(now, old.getSuperclass());
		// F!F's ValidateAttributeRule uses exactly this lookup on the bean class
		Deprecated dep = AnnotationUtils.findAnnotation(old, Deprecated.class);
		ConfigurationWarning warn = AnnotationUtils.findAnnotation(old, ConfigurationWarning.class);
		assertNotNull(dep); assertTrue(dep.forRemoval()); assertEquals("0.21.0", dep.since());
		assertEquals("Use className=\"" + newName + "\"", warn.value());
		assertNull(AnnotationUtils.findAnnotation(now, Deprecated.class));
		assertNull(AnnotationUtils.findAnnotation(now, ConfigurationWarning.class));
		assertEquals(0, old.getDeclaredMethods().length);   // a shim adds no behaviour
	}

	@Test
	void oldNamePipeConfiguresLikeTheNewOne() throws Exception {
		// instantiate by name, as the digester does, and configure()
	}
}
```
The second test builds `com.viscosiety.pipes.Hl7v2ToXmlPipe` via `Class.forName(..).getDeclaredConstructor().newInstance()`, sets name/`hl7Version="2.5"`/`validateMessage(false)`, adds a `success` forward, `configure()`s, runs the same ADT^A01 the existing `Hl7v2ToXmlPipe` test uses (copy its fixture) and asserts the output equals the new class's output for the same input. Also a test that the old-package directory holds exactly the seven shims (scan `target/classes/com/viscosiety/{fhir,mllp,pipes}`), so a stray moved class back in an old package fails.
- [ ] **Step 2:** `./mvnw -q test -pl packs/health -Dtest=LegacyClassNamesTest` → FAIL (`ClassNotFoundException: com.viscosiety.fhir.FhirListener`).
- [ ] **Step 3: shims**, e.g.

```java
package com.viscosiety.pipes;

import org.frankframework.configuration.ConfigurationWarning;

/**
 * Old name of {@link com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe}; kept so existing configurations
 * keep loading. Use the new class name.
 */
@Deprecated(since = "0.21.0", forRemoval = true)
@ConfigurationWarning("Use className=\"com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe\"")
public class Hl7v2ToXmlPipe extends com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe {
}
```
Same shape for the other six (license header as the other files). `FhirListener`'s base is generic (`JavaListener<String>`) — the shim needs no type parameter.
- [ ] **Step 4:** test green; full `./mvnw -q install -pl packs/health` green.
- [ ] **Step 5:** Commit `feat(health): keep the old class names loadable, with a deprecation warning`.

### Task 3: Demos, docs and Javadoc teach the new names

**Files:** `packs/health/demo-configurations/**/*.xml` (12), `packs/health/demo-configurations/README.md`, `packs/health/README.md`, Javadoc `{@link}`s in the moved classes, `CLAUDE.md`, `CHANGELOG.md`. Leave dated design/plan documents as written.

- [ ] **Step 1:** `sed` the 12 configurations' `className`s to the new names; README sentences; CLAUDE.md lines 62/68/120 (module description: `com.viscosiety.pack.health.{fhir,mllp,pipes}`; drop the "shared with the health pack's four pipes" clause — `com.viscosiety.pipes` holds core pipes plus the seven-class shim list until removal; note viscoFoundry's scan matches `com.viscosiety.pack.health.` as a whole package).
- [ ] **Step 2:** CHANGELOG Unreleased → Changed: "The health pack's classes moved to `com.viscosiety.pack.health.{fhir,mllp,pipes,hl7util}`. The old names of the seven classes a configuration uses (…) still load and show a deprecation warning; they will be removed in a later release. A configuration that uses the new names needs this release or later."
- [ ] **Step 3:** `grep -rnE 'com\.viscosiety\.(fhir|mllp|hl7util)\.|com\.viscosiety\.pipes\.(Hl7v2|XmlTo|Fhir)' --exclude-dir=target . | grep -v '^./docs/'` → only the seven shims, the test's map, CHANGELOG and CLAUDE.md's shim list.
- [ ] **Step 4:** Commit `docs(health): demos and docs use the new class names`.

### Task 4: Integration test — old and new names in one running configuration

**Files:**
- Create: `viscorunner/src/test/resources/it-configurations/legacy-class-names/Configuration.xml`
- Create: `viscorunner/src/test/java/com/viscosiety/viscorunner/it/LegacyClassNamesIT.java`

- [ ] **Step 1:** Configuration `legacy-class-names` with three adapters: `OldPipe` (ApiListener `uriPattern="legacy/old"`, `<Pipe className="com.viscosiety.pipes.Hl7v2ToXmlPipe" name="parse" validateMessage="false" hl7Version="2.5"/>`), `NewPipe` (same, `legacy/new`, `com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe`), `OldFhirListener` (`<Listener className="com.viscosiety.fhir.FhirListener" name="OldPatientRead" fhirVersion="R4" facadeName="legacy-names" resourceType="Patient" operation="read"/>` with an `EchoPipe` returning a fixed Patient XML).
- [ ] **Step 2:** `LegacyClassNamesIT`, modelled on `Hl7v2ToFhirIT`'s setup minus ViscoStore (pass `http://localhost:1/fhir/` as store URL; the configuration never calls it): launch `ViscolinkLauncher` with the test-resources configurations dir and name `legacy-class-names`; wait for `/viscolink/iaf/api/server/health` 200 (all adapters STARTED); POST the ADT^A01 from `packs/health/util/hl7util/scripts/HTTP-ADT-A01-AdmitPatient.sh` to `/viscolink/api/legacy/old` and `/legacy/new` → both 200, identical bodies containing `<ADT_A01`; GET `/viscolink/fhir/r4/legacy-names/metadata` → 200 and `Patient` in the CapabilityStatement; GET `/viscolink/iaf/api/server/warnings` → contains `com.viscosiety.pipes.Hl7v2ToXmlPipe` … "marked for removal" and the FhirListener equivalent, and nothing for `com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe`. (Adjust the paths to what the launcher serves; the neutral-paths change may make it `/runtime` — read `ViscolinkLauncher`.)
- [ ] **Step 3:** `./mvnw -q install -pl viscolink,packs/health,viscostore -DskipTests && ./mvnw -q verify -pl viscorunner -Dit.test='LegacyClassNamesIT,Hl7v2ToFhirIT,LabEnrichmentIT,HealthClasspathIT,PackOverlayLayoutIT'` → all green.
- [ ] **Step 4:** Commit `test(health): old and new class names load side by side`.

### Task 5: Docker proof + viscoSuite MR

- [ ] **Step 1:** Build the suite runner image locally (`./mvnw package -pl viscorunner -DskipTests`, `docker build` per viscorunner README), run it with the demo-template-health configuration (old names; fetch from GitLab project 30) and a copy with new names; check the log for `Configuration [...] startup`, zero `ClassNotFoundException`, the deprecation warning only for the old copy, and `POST /runtime/api/hl7v2/patient` 200 on both.
- [ ] **Step 2:** Push the branch (`git -c credential.helper='!glab auth git-credential' push https://git.viscosiety.com/public-applications/viscosuite.git feat/health-pack-namespace`) and open the MR with `GITLAB_HOST=git.viscosiety.com glab api` (description: what moved, the shims, the warning, tests run, rollout order from spec §4, "Docs: CHANGELOG + CLAUDE.md").

### Task 6: Frank!Framework — class-name aliases (PR on Tom's fork)

**Files (in a worktree of `/Users/tom/IdeaProjects/frankframework`):**
- Modify: `core/src/main/java/org/frankframework/configuration/filters/ClassNameRewriter.java`
- Create: `core/src/main/java/org/frankframework/configuration/filters/ClassNameAliases.java`
- Modify: `core/src/main/java/org/frankframework/configuration/digester/ConfigurationDigester.java` (filter installation)
- Test: `core/src/test/java/org/frankframework/configuration/ClassNameRewriterTest.java`, test resource `core/src/test/resources/META-INF/frankframework/class-name-aliases.properties`
- Modify: `RELEASES.md` (Upcoming)

- [ ] **Step 1:** Sync the fork: `gh repo sync tommy2d/frankframework --source frankframework/frankframework --branch master`; `git fetch fork master`; `git worktree add -b feat/class-name-aliases .worktrees/class-name-aliases fork/master`.
- [ ] **Step 2: failing tests** in `ClassNameRewriterTest` (follow its existing style): alias `org.frankframework.test.OldEchoPipe=org.frankframework.pipes.EchoPipe` in the test resource → a configuration element `className="org.frankframework.test.OldEchoPipe"` comes out as `org.frankframework.pipes.EchoPipe` with one deprecation warning `[org.frankframework.test.OldEchoPipe] has been renamed to [org.frankframework.pipes.EchoPipe]. Please change the className attribute.`; a non-aliased name is untouched; an alias whose target cannot be loaded keeps the original and warns; `ClassNameAliases.load(classLoader)` with two resources mapping one key differently keeps the first and reports the conflict.
- [ ] **Step 3: implement.** `ClassNameAliases` (final, static `getInstance()` cached per class loader in a `ConcurrentHashMap<ClassLoader, ClassNameAliases>`; `load(ClassLoader)` reads every `META-INF/frankframework/class-name-aliases.properties` via `getResources`, `Properties.load`, merges with first-wins, logs a warning per conflict naming both URLs; `Optional<String> target(String className)`; `boolean isEmpty()`). `ClassNameRewriter.startElement`: after the legacy check, `aliases.target(className)` → if present and `canLoadClass(target)`: set it + `addDeprecationWarning(...)`; if present but not loadable: keep + warning "Class name alias [%s] points at [%s], which cannot be loaded.". New constructor flag `rewriteLegacy` so the digester can install the filter for aliases only. `ConfigurationDigester`: install when `rewriteLegacyClassNames || !ClassNameAliases.getInstance().isEmpty()`, passing the flag.
- [ ] **Step 4:** `./mvnw -q -pl core test -Dtest='ClassNameRewriterTest,ConfigurationDigesterTest'` green; `./mvnw -q -pl core -am -DskipTests install` compiles.
- [ ] **Step 5:** RELEASES.md Upcoming line: "Frameworks and libraries can rename classes without breaking configurations: a `META-INF/frankframework/class-name-aliases.properties` on the class path maps old `className` values to new ones; the configuration loads with a deprecation warning."
- [ ] **Step 6:** Commit, push to `fork`, `gh pr create --repo tommy2d/frankframework --base master --head feat/class-name-aliases` with the PR template filled in.

### Task 7: Portal — scan tests, manual pages, status (merge after rollout)

**Files:** `src/lib/healthcare-component-scan.ts` (comment only), `tests/unit/healthcare-component-scan.test.ts`, `data/packs/health/*.md` (5), `tests/unit/health-pack-knowledge.test.ts`, `CLAUDE.md`.

- [ ] **Step 1: failing tests:** new names `com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe`, `com.viscosiety.pack.health.fhir.FhirListener`, `com.viscosiety.pack.health.mllp.MllpSender` are found; `com.viscosiety.pipes.LemmaPipe` still is not. (They pass already with the current regex — then they are regression pins; say so.)
- [ ] **Step 2:** Manual pages: every `className` → new name; markers `<!-- source: viscosuite packs/health @ <merge sha> -->`; one sentence on the overview page: older configurations may use the old names (`com.viscosiety.fhir.FhirListener`, `com.viscosiety.mllp.*`, `com.viscosiety.pipes.<HealthPipe>`), which still load with a deprecation warning — write new configurations with the new names, and change old ones when the user agrees. Update `health-pack-knowledge.test.ts` expectations.
- [ ] **Step 3:** `HEALTH_PACK_PIPES` comment: those four are the old-package shims (removed at the shim removal); new names are covered by the `pack.health` package match.
- [ ] **Step 4:** `npx vitest run tests/unit/healthcare-component-scan.test.ts tests/unit/health-pack-knowledge.test.ts` + `npx tsc --noEmit` green; CLAUDE.md status entry; commit; push; MR "merge after rollout".

### Task 8: The Way (merge after rollout)

- [ ] **Step 1:** Clone `commercial/the-way` (project 35) to the scratchpad, branch `docs/health-namespace`; `packs/core/01-no-healthcare.md`: "none of the healthcare components in com.viscosiety.pack.health (formerly com.viscosiety.fhir / com.viscosiety.mllp), nor the HL7v2/FHIR pipes, exist here". Run its check script; push; MR "merge after rollout".

### Task 9: Hand-over for the rollout

- [ ] Write the rollout checklist (spec §4 + the two demo template edits with exact old→new lines, the template manifest sha note in viscoFoundry) into the shared notes folder and into the viscoSuite MR description.
