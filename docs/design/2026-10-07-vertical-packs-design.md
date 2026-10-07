# Vertical packs: a market-neutral core with the healthcare specifics as a pack

**Status:** Proposed (2026-10-07), M1 and M2 shipped (2026-10-07). Umbrella
design for viscoSuite, with the touch points in viscoForge and viscoFoundry
described at the interface level only. Implementation lands in milestones (§9);
this line names the shipped classes as they land. M1 shipped:
`com.viscosiety.pack.*`, `PackServlet`, `SubjectMetadataFieldExtractor`. M2
shipped: `packs/health` (artifact `viscolink-pack-health`, laid over the core as
an overlay zip), one `viscorunner/Dockerfile` with the build arguments `PACK`
and `STORE`, and the image tags `-health` and `-core`. M3 to M5 are not built.

## 1. Problem

viscoSuite is built as a healthcare integration platform: ViscoLink carries
FHIR facades, MLLP, HL7v2 pipes and validators, ViscoStore is a HAPI FHIR CDR,
the demo configurations are EMR-to-FHIR flows, and the one identifier the
tooling understands is a *patient id* — Ladybug stores it as report metadata,
ViscoFlow filters and shows it, viscoForge correlates journeys on it and knows
which fields to strip when a report is shared.

The runtime underneath — Frank!Framework, the console with OIDC, ViscoFlow,
Ladybug, the git class loader, the `api-service` servlets (config ref, agent
API, Larva), jdbc stores, the credential factory, hosted pages — is not
healthcare-specific at all. We want to sell the same platform into other
markets (public sector first) without forking it, and without shipping HAPI
FHIR to a tenant who integrates case files.

The one concept every market has is a *main subject identifier* for a
message: patient id in care, BSN (or another national id) in public sector.
Nothing else in the runtime is identifier-shaped.

## 2. Where "medical" lives today

This is the state the design started from; §4.1 shows where each piece lives since M2.

| Module | Healthcare-specific | Market-neutral |
|---|---|---|
| `viscolink` | `com.viscosiety.fhir.*` (facade servlets, providers, operation registry, metadata builder), `com.viscosiety.mllp.*`, pipes `FhirFormatPipe`, `FhirValidatorPipe`, `Hl7v2ToXmlPipe`, `XmlToHl7v2Pipe`; `springFhir.xml`, `springMllp.xml`; the `/fhir/` rule in `ConsoleSecurityRegistrar`; the `patientId` extractor + column in `springIbisTestToolVisco.xml`; FHIR keys in `DeploymentSpecifics.properties`; the HAPI dependencies in the pom; the console script `console/fhir-webservices.js` (the FHIR block on the Webservices page); ViscoFlow's "Patient" column, filter and detail line (`flow/js/*`, `FlowController.subjectFilter`, which kept `patientFilter` as an alias for one release) | Frank!Framework, console + OIDC (`ConsoleSecurityRegistrar`, `ApiSessionAuthListener`), ViscoFlow itself, Ladybug wiring, `GitClassLoader`, `api-service` servlets, `springStubbedRun.xml`, `ViscoLinkModule` |
| `viscostore` | the whole module (HAPI FHIR JPA server) | — |
| `util/hl7util` | the whole module | — |
| `viscorunner` | `demo-configurations/*` (fake-emr, fhir-*, hl7v2-to-fhir, demo-traffic), the viscostore WAR in the combined image, `init-databases.sql` | Tomcat setup, `catalinaAdditional.properties`, the overlay directory, the ROOT landing page, probes |
| viscoForge (sibling) | `DeidentificationService` + `deid/hl7v2-deid.xslt` (PHI rules on FHIR `Patient` and HL7v2), console vocabulary ("patient") | `ShareController` mechanics, journeys/KPIs/audit, ViscoFlow extensions |
| viscoFoundry (sibling) | demo config seeds, parts of the vendored knowledge, the stack names (`suite` = + FHIR store) | the rest |

The entanglement is shallow: one `Module` registers everything, one Spring file
holds the Ladybug metadata, one class holds the security paths, and ViscoFlow
reads one metadata key. That is what makes a pack split cheap.

## 3. Decisions

- **D1 — A pack is a Maven module that produces one jar, discovered at runtime
  through the Frank!Framework's own `Module` SPI.** ViscoLink already registers
  itself this way (`META-INF/services/org.frankframework.components.Module` →
  `ViscoLinkModule`, which lists its Spring files). A pack ships its own
  `Module` with its Spring files, pipes, listeners, senders and resources.
  No new class-loading mechanism.
- **D2 — Packs are baked into images, never dropped in at runtime.** One image
  per (runtime, pack). Immutable images keep castings, `BuildInfo` and the
  tenant "run anywhere" bundle meaningful; a hot-pluggable jar directory would
  need a restart anyway because custom pipes and Spring files load at boot.
- **D3 — Exactly one pack per image, or none.** The core boots alone (a
  market-neutral ViscoLink) and must be usable as a product. Two packs in one
  image is not a goal; a market that needs two is a new pack.
- **D4 — The pack declares itself in a descriptor the runtime serves.**
  Consumers (ViscoFlow, the Frank!Console views, viscoForge, viscoFoundry) read
  the descriptor instead of knowing the pack. The descriptor is not secret.
- **D5 — "Subject identifier" is the one cross-cutting concept the core owns.**
  The core defines it as *a session key whose value Ladybug records as report
  metadata and every UI calls by the pack's label*. The health pack maps it to
  `patientId`/"Patient"; the public-sector pack to `bsn`/"BSN". Configurations
  keep writing the session key the pack names, so existing tenant
  configurations do not change.
- **D6 — Image names stay; the pack is a tag suffix.** `viscolink:<v>` and
  `viscorunner:<v>` keep meaning "the health pack" until every consumer selects
  packs explicitly; `-core` and `-<pack>` suffixes are added for the others.
  No tenant breaks on the day the split lands.
- **D7 — ViscoStore belongs to the health pack.** It is a FHIR server; a
  public-sector store, if one is ever needed, is its own product in its own
  pack. `viscorunner` (the combined image) is therefore health-only by
  definition; the core and other packs ship as `viscolink` images.
- **D8 — The healthcare image must behave byte for byte as today after the
  split.** The split is a move, not a rewrite: the health pack's tests are the
  current tests, and the existing Larva scenarios and demo configurations run
  unchanged.

## 4. Architecture

### 4.1 Modules

```
viscolink/                      core WAR (artifactId stays `viscolink`): F!F, console+OIDC, ViscoFlow,
                                Ladybug wiring, GitClassLoader, api-service servlets, the pack SPI;
                                also installed as viscolink-<version>-classes.jar, which packs compile against
viscolink/demo-configurations/echo/   the core's one neutral demo (§4.6)
packs/health/                   artifact `viscolink-pack-health`: the jar and the overlay zip (§4.5)
  src/main/java/                com.viscosiety.{fhir,mllp,pipes} (moved, same packages), com.viscosiety.pack.health
  src/main/resources/           springFhir.xml, springMllp.xml, console/fhir-webservices.js,
                                DeploymentSpecifics.properties (the FHIR keys), ladybug/DatabaseChangelog_Pack.xml,
                                META-INF/services/* (the pack's two registrations)
  src/main/overlay/             WEB-INF/pack.properties: the pack's identity and landing-page texts
  demo-configurations/          what used to be viscorunner/demo-configurations
  util/{hl7util,fhirutil,demo}  moved from util/: hl7util is a standalone CLI module, the others are scripts and fixtures
viscostore/                     unchanged, referenced only by the health pack's images
packs/public/                   later: BSN subject, BSN de-identification rules, no store (skeleton in M5)
viscorunner/                    builds the images (§4.5); no domain code
```

The reactor order is `viscolink → packs/health → packs/health/util/hl7util → viscostore →
viscorunner`. `viscorunner` takes the WARs and the overlay zip from the local Maven
repository, not from the reactor, so the WARs and the pack are installed before it
packages.

- **The dependency runs one way.** The pack compiles against the core's classes
  artifact with scope `provided` (`viscolink` sets `attachClasses`) and uses exactly
  the SPI types `com.viscosiety.pack.{PackDescriptor,SubjectIdentifier,ConsoleView}`.
  Nothing in the core names the pack; nothing in the pack imports the core's servlets,
  ViscoFlow, Ladybug or security classes. A pack registers itself through its own
  `META-INF/services` files (the F!F `Module` and `PackDescriptor`) and nothing else;
  the core's own `PackDescriptor` services file is gone.
- **Packages.** A class in `com.viscosiety.pack` is the core's (that package has
  package-private test seams); a pack lives in `com.viscosiety.pack.<id>`, which is why
  the descriptor is `com.viscosiety.pack.health.HealthPack`. The moved code keeps its
  packages (`com.viscosiety.fhir`, `.mllp`, `.pipes`) because tenant configurations
  name the pipes (D8).
- **What the core tests without the pack.** The core cannot test-depend on the pack
  (the pack depends on the core's classes: a reactor cycle), so the core's D8 pins run
  against a test-tree mirror of the health values (`HealthValuesPack`), and in the core's
  own tests `PackRegistry.get()` resolves `CorePack`. The pack's `HealthPackTest` pins
  the real `HealthPack` to the same literal values; discovery through the services
  files is tested in the pack, where they are on the class path.
- **Leftovers that moved with the code.** The Xerces `DocumentBuilderFactory` services
  file went to the pack; the core still resolves Xerces through `ibis-xerces`'s own
  services file, so the pack's copy is redundant but harmless and the core's behaviour
  does not change. `hl7util` (artifactId `hl7util`) stays a standalone command-line
  module that nothing depends on and no image stages; it is health content and moved
  with the pack, nothing more.

### 4.2 The pack SPI (core)

```java
package com.viscosiety.pack;

/** Discovered through ServiceLoader; the core requires zero or one. */
public interface PackDescriptor {
    String id();                       // "health", "public"
    String displayName();              // "Healthcare", "Public sector"
    String version();                  // the jar's version
    SubjectIdentifier subject();       // §4.3
    List<ConsoleView> consoleViews();  // mirror of the pack's customViews.* property keys
    List<String> frankOwnedPaths();    // extra path prefixes handed to F!F's own chain (today: "/fhir/")
    List<String> deidentificationStrategyIds(); // names only; implementations live in viscoForge (§6)
}

public record SubjectIdentifier(String sessionKey, String metadataName, String metadataLabel,
                                String displayLabel, Optional<Pattern> format) {}
```

- **Two labels, not one.** `metadataLabel` is the title Ladybug gives the
  metadata column (`PatientId` with the health pack); `displayLabel` is the word
  ViscoFlow puts in its column header, its filter placeholder and its detail line
  (`Patient`). A single `label` would have changed one of the two on-screen texts,
  against D8. In the descriptor's JSON the display label is the key `label`.
- **Spring files, pipes, listeners, senders**: through the pack's own F!F
  `Module` (D1). The descriptor does not duplicate that.
- **`ConsoleSecurityRegistrar.isFrankOwnedPath`** becomes
  `core paths ∪ pack.frankOwnedPaths()`.
- **Console views**: a pack adds views through properties in its own jar, not by
  Java appending to `customViews.names` at startup. The core's
  `DeploymentSpecifics.properties` says
  `customViews.names=viscoLink,${pack.customViews.names:-}`; a pack sets
  `pack.customViews.names` and its `customViews.<name>.*` keys, and `consoleViews()`
  mirrors them for consumers. The health pack has no console view (`consoleViews()`
  is empty, see §4.4), so M2 only put the indirection in place. Deployers that set
  `customViews.names` themselves (the compose files, viscoFoundry's manifest renderer
  and casting bundle, viscoForge's composes) were left alone and bypass the
  indirection; the ViscoStore view is theirs by design, because it depends on `STORE`,
  not on the pack. A pack with a real view makes every such deployer list it (§10).
- **Console scripts** (the FHIR block on the Webservices page) are not views. A
  pack's `console/*.js` ride on its class path; the `<script>` tag that loads them is
  part of the pack's overlay, built into a copy of the console's `index.html` (§4.5).
  The SPI has no `consoleScripts` field.
- **Property defaults**: a pack ships them as the `DeploymentSpecifics.properties`
  of its jar; the Frank!Framework merges every copy on the class path, so a
  tenant's `StageSpecifics`/env still win. M2 removed `propertyDefaults()` from the
  SPI: no hook could apply a Java map early enough (§10), so a mirror of the file
  would only document it. **The rule: a pack's file only adds keys the core's file
  does not define.** On a clash the order of Tomcat's resource sets decides, and
  that order is not a contract; `HealthDefaultsTest` pins the rule for the health
  pack. Its file carries the MLLP identity, the FHIR target version, the ViscoStore
  base URL and the MR naming-system fallback.
- **Ladybug column**: the column of a pack's `metadataName` ships in the pack
  (`ladybug/DatabaseChangelog_Pack.xml`), the core owns `subjectid`; the rule is in
  §4.3, where the Ladybug wiring is described.
- **No pack**: a built-in `CorePack` descriptor (`id = "core"`, subject
  `subjectId`/"Subject", no views, no extra paths) keeps every consumer total.

### 4.3 The subject identifier

Today `springIbisTestToolVisco.xml` hardcodes a `SessionKeyMetadataFieldExtractor`
for `patientId` and lists `patientId` in `metadataNames`. The core replaces
the two literals with beans built from `PackDescriptor.subject()`:

- extractor `name = metadataName`, `label = metadataLabel`, `sessionKey = sessionKey`
  (the class `SubjectMetadataFieldExtractor`, which reads the registry in its
  constructor);
- `metadataNames` gets `metadataName` in the position `patientId` has now (a SpEL
  expression, `#{T(com.viscosiety.pack.PackRegistry).get().subject().metadataName()}`,
  in the default and the Shareable column lists).

ViscoFlow (`flow/js/*`, `FlowController`) reads `subject` from the descriptor
endpoint (§4.4): the column header, the filter placeholder and the detail line
use `displayLabel`; the metadata column and the Ladybug `filterHeader` use
`metadataName`. The query parameter becomes `subjectFilter`; `patientFilter`
stays as an alias for one release.
`SESSION_META_KEYS` is built from the descriptor plus the core keys.

The health pack declares `patientId` as session key and metadata name,
`PatientId` as metadata label and `Patient` as display label, so the Ladybug
column, the stored metadata and every existing configuration's
`PutInSessionPipe` keep working with no change. The public pack declares
`bsn` / "BSN" / `bsn` with a format (eleven-proof) the UIs may use to mark an
invalid value.

**The Ladybug column.** Ladybug stores the metadata in a lowercase column of the
`LADYBUG` table named after `metadataName`, and the column must exist or Ladybug's
queries fail. The core owns `subjectid` (the `CorePack` name): changeSet
`LadybugCustom:5` of `ladybug/DatabaseChangelog_Custom.xml`, guarded by a
`columnExists` precondition. A pack owns the column of its own `metadataName`: the
core's changelog ends with `<include file="ladybug/DatabaseChangelog_Pack.xml"
errorIfMissing="false"/>` and the pack ships that file. The health pack's
`LadybugHealth:1` adds `patientid` under a negated `columnExists` precondition
(`MARK_RAN`), because a health database got the column from the core's old changeSet
`LadybugCustom:2`, which M2 removed from the core's file: such a database is left
alone, a fresh one gets the column. Without the core's own column a core image could
not store a single report, since its `metadataNames` carry `subjectId` (the SpEL
entries above). A pack-less image logs one warning for the missing optional include
and nothing else. A new pack adds its column the same way, in its own changelog, and
never edits the core's. Proven on a fresh Postgres in the M2 docker proofs (core only:
`subjectid` and that one warning; health: both columns) and, for a database that had
already run `LadybugCustom:2`, on a scratch H2 database (the pack's changeSet is marked
as run).

### 4.4 The descriptor endpoints

Two endpoints serve the same JSON (`PackJson`); both sit on an authenticated
chain, so nothing about the pack is public and no secret belongs in it:

- `GET /viscolink/api-service/pack` — bearer JWT only (`PackServlet`, built on
  `AbstractBearerServiceServlet` like `ConfigRefServlet`), for the portal and
  agents. The servlet's name is `pack`, so its settings are `servlet.pack.*`: a
  deployment that wants bearer auth on it sets `servlet.pack.authenticator` and
  `servlet.pack.securityRoles` (viscoFoundry's manifest renderer will in M4; this
  is the only place the names are documented). Without `servlet.pack.securityRoles`
  the servlet fails closed with a 401.
- `GET /viscolink/flow-api/pack` — inside ViscoFlow's console session
  (`FlowController`), for ViscoFlow's own JS, which reads it once at load. No
  `servlet.*` setting is involved.

What a health image answers (`consoleViews` is empty because the FHIR UI is not a
`customViews` entry, see §10; the display label is the key `label`; `format` is the
pattern's source or `null`; `version` is the jar's, here a snapshot build):

```json
{ "id": "health", "displayName": "Healthcare", "version": "1.0.0-SNAPSHOT",
  "subject": { "sessionKey": "patientId", "metadataName": "patientId",
               "metadataLabel": "PatientId", "label": "Patient", "format": null },
  "consoleViews": [],
  "frankOwnedPaths": [ "/fhir/" ],
  "deidentificationStrategyIds": [ "fhir-patient", "hl7v2" ] }
```

The ROOT landing page prints the pack id and version next to the build time
(stamped at image build, §4.5), and `/pack` answers the same, so an operator can
tell what an image is. `BuildInfo` is not changed: castings keep baking it as
they do, and the stamp plus `/pack` are enough.

### 4.5 Images: how viscorunner builds them

`viscorunner` stays the image builder and carries no domain code. It gains two
build arguments and a Maven staging step.

**The pack is an overlay zip.** The health pack's build publishes, next to its jar,
a Maven `zip` artifact with the classifier `overlay`
(`com.visco:viscolink-pack-health:zip:overlay`). Its tree is exactly what lands under
`/opt/frank/webapp-overlay/viscolink/`:

- `WEB-INF/lib/` — the pack jar plus the runtime jars the core WAR lacks (HAPI, …);
- `WEB-INF/classes/console/index.html` — the Frank!Console page with the pack's
  `<script>` tag;
- `WEB-INF/pack.properties` — id, display name, version and the landing-page texts.

The libraries are Maven's own runtime closure of the pack's pom, staged by
`dependency:copy-dependencies -DincludeScope=runtime` into
`packs/health/target/overlay-libs` and zipped together with the pack jar. The assembly
plugin's `dependencySet` is not used for them: it re-resolves the direct dependencies
itself and ignores a `provided` declaration (measured: the same 115 jars before and
after 25 declarations).

**Maven staging (`viscorunner/pom.xml`).** It copies `viscolink.war`, `viscostore.war`,
the drivers and the runner jar into `target/`, as before. It also stages, from nothing
each time (so a jar a pack lost cannot linger in an incremental build):

- `target/packs/health/` — the overlay zip, unpacked (the zip dependency carries a
  wildcard exclusion: it is a bag of files, and without it the pack's transitives, about
  190 MB, would be shaded into the runner jar) — and `target/packs/core/`, an empty
  `WEB-INF/lib` plus the core's own `pack.properties` (`viscorunner/src/packs/core/`,
  the version filled in).
- `target/demo/{health,core}/` — `packs/health/demo-configurations/` and
  `viscolink/demo-configurations/`.
- `target/store/viscostore/viscostore.war` or `target/store/none/` (empty).

**The class-path rule (D8).** The core WAR's libraries plus the overlay's are exactly
the libraries the one WAR shipped before the split, and no jar is in both (a second copy
would put duplicate classes on the webapp class loader). The oracle is `HealthClasspathIT`
in `viscorunner`, against `src/test/resources/golden/health-webapp-libs.txt`: 430 jars,
written before the first line moved and frozen (edited by hand if ever, never
regenerated). The WAR has 361, the overlay 69 plus the pack's own jar, which the
comparison leaves out. What keeps it true:

- the pack marks everything the WAR already ships `provided`: 27 libraries, of which 18
  are in the WAR at the same version, 6 are ones where HAPI would pull another version
  than the WAR's, and 3 are ones whose version the core now pins;
- a direct `provided` declaration drops the artifact and its subtree from the overlay, so
  the two children of `guava` that the golden lists (`listenablefuture`,
  `j2objc-annotations`) are declared in the pack explicitly, with compile scope;
- `viscolink:classes` carries one `io.netty:*` exclusion: Maven resolves netty, reached
  only through a core dependency (`protonj2-client`), with scope runtime, which would put
  nine netty jars the WAR already ships into the overlay;
- four class-path pins in the core's `dependencyManagement` (`caffeine` 3.1.8, `gson`
  2.12.0, `opentelemetry-api` 1.44.1, `error_prone_annotations` 2.36.0): the versions the
  image class path has always carried, which Maven would otherwise move now that HAPI is no
  longer in the WAR's graph.

The rule for a pack's author: never add a `compile` dependency the WAR ships; the IT names
the jar that is in both. The IT is also the only drift detector for the pins; revisit them
at a deliberate dependency review (the next Frank!Framework bump is one).

**One Dockerfile** replaces `Dockerfile` and `Dockerfile.viscolink`; it is the old suite
`Dockerfile` with two build arguments. `Dockerfile.viscostore` stays for the standalone
store image.

```dockerfile
# Two build arguments choose the variant. Plain ARG lines: a trailing comment would be read
# as more argument names.   PACK = health | core      STORE = viscostore | none
ARG PACK=health
ARG STORE=viscostore
# …unchanged: Tomcat base, user, catalinaAdditional.properties, drivers, the ROOT landing page…
COPY --chown=tomcat conf/context-${STORE}.xml    /usr/local/tomcat/conf/context.xml
COPY --chown=tomcat conf/server.xml              /usr/local/tomcat/conf/server.xml
COPY --chown=tomcat target/viscorunner-*.jar     /opt/frank/lib/
COPY --chown=tomcat target/packs/${PACK}/        /opt/frank/webapp-overlay/viscolink/
COPY --chown=tomcat target/demo/${PACK}/         /opt/frank/demo-configurations/
COPY --chown=tomcat target/viscolink.war         /usr/local/tomcat/webapps/viscolink.war
COPY --chown=tomcat target/store/${STORE}/       /usr/local/tomcat/webapps/
# the ROOT page is stamped from the overlay's WEB-INF/pack.properties (below)
```

`COPY` cannot be conditional, hence the empty directories (`target/store/none/`, the
core's empty `WEB-INF/lib`). GitLab's artifact zip may not keep empty directories, so
the image job recreates both before it builds. The stale-stub size check stays for the
WAR; the store check runs only when `STORE != none`. The two arguments are independent
in the recipe; D7 is a publishing rule: CI builds only the combinations in the table
below.

**What one recipe changes for `viscolink`.** The store-less `viscolink` image gains what
the suite image always had: `conf/server.xml` (with the `ContextFailureEventPublisher`
listener) and the shaded runner jar on `/opt/frank/lib`. Both are no-ops outside
Kubernetes (the listener then logs that event publishing stays disabled) and wanted
inside it (a Kubernetes event when a context fails to start). The Tomcat context file is
`conf/context-${STORE}.xml`: `context-viscostore.xml` (the suite's old file, with
`jdbc/viscostore`) and `context-none.xml` (the store-less old file), so a `STORE=none`
image still has no `jdbc/viscostore` resource.

**Identity and the landing page.** `WEB-INF/pack.properties` carries `pack.id`,
`pack.displayName`, `pack.version`, `pack.tagline` and `pack.linkBlurb`. At image build
the Dockerfile fills three placeholders of the ROOT page from it: `%%PACK_TAGLINE%%`,
`%%PACK_LINK_BLURB%%` and `%%PACK_STAMP%%` (` · pack health 1.0.0-SNAPSHOT`, in front of the
build time). A health image therefore renders today's texts and a core image neutral
ones. The values must not contain `|`, `&` or a backslash (the `sed` delimiter and
replacement metacharacters); `DockerfileContractTest` pins that for both files. The
standalone store image fills the same placeholders with its fixed texts.

**The console script.** The FHIR block on the Frank!Console's Webservices page is a
script (`console/fhir-webservices.js`, a resource of the pack jar) that needs a
`<script>` tag in the console's `index.html`. M2 took the build-time option of §10: the
pack's build extracts the framework's `console/index.html`, adds
`<script src="fhir-webservices.js"></script>` before `</body>`, and the overlay carries
the result as `WEB-INF/classes/console/index.html`. Tomcat's webapp class loader searches
`WEB-INF/classes` before every `WEB-INF/lib` jar, whatever the order of the resource
sets, so the patched page shadows the one inside the console jar deterministically, and
the health console loads the script with the same tag and the same timing as before (D8).
A core image has no overlay page and serves the framework's own. The alternative, one
core-owned loader script that appends the scripts a descriptor field lists, would keep a
pack jar drop-in but changes when the script loads and needs a new SPI field; it was not
taken. `ConsoleIndexPatchTest` pins the patch in the pack's build.

**Why the overlay lib directory.** `/opt/frank/webapp-overlay/viscolink/` is
already a `PreResources` set of the `/viscolink` context, so jars under its
`WEB-INF/lib/` load in the webapp class loader — the same loader as the WAR's
own `WEB-INF/lib`. That is what a pack needs: its
`META-INF/services/org.frankframework.components.Module` is found by the
Frank!Framework's ServiceLoader, its Spring files by the class path, its pipes
by the configuration digester. `/opt/frank/drivers` and `/opt/frank/lib` sit on
Tomcat's common loader and die with `NoClassDefFoundError` on Frank!Framework
classes (the lesson of the viscoForge jar). `plugins.directory` is left as it
is. The same resource set serves the pack's `WEB-INF/classes/`, which is how the
console page above is replaced. The core WAR stays one published artifact that no
pack rebuilds, and viscoForge keeps layering on top of any pack image the way it
does today.

The alternative — one WAR per pack, a Maven `war` module per pack overlaying
the core WAR — is standard Maven too, but multiplies published WARs and puts
the Forge overlay on top of a pack-specific WAR. Rejected in favour of the
overlay lib.

**CI.** One job template (`.docker-build`) with three variable sets replaces the three
hand-written image jobs: per variant an amd64 job and an arm64 job, then a `manifest`
job per tag set, as before.

| `PACK` | `STORE` | Image | Tags |
|---|---|---|---|
| health | viscostore | `viscorunner` | `<sha>`, `latest` (today's meaning, kept), `<sha>-health`, `latest-health` |
| health | none | `viscolink` | `<sha>`, `latest` (today's meaning, kept), `<sha>-health`, `latest-health` |
| core | none | `viscolink` | `<sha>-core`, `latest-core` |
| public | none | `viscolink` | `<sha>-public`, `latest-public` (M5) |

CI publishes no version tags, only the commit (`<sha>`) and `latest`; wherever this
document writes `<v>` for an image tag it means those. The `-health` tags are extra names
for the same multi-arch manifest as the unsuffixed ones, not extra builds. The per-arch
intermediates carry the same suffixes (`<sha>-core-amd64`) and the cleanup job deletes
them once the manifests are pushed. The `package` job hands the staged `target/packs/`,
`target/demo/` and `target/store/` to the image jobs as artifacts. viscoForge keeps
building `FROM viscorunner:<sha>` and `viscolink:<sha>`, triggered with the commit as
`UPSTREAM_TAG` as before; nothing on its side changed.

`viscorunner` keeps its name for the health suite only; a public-sector image
with a store, if one is ever needed, gets its own name rather than a
`viscorunner-public`. The standalone `viscostore` image is unchanged.

**Compose files.** `docker-compose.yml` passes `PACK=health STORE=viscostore` (the
suite), `docker-compose.viscolink.yml` passes `PACK=health STORE=none` and the new
`docker-compose.core.yml` passes `PACK=core STORE=none`; the demo overlay still
bind-mounts the health demo set over `/opt/frank/configurations`. The suite's Postgres
runs `init-databases.sql`, which creates the `ladybug` database among others. The two
store-less composes only create `viscolink`, so they mount `postgres/init-ladybug.sql`
for Ladybug's own database; without it the context never starts. The file is idempotent
(`\gexec` runs the `CREATE DATABASE` only when the database is missing, because it has
no `IF NOT EXISTS`): the suite compose mounts the whole `postgres/` directory, so both
files run there, and a plain statement would abort its first start.

**At runtime nothing new happens.** Tomcat starts `/viscolink`, the pack's
`Module` registers its Spring files, the core's descriptor loader finds the
one `PackDescriptor` (or `CorePack`), Ladybug metadata and ViscoFlow labels
follow it. Probes, the `wait-for-keycloak` gate and the portal's manifests see
the same context paths as today. viscoFoundry picks an image by tag (its
catalogue row) and can verify it through `/pack` and the ROOT page; castings
`FROM` the chosen image with the bake script unchanged.

### 4.6 Demo configurations and knowledge

Demo configurations live under the pack that owns them. The health set is
`packs/health/demo-configurations/` (it was `viscorunner/demo-configurations/`), and the
core ships one neutral demo, `viscolink/demo-configurations/echo/`: an adapter behind
`POST /viscolink/api/echo?subjectId=…` that echoes the body and records the subject in the
session, so "create an instance with demo content" works on a core image and Ladybug and
ViscoFlow show the Subject column. Both sets are also baked into the images at
`/opt/frank/demo-configurations/` (`target/demo/<pack>/`) and are never loaded unless
mounted over `/opt/frank/configurations`; the demo compose mounts the health set from the
repository. The pack descriptor's id is what viscoFoundry uses to pick a demo set and the
knowledge/pattern documents it vendors (§7).

## 5. What does not change

Keycloak clients and roles, the console's OIDC chain, the bearer-only
servlets, `GitClassLoader` and `run_draft`, Larva, Ladybug itself, the jdbc
stores and the data explorer, the credential factory, hosted pages, the
Kubernetes lifecycle events, castings (the bake script is F!F-level), the
tenant "run anywhere" bundle (it names an image; the image carries the pack).

## 6. viscoForge (interface level)

- `DeidentificationService` becomes a registry of `DeidentificationStrategy`
  implementations (ServiceLoader), each named by an id the pack descriptor
  lists; today's FHIR-`Patient` and HL7v2 rules become the two strategies of
  the health pack's companion jar `viscoforge-pack-health`. A report is
  de-identified by every strategy the running pack names; a pack without
  strategies shares nothing (the share endpoint answers 409 with a fixed text).
- The Forge console takes its vocabulary (the "Patient" column, filters,
  journey correlation key) from the descriptor endpoint, never from a literal.
- The Forge image is built `FROM` the matching viscoSuite image, so the
  Forge/standard "edition" axis stays orthogonal to the pack axis.

## 7. viscoFoundry (interface level)

The portal stops modelling instances as `stack ∈ {suite, viscolink}` and gets a
**product catalogue**: `(runtime, pack, edition)` → image reference, store
(none / FHIR), demo set, knowledge directories, eval scenarios, assistant tool
availability (the share tool only when the pack lists a strategy), console
links. The catalogue is the portal's own concern and its design, including
the commercial "edition" (open-source vs Forge), lives in the viscoFoundry
repository; viscoSuite only guarantees the descriptor endpoint and the image
tags above.

## 8. Alternatives rejected

- **Feature flags in the monolith** (`fhir.enabled=false`): HAPI and hl7util
  stay in every image (size, CVE surface, licence noise), the vocabulary stays
  medical, and a second market gets nothing reusable.
- **Runtime plugin directory** (`/opt/frank/plugins` watched at boot): flexible
  on paper, but every pack needs Spring files and pipe classes at context
  start, so it is a restart either way; it also breaks the immutable-image
  story castings and bundles rely on, and the jar placement pitfall above
  bites every tenant instead of our CI once.
- **One pack per market as a fork of viscoSuite**: the runtime fixes diverge
  within weeks; rejected without discussion.
- **A generic "store" abstraction now**: nothing but FHIR needs a store today;
  the public-sector target is not decided. D7 keeps the door open (a pack may
  ship a store) without inventing an interface for one customer.
- **Renaming `patientId` to `subjectId` in Ladybug metadata**: would force
  every tenant configuration and stored report to change; D5 keeps the key
  per pack and makes the *label* the abstraction.

## 9. Milestones

| M | Scope | Proof |
|---|---|---|
| M1 | `PackDescriptor` SPI + `CorePack` + the descriptor servlet; Ladybug metadata and ViscoFlow driven by the descriptor; the health values live in a `HealthPack` descriptor inside `viscolink` (no module split yet) | existing tests green; ViscoFlow shows "Patient" from the descriptor; a unit test boots the core with `CorePack` and sees `subjectId` |
| M2 | The module split (`packs/health`, hl7util moved, demo configurations moved); `viscorunner` image matrix (`PACK`, `STORE`); CI tags | Shipped 2026-10-07. `HealthClasspathIT` pins the class path (WAR ∪ overlay = the one WAR's 430 jars); the launcher ITs run the health pack from the overlay; local docker proofs of the health, suite and core stacks (below). Larva + demo flows on a tenant instance are a rollout step after the merge |
| M3 | viscoForge: strategy registry + `viscoforge-pack-health`; console vocabulary from the descriptor | a shared report on a health instance is de-identified exactly as today (existing tests); on a core instance sharing answers the fixed refusal |
| M4 | viscoFoundry: product catalogue (replaces `stack`), edition axis, demo/knowledge per pack | an instance created from a core image, and one from a Forge-health image, both RUNNING through the portal |
| M5 | `packs/public` skeleton: BSN subject with format, BSN de-identification strategy, one demo adapter | `viscolink:<v>-public` boots; ViscoFlow filters on "BSN"; a shared report masks BSNs |

M1 and M2 are the viscoSuite work and can ship independently of the siblings:
M1 changes nothing a consumer sees, M2 keeps today's image tags meaning what
they mean now.

**M2 proof, 2026-10-07 (local docker, three stacks).** *Health*
(`docker-compose.viscolink.yml`): the webapp starts once `init-ladybug.sql` has created
the `ladybug` database; `/flow-api/pack` answers `health` and `/api-service/pack`
answers 401 (it fails closed without its servlet settings); the overlay holds 70 jars and
the console page; an HL7v2 ADT A04 message posted to the HTTP listener answers 200 and
Ladybug stores its `patientId`; the FHIR facade list and a facade `metadata` call answer
200; the console page carries the script tag and its Webservices page shows the
"Available FHIR Facades" block and the footer line; the ROOT page carries today's texts
plus the pack stamp; Liquibase ran `LadybugCustom:5` and `LadybugHealth:1`; no exception
in the log. *Suite* (`docker-compose.yml`): both WARs deploy, `/viscostore/fhir/metadata`
answers 200, the context has `jdbc/viscostore`, and `init-databases.sql` followed by
`init-ladybug.sql` runs without error. *Core* (`docker-compose.core.yml`):
`/flow-api/pack` answers `core` with the label "Subject"; the FHIR paths answer 404; the
console page has no script tag; the overlay's `WEB-INF/lib` is empty (the empty directory
survives the `COPY`); only the `ROOT` and `viscolink` webapps exist and the context has no
`jdbc/viscostore`; the ROOT page carries the neutral texts and the pack stamp;
`LadybugCustom:5` ran and the missing optional pack changelog is one warning; the echo
demo starts, `POST /viscolink/api/echo?subjectId=S-42` answers 200, Ladybug stores the
row under `subjectid`, and ViscoFlow shows it under "Subject".

**What that proof does not cover.** The CI matrix is linted and its merged YAML compared,
but a real run is the first pipeline on `main`: the six image builds finding the restored
staging directories, the `-health` and `-core` manifests, the cleanup, the viscoForge
trigger, and the arm64 runner now building four images. The Liquibase path for a database
that already ran `LadybugCustom:2` was exercised on a scratch H2 database, not on a
Postgres a health tenant has been using.

**M2 rollout, after the merge.** The pipeline on `main` publishes the new
`viscorunner:<sha>` and `viscolink:<sha>` (and the `-health` and `-core` tags) and
triggers viscoForge, which rebuilds `FROM` them. One stop/resume of a tenant instance
pulls the new Forge image (`:latest`, pull Always). The M2 proof on a tenant instance is
viscoFoundry's existing Larva evaluation scenario on its dedicated evaluation instance
plus the demo flows on a demo instance: the health image must behave exactly as before.
viscoFoundry's M4 adds the catalogue row for `viscolink:<sha>-core`; until then nothing in
the portal selects a core image.

## 10. Open questions

- The public-sector pack's standards (StUF/ZGW, Digikoppeling, Haal Centraal)
  and identity (DigiD/eHerkenning) are undecided; M5 deliberately ships only
  the subject identifier and de-identification so the pack exists before the
  first adapter does.
- Whether the Frank!Console's `customViews` mechanism can take views from a
  jar on the overlay class path at startup, or needs the properties written
  into `DeploymentSpecifics` at image build time — to verify in M1's plan.
  Answered by the M1 spike, below.

### M1 spike result

**Verdict.** Neither needs a Java hook in the core. Property defaults ride on the
`DeploymentSpecifics.properties` of the pack's own jar. Extra console views ride
on properties too, plus a one-line indirection in the core's file. A console
*script* (what the FHIR UI is) is the one thing that needs a step in M2. This was
read with `javap` in the Frank!Framework version `viscolink/pom.xml` pins
(`10.3.0-20260924.042323`) and then run: the real `AppConstants` against small
test jars, and an embedded Tomcat with an overlay shaped like the runner's. No
application was started.

**The property chain** (`org.frankframework.util.AppConstants` and `PropertyLoader`,
in `frankframework-commons`). `PropertyLoader.load` calls
`ClassLoader.getResources(name)`: every copy on the class path, not the first.
It reverses the list and loads each, so the copy earliest on the class path is
loaded last and wins a clash on the same key. `AppConstants` loads
`AppConstants.properties` and then the files named by `ADDITIONAL.PROPERTIES.FILE`
(set in the `frankframework-core` jar): `DeploymentSpecifics`, `BuildInfo`,
`ServerSpecifics_*`, `SideSpecifics_*`, `StageSpecifics_<stage>`, `Test`, later
wins, each through the same all-copies path. A read asks the environment and the
system properties before any file. The global instance and the per-configuration
instances both do this, so a pack's keys reach configurations too.

- A jar's `DeploymentSpecifics.properties` is therefore merged next to the WAR's
  (`viscolink/src/main/resources/DeploymentSpecifics.properties`, which ships in
  `WEB-INF/lib/viscolink-*.jar` because the pom sets `archiveClasses`): just
  another copy.
- Run against the real classes, two jars with one file each: both loaded; a key in
  one visible globally and per configuration; a key in both resolved to the jar
  first on the class path; `-D` beats both.
- The overlay is a `PreResources` set. In embedded Tomcat 11.0.18 (the image runs
  11.0.14, and the image itself was not run) a webapp loader with the same overlay
  lists the overlay jar *before* the WAR's `WEB-INF/lib` jars, so on a clash a
  pack's value beats the core's. That order is Tomcat's, not a contract: a pack's
  file must only *add* keys the core does not define.

**How the console consumes `customViews`.** The console frontend asks
`GET /iaf/api/environmentvariables` once, when the console page loads
(`console.controllers.EnvironmentVariables`, then the bus endpoint of the same name in
`frankframework-core`). The answer is the keys of the live global `AppConstants`
with resolved values. The sidebar component then reads `customViews.names`
(comma-separated) and, per name, `customViews.<name>.{name,url,target}`; a name
without `name` or `url` is skipped. So it is read per console page load from the
live properties, not once at JVM start.

- Per-view keys from a pack's jar merge fine, they are distinct keys.
  `customViews.names` is one value: two files that define it do not concatenate,
  one wins (run, both orders).
- What works with no Java: the core's file says
  `customViews.names=viscoLink,${pack.customViews.names:-}` (the `${key:-default}`
  syntax of `StringResolver`) and a pack's jar sets `pack.customViews.names` and
  its `customViews.<name>.*`. Run: with the pack the console list is
  `viscoLink,fhir`; without one it is `viscoLink,`, and the empty name is skipped
  by the frontend (it needs name and url).
- **Caveat: deployers set `customViews.names` themselves.** The environment beats
  every file (see the property chain above), and `viscorunner/docker-compose.yml`,
  viscoFoundry's manifest renderer and its casting bundle all set
  `customViews.names` through the environment or a property of their own. In such a
  deployment the core file's `viscoLink,${pack.customViews.names:-}` is never
  consulted, so a pack's views would not show. M2 left those deployers as they
  are: the health pack has no console view, and the ViscoStore view is the
  deployer's by design (it depends on `STORE`, not on the pack). **Standing rule for
  M5:** the first pack with a real console view must be listed by every deployer that
  sets `customViews.names` itself (each lists the names it wants, pack views
  included), or that deployer switches to the indirection.

**Startup hooks.** `org.frankframework.components.Module` has two default methods,
`getModuleInformation()` and `getSpringConfigurationFiles()`: no property hook.
`ComponentLoader` finds modules with `ServiceLoader.load(Module.class)` (all of
them) when `IbisApplicationContext` builds the Spring context, which is after the
global `AppConstants` exist and `SPRING.CONFIG.LOCATIONS` was read from them; it
then registers each module's version with `AppConstants.setGlobalProperty`. That
static method reaches every existing and future instance (run), so a Java hook is
possible, but it would run after the early readers: a worse place for defaults
than the files. `AppConstants` is its own `Properties` subclass and does not read
Spring's `Environment`, so there is no `PropertySource` seam either.

**First found versus all.** All copies: the properties files above and the
`ServiceLoader` services. First found only: a Spring file named by
`getSpringConfigurationFiles()` (`ClassLoader.getResource`) and everything under
`console/` that `ConsoleFrontend` serves (`ClassUtils.getResourceURL`, also
`getResource`).

**What the FHIR UI really is.** Not a `customViews` entry and not a tab:
`viscolink/pom.xml` extracts the console's `index.html` from the frontend jar,
adds `<script src="fhir-webservices.js">` before `</body>` and stages the result as
`WEB-INF/classes/console/index.html`. The script (`console/fhir-webservices.js`, a
resource of the viscolink jar) injects an "Available FHIR Facades" block into the
console's Webservices page and a footer line. A pack's `console/*.js` is served as
it stands (first found on the class path); the `<script>` tag is the problem,
because `index.html` is one file that never merges, and the pom patch is fixed
when viscolink is built, before any pack jar is known. M2 moved the patch into the
health pack's own build (§4.5).

**Recommendation for M2.**

- `propertyDefaults`: no hook. The pack's defaults are the
  `DeploymentSpecifics.properties` of its jar (the FHIR keys of viscolink's file
  move with the health pack). Drop `propertyDefaults()` from the SPI, or keep it as
  a mirror that documents what the file sets; applying a Java map would need the
  hook above. Tenants' `StageSpecifics`, environment and system properties still win.
- `consoleViews`: the indirection above in viscolink's `DeploymentSpecifics.properties`
  and the pack's `pack.customViews.names` plus `customViews.<name>.*`.
  `consoleViews()` becomes the mirror of those keys for consumers.
- A console script such as the FHIR block, either (a) a build-time step in
  `viscorunner`'s staging that patches `WEB-INF/classes/console/index.html` once
  the pack jar is staged, or (b) one core-owned loader script, added to
  `index.html` at viscolink's build as today, which reads the descriptor from
  the console-session endpoint and appends `<script>` tags for the scripts the
  descriptor lists (a `consoleScripts` field, new in M2). (b) keeps a pack jar
  drop-in and the pom patch unchanged; it needs a live check in the console.

**Taken in M2.** `propertyDefaults()` was dropped from the SPI outright, without a
mirror (§4.2). `consoleViews` rides on the indirection, which the health pack does not
use (§4.2). The console script took option (a), but as a step of the pack's own build
and the pack's overlay rather than of `viscorunner`'s staging, so `viscorunner`
carries no patching logic; `consoleScripts` was not added to the SPI (§4.5).

**Rollout of `servlet.pack.*`.** `PackServlet` answers only with
`servlet.pack.authenticator` and `servlet.pack.securityRoles` set (§4.4). viscoFoundry's
manifest renderer will render them in M4 the way it renders `servlet.configRef.*` today:
a new instance gets them at creation, and an instance created earlier only at its next
stop/resume, when the renderer runs again. Until then
`/api-service/pack` answers 401 to everyone, because the servlet fails closed without
its roles. ViscoFlow's `/flow-api/pack` needs no setting and works at once.

Closed in M2:

- Whether `BuildInfo` should carry the pack id (castings) or the ROOT page alone is
  enough: the ROOT page, stamped from the pack's `pack.properties` at image build, plus
  `/pack` are enough, and `BuildInfo` is not changed. A casting keeps baking `BuildInfo`
  as before and inherits its pack from the image it is built `FROM`.
