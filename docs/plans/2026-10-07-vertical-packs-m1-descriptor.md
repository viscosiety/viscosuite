# Vertical Packs — M1: the pack descriptor and the subject identifier — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ViscoLink learns what pack it runs: a `PackDescriptor` SPI with a built-in `CorePack` and an in-module `HealthPack`, a registry that resolves exactly one, a descriptor endpoint for machines (`/api-service/pack`) and one for ViscoFlow (`/flow-api/pack`), and Ladybug's metadata, ViscoFlow's "Patient" column/filter/detail and the console's F!F-owned paths driven by the descriptor instead of literals. No module split, no image change: the health image behaves exactly as today.

**Architecture:** `com.viscosiety.pack` holds the SPI (`PackDescriptor`, `SubjectIdentifier`, `ConsoleView`), the two descriptors and `PackRegistry` (ServiceLoader → zero or one; two = fail fast). Consumers read `PackRegistry.get()`: the Ladybug Spring wiring (`springIbisTestToolVisco.xml`), `ConsoleSecurityRegistrar.isFrankOwnedPath`, `FlowController`, and ViscoFlow's JS through a new `/flow-api/pack` call made once at load. The `consoleViews()` / `propertyDefaults()` members exist in the SPI but are applied in M2; a spike in this plan records how.

**Tech Stack:** Java 21, Frank!Framework (BOM pinned in `viscolink/pom.xml`), Spring XML wiring, Jakarta Servlet, Jackson (already used by `FlowController`), JUnit 5 + Mockito (see `ConfigRefServletTest`), vanilla ES-module JavaScript for ViscoFlow (no test runner; verified live).

**Spec:** `docs/design/2026-10-07-vertical-packs-design.md` §3 (D1, D3–D5, D8), §4.2–§4.4, §9 (M1), §10.

**Branch:** `feat/vertical-packs-m1` from `docs/vertical-packs-design` (the spec + this plan), MR against `main` after the spec's MR merges. One MR.

## Global Constraints

- **D8 — the health image behaves byte for byte as today.** With `HealthPack` resolved: the Ladybug metadata extractor still has `name = patientId`, `label = PatientId`, `sessionKey = patientId`, and `metadataNames` keeps `patientId` in the same position; ViscoFlow sends the same Ladybug query as before, shows "Patient" and the `Patient ID…` placeholder; `/fhir/` is still F!F-owned. Existing tests stay green and unchanged except where a literal moves behind the registry (say so in the report).
- **One pack or none.** `PackRegistry` resolves the `ServiceLoader` entries: none → `CorePack`; one → that one; more than one → `IllegalStateException` naming both ids (fail fast at first use, which is context start). The result is cached for the JVM.
- **The descriptor is not secret** but is served only on authenticated chains: `/api-service/pack` on the BEARER_ONLY family (same base class, same role property pattern), `/flow-api/pack` inside ViscoFlow's console session like the other `flow-api` calls. Nothing new is public.
- **No new dependency.** Jackson is already on the class path (`FlowController`).
- **Texts and names are the spec's:** health subject = `sessionKey "patientId"`, `metadataName "patientId"`, `metadataLabel "PatientId"`, `displayLabel "Patient"`, no format; core subject = `"subjectId"`, `"subjectId"`, `"SubjectId"`, `"Subject"`, no format. JSON field names in §4.4 (`id`, `displayName`, `version`, `subject{sessionKey,label,metadataName}`, `consoleViews[]`, `deidentificationStrategyIds[]`) plus `subject.metadataLabel` and `subject.format` (string or null).
- TDD per task: write the test, see it fail for the right reason, implement. Gates before every commit: `./mvnw -q test -pl viscolink` green (do not run `-pl viscostore`, unaffected); `./mvnw -q install -pl viscolink` once at the end of a task that changes resources, so the WAR builds.
- Commit messages end with a `Co-Authored-By` line naming the model that did the work, as Claude Code reports it for that agent.
- Licence header on every new Java file (copy the one on `AbstractBearerServiceServlet`).

## File structure

| File | Responsibility |
|---|---|
| `viscolink/src/main/java/com/viscosiety/pack/PackDescriptor.java`, `SubjectIdentifier.java`, `ConsoleView.java` | The SPI |
| `…/pack/CorePack.java`, `…/pack/HealthPack.java` | The built-in and the in-module descriptors |
| `…/pack/PackRegistry.java` | ServiceLoader resolution, cached |
| `…/pack/PackJson.java` | One place that turns a descriptor into the §4.4 JSON (used by both endpoints) |
| `viscolink/src/main/resources/META-INF/services/com.viscosiety.pack.PackDescriptor` | Registers `HealthPack` (M1 only; moves to the pack jar in M2) |
| `…/pack/SubjectMetadataFieldExtractor.java` + `springIbisTestToolVisco.xml` | Ladybug metadata from the registry |
| `…/security/ConsoleSecurityRegistrar.java` | F!F-owned paths = core ∪ pack |
| `…/flow/FlowController.java` + `webapp/flow/{index.html,js/api.js,js/app.js,js/render.js,js/checkpoints.js}` | ViscoFlow driven by the descriptor |
| `viscolink/src/main/java/org/frankframework/visco/security/PackServlet.java` | `/api-service/pack` |
| `docs/design/2026-10-07-vertical-packs-design.md` (status + §10), `CLAUDE.md` | Docs |

---

### Task 1: The SPI, the two descriptors and the registry

**Files:**
- Create: `PackDescriptor.java`, `SubjectIdentifier.java`, `ConsoleView.java`, `CorePack.java`, `HealthPack.java`, `PackRegistry.java`, `PackJson.java` (all under `viscolink/src/main/java/com/viscosiety/pack/`), `viscolink/src/main/resources/META-INF/services/com.viscosiety.pack.PackDescriptor`
- Test: `viscolink/src/test/java/com/viscosiety/pack/PackRegistryTest.java`, `PackJsonTest.java`, `HealthPackTest.java`

**Interfaces — Produces:**

```java
public interface PackDescriptor {
    String id();
    String displayName();
    String version();                       // HealthPack: the viscolink Implementation-Version; CorePack: the same
    SubjectIdentifier subject();
    List<ConsoleView> consoleViews();       // informational in M1 (applied in M2)
    List<String> frankOwnedPaths();         // path prefixes handed to the Frank!Framework's own chain
    Map<String, String> propertyDefaults(); // informational in M1 (applied in M2)
    List<String> deidentificationStrategyIds();
}
public record SubjectIdentifier(String sessionKey, String metadataName, String metadataLabel,
                                String displayLabel, Optional<Pattern> format) {}
public record ConsoleView(String name, String url, String target) {}

public final class CorePack implements PackDescriptor { /* id "core", "Core", subject subjectId/subjectId/SubjectId/Subject, no views, no paths, no defaults, no strategies */ }
public final class HealthPack implements PackDescriptor { /* id "health", "Healthcare", subject patientId/patientId/PatientId/Patient, consoleViews = the FHIR webservices view as DeploymentSpecifics declares it today, frankOwnedPaths = ["/fhir/"], propertyDefaults = {} in M1, strategies = ["fhir-patient", "hl7v2"] */ }

public final class PackRegistry {
    /** Pure: the resolution rule over any candidates (tested without ServiceLoader). */
    static PackDescriptor resolve(Collection<? extends PackDescriptor> candidates);
    /** ServiceLoader-backed, cached; what every consumer calls. */
    public static PackDescriptor get();
    /** Tests only: replace the cached descriptor; cleared with reset(). */
    static void override(PackDescriptor pack); static void reset();
}
public final class PackJson { public static String of(PackDescriptor pack); }   // the §4.4 shape, stable key order
```

- [ ] **Step 1: Tests first.** `PackRegistryTest`: `resolve` of an empty collection is a `CorePack`; of one descriptor is that descriptor; of two throws `IllegalStateException` whose message contains both ids; `get()` on this class path resolves `HealthPack` (the services file exists) and returns the same instance twice; `override`/`reset` work. `HealthPackTest`: every field of the subject and `frankOwnedPaths` equals the Global Constraints' values. `PackJsonTest`: the JSON of `HealthPack` parsed back (Jackson) has exactly the keys `id, displayName, version, subject, consoleViews, frankOwnedPaths, deidentificationStrategyIds` with `subject` keys `sessionKey, metadataName, metadataLabel, label, format`; `format` is `null` for both packs; `CorePack` serialises with empty arrays, not missing keys. RED.
- [ ] **Step 2: Implement.** `version()` reads `Implementation-Version` from the viscolink manifest the way `ViscoLinkModule.getModuleInformation` builds it (share the constant). Gates. Commit `feat(pack): the pack descriptor SPI, the core and health descriptors and the registry`.

---

### Task 2: Ladybug metadata from the registry

**Files:**
- Create: `…/pack/SubjectMetadataFieldExtractor.java`
- Modify: `viscolink/src/main/resources/springIbisTestToolVisco.xml`
- Test: `viscolink/src/test/java/com/viscosiety/pack/SubjectMetadataFieldExtractorTest.java`, `…/pack/LadybugWiringTest.java`

**Rules.** `SubjectMetadataFieldExtractor extends org.wearefrank.ladybug.metadata.SessionKeyMetadataFieldExtractor` and, in its constructor, sets `name = subject.metadataName()`, `label = subject.metadataLabel()`, `sessionKey = subject.sessionKey()` from `PackRegistry.get()`. In the XML the hardcoded `patientId` extractor bean becomes `<bean class="com.viscosiety.pack.SubjectMetadataFieldExtractor"/>` in the same list position, and the two `<value>patientId</value>` entries in the `metadataNames` lists become `<value>#{T(com.viscosiety.pack.PackRegistry).get().subject().metadataName()}</value>` (SpEL in a `<value>` is resolved by the F!F context's `StandardBeanExpressionResolver`; confirm by the wiring test). Comments in the XML say why.

- [ ] **Step 1: Tests first.** Extractor: with `PackRegistry.override(HealthPack)` the getters answer `patientId` / `PatientId` / `patientId`; with `CorePack`, `subjectId` / `SubjectId` / `subjectId`; `reset()` in `@AfterEach`. `LadybugWiringTest`: load `springIbisTestToolVisco.xml` in a minimal `GenericXmlApplicationContext` with the imported `springIbisTestTool.xml` replaced by a stub resource that defines the beans the file references (read the file to list them; mock what Ladybug needs), then assert the `metadataExtractor` bean's extractor list contains one `SubjectMetadataFieldExtractor` with `name = patientId`, and that the `metadataNames` bean contains `patientId` at the index it has today (count it in the current file before changing anything) — if the import cannot be stubbed in reasonable time, replace this test by one that parses the XML as a document and asserts the SpEL text and the bean class at the same positions, and say so in the report. RED.
- [ ] **Step 2: Implement.** Gates + `./mvnw -q install -pl viscolink`. Commit `feat(pack): Ladybug records the pack's subject identifier instead of a hardcoded patientId`.

---

### Task 3: F!F-owned paths from the registry

**Files:**
- Modify: `viscolink/src/main/java/com/viscosiety/security/ConsoleSecurityRegistrar.java`
- Test: `viscolink/src/test/java/com/viscosiety/security/ConsoleSecurityRegistrarTest.java` (extend)

**Rules.** `isFrankOwnedPath(path)` = the core prefixes (`/iaf/`, `/api/`, `/api-service/`) ∪ `PackRegistry.get().frankOwnedPaths()`. The `/fhir/` literal leaves the class; `HealthPack` carries it (Task 1). A pack path is validated once at registry resolution: must start and end with `/` (`IllegalStateException` otherwise, message names the pack and the path).

- [ ] **Step 1: Tests first.** Existing `fhirPathIsFrankOwned` keeps passing under `HealthPack` (the default on this class path); new: under `PackRegistry.override(CorePack)` `/fhir/x` is NOT F!F-owned while `/iaf/x`, `/api/x`, `/api-service/x` still are; a pack path without the trailing slash fails resolution. RED (the new ones).
- [ ] **Step 2: Implement.** Gates. Commit `feat(pack): the console hands a pack's paths to the Frank!Framework chain from the descriptor`.

---

### Task 4: ViscoFlow driven by the descriptor

**Files:**
- Modify: `viscolink/src/main/java/com/viscosiety/flow/FlowController.java`, `viscolink/src/main/webapp/flow/index.html`, `flow/js/api.js`, `flow/js/app.js`, `flow/js/render.js`, `flow/js/checkpoints.js`
- Test: `viscolink/src/test/java/com/viscosiety/flow/FlowControllerPackTest.java` (new; mock the servlet request/response as `ConfigRefServletTest` does)

**Rules.**
- `GET /flow-api/pack` → `PackJson.of(PackRegistry.get())`, `application/json`, same chain as the other `flow-api` calls (nothing added to security).
- `handleCombinedTraces`: the filter parameter is `subjectFilter`; `patientFilter` is read as an alias when `subjectFilter` is absent (one release, comment says so); the Ladybug `filterHeader` and the metadata name are `subject.metadataName()` — no `"patientId"` literal remains in the Java.
- JS: `api.js` exports `getPack()` (`GET ${BASE}/flow-api/pack`); `app.js` fetches it once before the first `getTraces` and keeps `pack.subject`; `getTraces` takes `subjectFilter` and uses `subject.metadataName` in `metadataNames` and as `filterHeader`; `render.js` reads `row[subject.metadataName]` for the column (the `td-patient`/`patient-id` CSS class names may stay); `index.html` keeps the `<th>` and the `<input>` but their text/placeholder are set at load from `subject.label` (the JSON key of `displayLabel`; `Patient` / `Patient ID…`; for the core `Subject` / `Subject ID…`); `checkpoints.js` builds `SESSION_META_KEYS` from the core keys plus `subject.sessionKey` (export a function `sessionMetaKeys(subject)` and keep `processCheckpoints` taking the set); `app.js`'s detail line uses `metaItem(subject.label, …)` reading `sessionMeta[subject.sessionKey]`. The on-screen result for the health pack must be identical to today.

- [ ] **Step 1: Tests first** (Java): `/flow-api/pack` answers the JSON with `id = health`; `subjectFilter=x` and `patientFilter=x` both produce a Ladybug URL with `filterHeader=patientId&filter=x` (assert on the URL the controller builds — expose the builder package-private or capture through the mocked fetch); under `CorePack` the header is `subjectId`. RED.
- [ ] **Step 2: Implement** Java and JS. `grep -n "patientId\|patientFilter\|Patient" viscolink/src/main/webapp/flow -r` must show only the CSS class names, the alias comment and nothing else.
- [ ] **Step 3:** Gates + `install -pl viscolink`. Commit `feat(pack): ViscoFlow takes the subject identifier's key and label from the pack`.

---

### Task 5: `/api-service/pack`

**Files:**
- Create: `viscolink/src/main/java/org/frankframework/visco/security/PackServlet.java`
- Test: `viscolink/src/test/java/org/frankframework/visco/security/PackServletTest.java`

**Rules.** `PackServlet extends AbstractBearerServiceServlet`: `getName() = "pack"` (the servlet manager reads `servlet.<name>.*`, so the name must match the `servlet.pack.*` properties), `getUrlMapping() = "/api-service/pack"`, `securityRolesProperty() = "servlet.pack.securityRoles"`, `elevatedRoles()` = the same set `ConfigRefServlet` uses for reads (read it), `doGet` → `PackJson.of(PackRegistry.get())`, `application/json`, `Cache-Control: no-store`; every other method 405. Registered exactly like `ConfigRefServlet` (a `DynamicRegistration.Servlet`; find where it is declared as a bean and declare this one beside it).

- [ ] **Step 1: Tests first** (mirror `ConfigRefServletTest`'s harness): no bearer → 401 with the base class's text; a bearer without the role → 403; with the role → 200, `application/json`, body parses to `id = health`; `PUT` → 405; the servlet is registered under `/api-service/pack` (whatever the existing tests assert for `ConfigRefServlet`'s registration). RED.
- [ ] **Step 2: Implement.** Gates + install. Commit `feat(pack): /api-service/pack serves the descriptor to bearer callers`.

---

### Task 6: Spike note, docs, live check, merge request

**Files:** `docs/design/2026-10-07-vertical-packs-design.md`, `CLAUDE.md`, `docs/design/README.md` (no change needed unless the table wording drifts)

- [ ] **Step 1: Spike (read-only, ≤ 1 h):** how can a pack jar on the overlay class path contribute `customViews.*` entries and property defaults at startup? Read how `AppConstants` loads `DeploymentSpecifics.properties` (first-found vs all), how `customViews.names` is consumed by the console, and whether a `PropertySource`/`AppConstants` hook exists that a `Module` can register. Write the finding (chosen mechanism, or "needs a small core hook: …") as the answer to the second bullet of §10 in the design doc, under a new sub-heading "M1 spike result".
- [ ] **Step 2: Docs.** Design doc status line: "M1 shipped: `com.viscosiety.pack.*`, `PackServlet`, `SubjectMetadataFieldExtractor`; health values still in-module (M2 moves them)". `CLAUDE.md` (viscoSuite): a short "Packs" section under Architecture — the SPI, the registry rule, the two endpoints, "the subject identifier is `patientId` for health; never hardcode it again, read `PackRegistry.get().subject()`", and that `consoleViews`/`propertyDefaults` are informational until M2.
- [ ] **Step 3: Commit.**
- [ ] **Step 4: Live check** (local): `./mvnw install -pl viscolink,viscostore && ./mvnw package -pl viscorunner`, then `cd viscorunner && docker compose -f docker-compose.viscolink.yml up --build`. In the console: ViscoFlow shows "Patient" as the column header, the placeholder `Patient ID…`, a trace's detail line `Patient`; `/viscolink/flow-api/pack` (logged-in browser) answers `id: health`; a bearer call to `/viscolink/api-service/pack` answers the same (the LOC stage runs without auth — then assert the 200 body and note that the auth paths are covered by the unit tests); the Ladybug "whitebox" view still has the `PatientId` column; one demo message through the fake-emr demo (or a Larva run) lands in Ladybug with its patient id. Screenshots for the MR. Stop the compose stack.
- [ ] **Step 5:** Push `feat/vertical-packs-m1`, open the merge request against `main` (after the spec MR merged; otherwise against the spec branch and retarget), title `feat(pack): the pack descriptor and the subject identifier (vertical packs M1)`.

---

## Self-review

**Spec coverage (M1 row of §9 + §4.2–4.4):** SPI and `CorePack` (Task 1); descriptor endpoint (Task 5) and ViscoFlow's own (Task 4); Ladybug metadata from the descriptor (Task 2); ViscoFlow column/filter/detail from the descriptor (Task 4); F!F-owned paths from the descriptor (Task 3); health values in an in-module `HealthPack` (Task 1); `consoleViews`/`propertyDefaults` present but applied in M2, with the spike recorded (Task 6); "existing tests green, ViscoFlow shows Patient from the descriptor, a unit test sees `subjectId` under the core" (Tasks 2–4, 6).

**Deviations from the spec to write back:** `SubjectIdentifier` carries both `metadataLabel` ("PatientId", Ladybug's column) and `displayLabel` ("Patient", ViscoFlow) — the spec's single `label` would have changed one of the two on-screen texts, against D8. ViscoFlow gets its own `/flow-api/pack` (console session) next to the bearer `/api-service/pack`.

**Type consistency:** `PackDescriptor`, `SubjectIdentifier`, `PackRegistry.get()/override()/reset()`, `PackJson.of()` (Task 1) are what Tasks 2–5 use; `HealthPack.frankOwnedPaths()` (Task 1) is what Task 3 reads; the JSON shape (Task 1) is what Tasks 4 and 5 serve.
