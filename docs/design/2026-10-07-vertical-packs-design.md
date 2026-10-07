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

### 4.5 Images

`viscorunner` builds from the same Tomcat base with two arguments: `PACK`
(`health` | `core` | `public`) and `STORE` (`viscostore` | none). The pack jar
is copied into the WAR's overlay lib directory
(`/opt/frank/webapp-overlay/viscolink/WEB-INF/lib/`), which the context's
`PreResources` already searches — the proven placement for jars that use F!F
classes (a jar in `/opt/frank/drivers` or `/opt/frank/lib` sits on the wrong
class loader and dies with `NoClassDefFoundError`). `plugins.directory` is
left as it is.

| Image | Contents | Tags |
|---|---|---|
| `viscolink` | core | `<v>-core` |
| `viscolink` | core + health pack | `<v>` (today's meaning, kept) and `<v>-health` |
| `viscorunner` | core + health pack + viscostore | `<v>` (today's meaning, kept) and `<v>-health` |
| `viscolink` | core + public pack | `<v>-public` (M5) |

CI builds the matrix from one job template; the `manifest` stage combines the
architectures per tag as it does now.

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
