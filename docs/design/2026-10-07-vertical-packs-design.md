# Vertical packs: a market-neutral core with the healthcare specifics as a pack

**Status:** Proposed (2026-10-07). Umbrella design for viscoSuite, with the
touch points in viscoForge and viscoFoundry described at the interface level
only. Implementation lands in milestones (§9); this line names the shipped
classes as they land.

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
| `viscolink` | `com.viscosiety.fhir.*` (facade servlets, providers, operation registry, metadata builder), `com.viscosiety.mllp.*`, pipes `FhirFormatPipe`, `FhirValidatorPipe`, `Hl7v2ToXmlPipe`, `XmlToHl7v2Pipe`; `springFhir.xml`, `springMllp.xml`; the `/fhir/` rule in `ConsoleSecurityRegistrar`; the `patientId` extractor + column in `springIbisTestToolVisco.xml`; FHIR keys in `DeploymentSpecifics.properties`; the HAPI dependencies in the pom; the console tab `console/fhir-webservices.js`; ViscoFlow's "Patient" column, filter and detail line (`flow/js/*`, `FlowController.patientFilter`) | Frank!Framework, console + OIDC (`ConsoleSecurityRegistrar`, `ApiSessionAuthListener`), ViscoFlow itself, Ladybug wiring, `GitClassLoader`, `api-service` servlets, `springStubbedRun.xml`, `ViscoLinkModule` |
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

public record SubjectIdentifier(String sessionKey, String label, String metadataName,
                                Optional<Pattern> format) {}
```

- **Spring files, pipes, listeners, senders**: through the pack's own F!F
  `Module` (D1). The descriptor does not duplicate that.
- **`ConsoleSecurityRegistrar.isFrankOwnedPath`** becomes
  `core paths ∪ pack.frankOwnedPaths()`.
- **Console views**: the pack's `consoleViews()` are appended to
  `customViews.names` at startup (the mechanism the `viscoLink` view uses
  today); the pack's `console/*.js` ride on its classpath.
- **Property defaults**: the core applies `propertyDefaults()` below
  `DeploymentSpecifics.properties` in the AppConstants chain, so a tenant's
  `StageSpecifics`/env still win.
- **No pack**: a built-in `CorePack` descriptor (`id = "core"`, subject
  `subjectId`/"Subject", no views, no extra paths) keeps every consumer total.

### 4.3 The subject identifier

Today `springIbisTestToolVisco.xml` hardcodes a `SessionKeyMetadataFieldExtractor`
for `patientId` and lists `patientId` in `metadataNames`. The core replaces
the two literals with beans built from `PackDescriptor.subject()`:

- extractor `name = metadataName`, `label = label`, `sessionKey = sessionKey`;
- `metadataNames` gets `metadataName` in the position `patientId` has now.

ViscoFlow (`flow/js/*`, `FlowController`) reads `subject` from the descriptor
endpoint (§4.4): the column header, the filter chip, the detail line and the
Ladybug `filterHeader` all use `metadataName`/`label`. The query parameter
becomes `subjectFilter`; `patientFilter` stays as an alias for one release.
`SESSION_META_KEYS` is built from the descriptor plus the core keys.

The health pack declares `patientId` / "Patient" / `patientId`, so the Ladybug
column, the stored metadata and every existing configuration's
`PutInSessionPipe` keep working with no change. The public pack declares
`bsn` / "BSN" / `bsn` with a format (eleven-proof) the UIs may use to mark an
invalid value.

### 4.4 The descriptor endpoint

`GET /viscolink/api-service/pack` → the descriptor as JSON, on the same
console-authenticated chain as the other `api-service` servlets (bearer or
console session), because the UIs that need it are behind that login anyway:

```json
{ "id": "health", "displayName": "Healthcare", "version": "1.4.0",
  "subject": { "sessionKey": "patientId", "label": "Patient", "metadataName": "patientId" },
  "consoleViews": [ { "name": "FHIR webservices", "url": "…" } ],
  "deidentificationStrategyIds": [ "fhir-patient", "hl7v2" ] }
```

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
- Whether `BuildInfo` should carry the pack id (castings) or the ROOT page
  alone is enough — decide in M2 with the viscoFoundry casting flow in view.
