# Vertical packs: a market-neutral core with the healthcare specifics as a pack

**Status:** Proposed (2026-10-07). Umbrella design for viscoSuite, with the
touch points in viscoForge and viscoFoundry described at the interface level
only. Implementation lands in milestones (§9); this line names the shipped
classes as they land. M1 shipped: `com.viscosiety.pack.*`, `PackServlet`,
`SubjectMetadataFieldExtractor`; health values still in-module (M2 moves them).

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
                                Ladybug wiring, GitClassLoader, api-service servlets, the pack SPI
packs/health/                   jar `viscolink-pack-health`: fhir.*, mllp.*, the four pipes,
                                springFhir.xml, springMllp.xml, console/fhir-webservices.js,
                                the /fhir/ security rule, FHIR property defaults, hl7util (moved under it)
packs/health/demo-configurations/   today's viscorunner/demo-configurations
viscostore/                     unchanged, referenced only by the health pack's images
packs/public/                   later: BSN subject, BSN de-identification rules, no store (skeleton in M5)
viscorunner/                    builds the images (§4.5); no domain code
```

The reactor order becomes `viscolink → packs/* → viscostore → viscorunner`.
`util/hl7util` moves under `packs/health/` (nothing outside the health code
uses it).

### 4.2 The pack SPI (core)

```java
package com.viscosiety.pack;

/** Discovered through ServiceLoader; the core requires zero or one. */
public interface PackDescriptor {
    String id();                       // "health", "public"
    String displayName();              // "Healthcare", "Public sector"
    String version();                  // the jar's version
    SubjectIdentifier subject();       // §4.3
    List<ConsoleView> consoleViews();  // extra Frank!Console views (today: customViews.* properties)
    List<String> frankOwnedPaths();    // extra path prefixes handed to F!F's own chain (today: "/fhir/")
    Map<String, String> propertyDefaults(); // DeploymentSpecifics-level defaults the pack needs
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
- **Console views**: a pack adds views through properties in its own jar
  (`customViews.<name>.*` plus a list the core's `customViews.names` includes),
  not by Java appending to `customViews.names` at startup; `consoleViews()`
  mirrors them for consumers. The pack's `console/*.js` ride on its classpath,
  but getting a `<script>` tag into the console's `index.html` is an open step
  for M2 (see the M1 spike result in §10).
- **Property defaults**: a pack ships them as the `DeploymentSpecifics.properties`
  of its jar; the Frank!Framework merges every copy on the class path, so a
  tenant's `StageSpecifics`/env still win. `propertyDefaults()` is informational
  in M1 and nothing applies it (§10).
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

`propertyDefaults` is deliberately not in the JSON: it is for the core, not for UIs.

`BuildInfo` of a casting and the ROOT landing page print the pack id and
version next to the F!F version, so an operator can tell what an image is.

### 4.5 Images: how viscorunner builds them

`viscorunner` stays the image builder and carries no domain code. It gains two
build arguments and a Maven staging step.

**Maven staging (`viscorunner/pom.xml`).** Today it copies `viscolink.war`,
`viscostore.war`, the drivers and the runner jar into `target/`. It also stages:

- `target/packs/<pack>/` — the pack jar plus its runtime dependencies (HAPI,
  hl7util, …), and `target/packs/core/` empty. The rule that keeps this clean:
  a pack pom marks everything the core WAR already provides (Frank!Framework,
  Spring, Ladybug) as `provided`, so `dependency:copy-dependencies
  -DincludeScope=runtime` yields exactly the jars the WAR lacks — no duplicate
  classes on the webapp class path.
- `target/demo/<pack>/` — the pack's demo configurations (the core ships one
  echo/API adapter).
- `target/store/viscostore/viscostore.war` or `target/store/none/` (empty).

**One Dockerfile** replaces `Dockerfile`, `Dockerfile.viscolink` and
`Dockerfile.viscostore`'s runner half (`Dockerfile.viscostore` stays for the
standalone store image):

```dockerfile
ARG PACK=health        # health | core | public
ARG STORE=viscostore   # viscostore | none
# …unchanged: Tomcat base, user, catalinaAdditional.properties, the runner jar on
# common.loader, context/server.xml, drivers, the ROOT landing page…
COPY --chown=tomcat target/viscolink.war     /usr/local/tomcat/webapps/viscolink.war
COPY --chown=tomcat target/packs/${PACK}/    /opt/frank/webapp-overlay/viscolink/WEB-INF/lib/
COPY --chown=tomcat target/store/${STORE}/   /usr/local/tomcat/webapps/
COPY --chown=tomcat target/demo/${PACK}/     /opt/frank/demo-configurations/
# the ROOT page is stamped "Frank!Framework <v> · pack ${PACK} <version>" next to the build time
```

`COPY` cannot be conditional, hence the empty directories. The stale-stub size
check stays for the WAR; the store check runs only when `STORE != none`.

**Why the overlay lib directory.** `/opt/frank/webapp-overlay/viscolink/` is
already a `PreResources` set of the `/viscolink` context, so jars under its
`WEB-INF/lib/` load in the webapp class loader — the same loader as the WAR's
own `WEB-INF/lib`. That is what a pack needs: its
`META-INF/services/org.frankframework.components.Module` is found by the
Frank!Framework's ServiceLoader, its Spring files by the class path, its pipes
by the configuration digester. `/opt/frank/drivers` and `/opt/frank/lib` sit on
Tomcat's common loader and die with `NoClassDefFoundError` on Frank!Framework
classes (the lesson of the viscoForge jar). `plugins.directory` is left as it
is. The core WAR stays one published artifact that no pack rebuilds, and
viscoForge keeps layering on top of any pack image the way it does today.

The alternative — one WAR per pack, a Maven `war` module per pack overlaying
the core WAR — is standard Maven too, but multiplies published WARs and puts
the Forge overlay on top of a pack-specific WAR. Rejected in favour of the
overlay lib.

**CI.** One job template with a matrix replaces the three hand-written image
jobs; the amd64/arm64 builds and the `manifest` stage run per tag as today.

| `PACK` | `STORE` | Image | Tags |
|---|---|---|---|
| health | viscostore | `viscorunner` | `<v>` (today's meaning, kept), `<v>-health` |
| health | none | `viscolink` | `<v>` (today's meaning, kept), `<v>-health` |
| core | none | `viscolink` | `<v>-core` |
| public | none | `viscolink` | `<v>-public` (M5) |

`viscorunner` keeps its name for the health suite only; a public-sector image
with a store, if one is ever needed, gets its own name rather than a
`viscorunner-public`. The standalone `viscostore` image is unchanged.

**Compose files.** `docker-compose.yml` passes `PACK=health STORE=viscostore`,
`docker-compose.viscolink.yml` passes `PACK=health STORE=none`, and a new
`docker-compose.core.yml` passes `PACK=core STORE=none`. The Postgres
`init-databases.sql` stays with the suite compose only.

**At runtime nothing new happens.** Tomcat starts `/viscolink`, the pack's
`Module` registers its Spring files, the core's descriptor loader finds the
one `PackDescriptor` (or `CorePack`), Ladybug metadata and ViscoFlow labels
follow it. Probes, the `wait-for-keycloak` gate and the portal's manifests see
the same context paths as today. viscoFoundry picks an image by tag (its
catalogue row), can verify it through `/pack`, and reads the pack from
`BuildInfo`/the ROOT page; castings `FROM` the chosen image with the bake
script unchanged.

### 4.6 Demo configurations and knowledge

Demo configurations move under the pack that owns them. The pack descriptor's
id is what viscoFoundry uses to pick a demo set and the knowledge/pattern
documents it vendors (§7); the core ships one neutral demo (an echo/API
adapter) so "create an instance with demo content" works on a core image.

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
| M2 | The module split (`packs/health`, hl7util moved, demo configurations moved); `viscorunner` image matrix (`PACK`, `STORE`); CI tags | `viscolink:<v>` and `viscorunner:<v>` images behave as before (Larva + demo flows on a tenant instance); `viscolink:<v>-core` boots with no FHIR servlets and `/pack` = core |
| M3 | viscoForge: strategy registry + `viscoforge-pack-health`; console vocabulary from the descriptor | a shared report on a health instance is de-identified exactly as today (existing tests); on a core instance sharing answers the fixed refusal |
| M4 | viscoFoundry: product catalogue (replaces `stack`), edition axis, demo/knowledge per pack | an instance created from a core image, and one from a Forge-health image, both RUNNING through the portal |
| M5 | `packs/public` skeleton: BSN subject with format, BSN de-identification strategy, one demo adapter | `viscolink:<v>-public` boots; ViscoFlow filters on "BSN"; a shared report masks BSNs |

M1 and M2 are the viscoSuite work and can ship independently of the siblings:
M1 changes nothing a consumer sees, M2 keeps today's image tags meaning what
they mean now.

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
  consulted, so a pack's views would not show. M2 must either change those
  deployers (each lists the names it wants, pack views included) or choose a
  different mechanism.

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
when viscolink is built, before any pack jar is known.

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

**Rollout of `servlet.pack.*`.** `PackServlet` answers only with
`servlet.pack.authenticator` and `servlet.pack.securityRoles` set (§4.4). viscoFoundry's
manifest renderer will render them in M4 the way it renders `servlet.configRef.*` today:
a new instance gets them at creation, and an instance created earlier only at its next
stop/resume, when the renderer runs again. Until then
`/api-service/pack` answers 401 to everyone, because the servlet fails closed without
its roles. ViscoFlow's `/flow-api/pack` needs no setting and works at once.

Remaining open question:

- Whether `BuildInfo` should carry the pack id (castings) or the ROOT page
  alone is enough — decide in M2 with the viscoFoundry casting flow in view.
