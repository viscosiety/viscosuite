# ViscoSuite

**Open-source integration platform: a market-neutral core, with packs for markets such as healthcare — powered by the [Frank!Framework](https://frankframework.org).**

ViscoSuite receives, validates, transforms and routes messages through declarative,
git-native pipelines. At its base is a market-neutral core, also published as an image of its
own. A pack adds what one market needs on top of that core. The first pack is Healthcare: it adds
HL7v2 and FHIR, and a standard FHIR repository stores the results — with every record traceable
back to its raw source.

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://github.com/viscosiety/viscosuite/blob/main/LICENSE)
[![Powered by Frank!Framework](https://img.shields.io/badge/powered%20by-Frank!Framework-1a7f76.svg)](https://frankframework.org)

- **ViscoLink** — the Frank!Framework integration layer, market-neutral: REST and
  database-backed integrations (and whatever else the Frank!Framework provides), the console
  with OIDC login, ViscoFlow and Ladybug. Extend by dropping F!F XML configurations into a
  mounted directory — no rebuild required. A **pack** adds the components of one market on top;
  the Healthcare pack brings HL7v2 (MLLP and HTTP) and FHIR (R4/R5/DSTU3).
- **ViscoStore** — a HAPI FHIR JPA Server as the FHIR repository (FHIR R4): standard FHIR REST
  API, browser tester UI, Swagger docs, an MCP endpoint for AI/LLM integration, and the
  codification step of the two-zone model. Part of the Healthcare suite; optional.
- **ViscoRunner** — Docker packaging (one image recipe, several variants) and a demo mode
  that shows the whole suite working in minutes.

## Quick start — live traffic in minutes

Prerequisite: Docker ≥ 24 with Compose V2.

```bash
git clone https://github.com/viscosiety/viscosuite.git
cd viscosuite/viscorunner
docker compose -f docker-compose.yml -f docker-compose.demo.yml up
```

The first start downloads the published images (the ViscoSuite image is about 1 GB) and,
through a small helper container, the Nictiz validation packages the demo uses, so it needs
internet access and takes a few minutes. Do not add `--build` on a fresh clone: that builds
the images from source and needs the Maven build first (see
[Building from source](#building-from-source)).

Open **http://localhost:8180**. The demo overlay starts a traffic generator that streams
HL7v2 ADT messages, FHIR transaction bundles and R4 nl-core patients through real
pipelines — including deliberately failing messages, so you can watch validation refusals,
error-store parking and retries happen live:

- **`/viscolink/flow/`** — follow any message's journey step by step (per-pipe input,
  output and routing decisions)
- **`/viscostore/tester/`** — browse the FHIR repository (ViscoStore asks for its login: enter
  the demo login that `viscorunner/docker-compose.demo.yml` sets under the lock icon in the
  tester's top bar)
- **`/viscolink/iaf/`** — the full Frank!Console for the expert view

The `fake-emr` demo adapter reads from a sample database hosted by Viscosiety, so that part of
the demo needs internet access as well; the rest of the demo does not depend on it.

**These compose files are for local development.** The Frank!Console runs without sign-in
(stage `LOC`), PostgreSQL is published on the host, and ViscoStore, PostgreSQL and the demo's
RabbitMQ use simple default logins written in the compose files. Do not expose these stacks
beyond your machine; for anything else, set your own credentials and stage.

To start blank instead (your own configurations, no demo traffic), create the credentials
file the stack mounts (`cp secrets/credentials.properties.example secrets/credentials.properties`
in `viscorunner/`), run `docker compose up` without the demo overlay, and put each of your
configurations in its own folder under `viscorunner/configurations/`. Details are in
[`viscorunner/README.md`](https://github.com/viscosiety/viscosuite/blob/main/viscorunner/README.md).

## Architecture

```
viscosuite/
├── viscolink/               the market-neutral core: Frank!Framework integration middleware
├── viscostore/              HAPI FHIR JPA Server (persistent FHIR R4 storage + MCP)
├── packs/health/            the Healthcare pack: HL7v2/FHIR components, defaults, working reference configurations (see below)
└── viscorunner/             Docker packaging and configuration hub
    ├── configurations/               empty scaffold — mount your own integrations here
    ├── docker-compose.yml            base service definitions (Healthcare pack + ViscoStore)
    ├── docker-compose.viscolink.yml  Healthcare pack without ViscoStore
    ├── docker-compose.core.yml       the market-neutral core, no pack components (see Images)
    └── docker-compose.demo.yml       demo overlay (demo configurations + traffic generator)
```

**ViscoLink owns the integration concern.** It receives messages from source systems,
validates and transforms them through F!F pipelines, and delivers the results to their
targets — in the Healthcare suite, to ViscoStore through the FHIR REST API. It is not the
system of record, and every pipeline execution is stateless. It does keep operational data in
its own database: the message store and error store of guaranteed-delivery flows (messages
waiting for delivery or parked after a failure) and the Ladybug traces, both of which contain
message content. Source systems talk to ViscoLink; ViscoLink talks to its targets.

**ViscoStore owns the persistence concern.** It is a standard HAPI FHIR JPA Server (FHIR R4)
with one Viscosiety addition: the codification step of the two-zone model (below) — an
interceptor that runs a FHIR StructureMap (FML) on inbound-zone records and writes the
codified record linked to its source, plus `$convert` to trigger that on demand and
`$compile` to turn FML text into a stored StructureMap. Consumers that only need to query
stored data — a clinical dashboard, an AI assistant, a reporting tool — go directly to
ViscoStore without passing through ViscoLink. For plain storage ViscoStore can be replaced by
any FHIR-compliant server (a replacement does not bring the codification step with it), and
ViscoLink can route to multiple targets.

**The two-zone model keeps source data honest.** Incoming records are stored in the
**inbound zone** first — tagged, provenance-tracked, and 1-to-1 traceable to the source
system, with no interpretation applied. Semantic mapping to coded, profile-conformant
resources happens as a separate step into the **codified zone**, permanently linked to its
inbound source. When the source corrects a record, the codified record is derived again; when
an auditor asks "where did this value come from", the answer is one reference away.
What is built: inbound-zone tagging in the `nl-core-intake` flow, and FML-driven codification
in ViscoStore (FHIR R4 only). The demo traffic shows only the inbound step; the codification
is demonstrated by `packs/health/util/demo/codify-lab.sh`. The profiles and extensions of
the model are identified by canonical URLs of the ViscoLink Implementation Guide (IG). The IG
is not part of this repository, and it is not yet available at its canonical address
(`https://ig.viscosiety.com`); until it is, the URLs work as identifiers only.

## Why Frank!Framework

Most integration middleware in healthcare falls into one of two traps: either
configuration lives in a proprietary database — invisible to version control, CI/CD, and
locked into a vendor silo — or pipelines are written as imperative scripts that require a
full build cycle for every change. Frank!Framework avoids both.

**Config-first, git-native.** Every integration is a plain XML file in version control. It
gets code review, branching, CI, and rollback for free. The configuration *is* the source
of truth — diffable, reviewable in a pull request, and deployable by volume mount.

**Stateless and DevOps-friendly.** F!F pipeline executions are stateless — each message flows
through independently, and the queues of guaranteed-delivery flows live in the database, not
in the process. Containers can be replaced, restarted or rolled back without draining
sessions. Platforms that embed session state, channel locks, or in-process queues make
zero-downtime deployments fragile.

**Declarative transformations, LLM-friendly.** Pipelines transform data through XSLT and
equivalent declarative mapping documents, not imperative scripts. A transformation is a
*document* with a known grammar: an LLM (or a reviewer) can generate it from a mapping
description, validate it against a FHIR profile or HL7v2 schema, and explain what it does.
Script-based middleware embeds logic as JavaScript or Groovy with implicit side effects —
producible, but not reliably verifiable against a structural contract.

**Open source, proven in the Dutch public sector.** F!F has a strong track record in Dutch
government and corporate integration. ViscoSuite builds on that foundation: a market-neutral
core with a pack per market, Healthcare being the first — fully open source, top to bottom,
with no proprietary engine anywhere in the stack.

## What ViscoSuite adds

Frank!Framework provides the pipeline engine, tooling, and runtime. ViscoSuite adds the
following.

**ViscoFlow** (part of ViscoLink, in every image)

F!F records every pipeline execution as a structured trace — input and output at every
pipe, session key values, the forward taken, and duration. ViscoFlow is a purpose-built
frontend on top of this: it surfaces those traces with the context of the message (subject
ID — "Patient" in the Healthcare pack, "Subject" in Core — correlation ID, flow name, exit
state) and makes them navigable without the developer-oriented Ladybug interface. Filtering
by subject or flow, inspecting a message's transformation step by step, and auditing routing
decisions are first-class operations.

**The Healthcare pack** (`packs/health`) lays the components below over ViscoLink. They are
part of the Healthcare images; the Core image does not contain them.

*Custom pipes*

| Pipe | Description |
|---|---|
| `Hl7v2ToXmlPipe` | Converts pipe-delimited HL7v2 to HL7v2 XML Encoding Syntax using HAPI HL7v2. `hl7Version` pins the HAPI model version (a message that declares another version is converted to the pinned one, not rejected — a known limitation), and message validation can be switched per message through a `validateMessage` parameter |
| `XmlToHl7v2Pipe` | Inverse: converts HL7v2 XML back to pipe-delimited format for MLLP transmission or ACK generation |
| `FhirValidatorPipe` | Validates FHIR resources (XML or JSON) against R4, R5, or DSTU3 using the HAPI FHIR instance validator; refuses invalid input on a `failure` forward with an `OperationOutcome`. Loads FHIR NPM packages (`validationPackages`) for profile-level validation — e.g. against Nictiz nl-core |
| `FhirFormatPipe` | FHIR-aware format conversion between `application/fhir+xml` and `application/fhir+json` — structurally correct (single-element arrays stay arrays), with the target mimetype configurable per deployment, per session key, or per message via `<Param>` |

*Custom listener and sender*

| Component | Description |
|---|---|
| `MllpListener` | TCP server that accepts persistent MLLP connections, frames HL7v2 messages, and returns synchronous ACKs |
| `MllpSender` | TCP client sender that maintains persistent connections to remote MLLP endpoints and reads ACK responses |
| `FhirListener` | Registers FHIR operation endpoints (read, search, bundle-transaction, proxy) with ViscoLink's FHIR facade servlet |

## Reference implementations

The demo overlay ships working F!F configurations (they live with the Healthcare pack) — use
them as starting points, study them as patterns, or run them as-is. They follow explicit
[configuration conventions](https://github.com/viscosiety/viscosuite/blob/main/packs/health/demo-configurations/README.md).

| Configuration | What it shows |
|---|---|
| `hl7v2-to-fhir` | HL7v2 ADT and SIU over HTTP or MLLP, converted to FHIR R4 Bundles via XSLT |
| `hl7v2-to-xml` | HL7v2 to structured XML — preprocessing step or standalone inspection |
| `fhir-delivery` | Asynchronous, guaranteed FHIR delivery: intake → message store → transacted delivery with retries and error-store parking, converting to the destination's FHIR mimetype in flight |
| `nl-core-intake` | **FHIR R4 / nl-core (Dutch) reference flow**: validate R4 synchronously (422 + OperationOutcome on refusal), tag into the inbound zone, deliver with guaranteed retry |
| `fhir-to-fhir` | FHIR R4 / DSTU3 / R5 facade endpoints routing through ViscoLink into ViscoStore |
| `fhir-store-proxy` | Transparent reverse proxy to ViscoStore with credential injection |
| `loinc-mapping-api` | CRUD API for a LOINC mapping table in ViscoLink's database (seeded from a CSV); the `fhir-to-fhir` lab-enrichment facade uses it to add LOINC codings to uncoded Observations |
| `fake-emr` | PostgreSQL-backed fake EMR emitting FHIR Bundles — database-sourced FHIR (its sample database is hosted by Viscosiety) |
| `demo-traffic` | The demo heartbeat: scheduled generator streaming valid and deliberately failing messages through the HL7v2 intakes (`hl7v2-to-fhir`, `hl7v2-to-xml`), `fhir-delivery` and `nl-core-intake` |

**A note on FHIR versions:** ViscoStore runs FHIR R4, and so does its codification step. The
pipes and the facade endpoints speak R4, R5 and DSTU3, and the `nl-core-intake` flow is the
R4 reference for the Dutch nl-core install base — including **package-backed profile
validation**: point `FhirValidatorPipe` at the Nictiz nl-core packages (CC0; the pinned
versions are pre-releases, and the demo downloads them into `viscorunner/fhir-packages/` on
first start) and resources are validated against the profiles they claim, not just base R4.
The demo overlay runs with this enabled.

## Key endpoints

| Endpoint | Description |
|---|---|
| `/` | ViscoSuite landing page — service discovery |
| `/viscolink/` | ViscoLink app launcher (tools + Frank!Console) |
| `/viscolink/flow/` | ViscoFlow — live message flow viewer and trace debugger |
| `/viscolink/iaf/` | Frank!Console / Ladybug flow debugger |
| `GET /viscolink/api-service/pack` | Pack descriptor: which pack the image runs (`health` or `core`) and its subject identifier; bearer-token callers only |
| `/viscostore/fhir` | FHIR REST API (HAPI JPA Server) |
| `/viscostore/tester/` | Interactive FHIR Tester UI |
| `/viscostore/fhir/swagger-ui/` | Swagger API docs |
| `POST /viscostore/mcp/messages` | MCP Streamable HTTP (AI/LLM integration) |

ViscoStore protects its FHIR API, Swagger UI and MCP endpoint with HTTP Basic authentication.
Only `/viscostore/fhir/metadata`, the health probe and the tester page itself are open. The
compose files ship a default login for local use; change it before exposing the store beyond
your machine.

Ports (local compose): `8180` HTTP · `2575` MLLP (HL7v2 over TCP, Healthcare images only) ·
`5432` PostgreSQL · `5005` JPDA debugger (debug overlays only) · `5672`/`15672` RabbitMQ
(demo only). These are local-development settings.

## MCP integration

ViscoStore exposes FHIR resources as [MCP](https://modelcontextprotocol.io) tools via
Spring AI, enabling AI assistants to query and write FHIR data. The endpoint needs the same
HTTP Basic login as the rest of the FHIR API — use the login configured in your compose
file, and let your MCP client send it (the header syntax differs per client):

```json
{
  "mcpServers": {
    "viscosuite": {
      "url": "http://localhost:8180/viscostore/mcp/messages",
      "headers": { "Authorization": "Basic <base64 of username:password>" }
    }
  }
}
```

## Building from source

Prerequisites: Docker ≥ 24 with Compose V2 (running), JDK 21+ (building). The Maven
wrapper (`./mvnw`) downloads the correct Maven automatically.

```bash
# Build all modules (viscorunner packages the WARs and the pack overlay the others install)
./mvnw install -pl viscolink,packs/health,viscostore && ./mvnw package -pl viscorunner -DskipTests

# Run tests
./mvnw test -pl viscolink
./mvnw test -pl packs/health
./mvnw verify -pl viscostore        # unit + integration tests
./mvnw verify -pl viscorunner       # class-path and launcher integration tests (after the install)
```

After the build, `docker compose up --build` in `viscorunner/` builds the images from your
build output instead of using the published ones.

Remote debugging (JPDA on `5005`) and smoke tests are described in
[`viscorunner/README.md`](https://github.com/viscosiety/viscosuite/blob/main/viscorunner/README.md)
and `viscostore/src/test/smoketest/`.

## Images

One recipe, `viscorunner/Dockerfile`, builds every runner image from two build arguments.
`PACK` (`health`, the default, or `core`) chooses the pack laid over the ViscoLink core, and
`STORE` (`viscostore`, the default, or `none`) whether the ViscoStore FHIR server is part of
the image.

A **pack** is the market-specific layer put over the market-neutral ViscoLink core: its
components, defaults, demo configurations and the word for the main subject of a message.
An image carries exactly one. Today there are two: **Healthcare** (id `health`: HL7v2, MLLP and
FHIR components, subject "Patient") and **Core** (id `core`: the market-neutral platform
without those components, subject "Subject" — the image without a vertical pack). Further packs, for example for the public sector, are planned
but not built.

CI publishes multi-arch images (amd64 and arm64) to Viscosiety's public container registry,
`registry.git.viscosiety.com/public-applications/viscosuite/<image>`, which allows anonymous
pulls. Three combinations of pack and store are published, plus the standalone store:

| Image | Contents | Tags |
|---|---|---|
| `viscorunner` | Healthcare pack + ViscoStore (the full suite) | `<sha>`, `latest`, `<sha>-health`, `latest-health` |
| `viscolink` | Healthcare pack, no store (ViscoLink only) | `<sha>`, `latest`, `<sha>-health`, `latest-health` |
| `viscolink` | Core: no pack components, no store | `<sha>-core`, `latest-core` |
| `viscostore` | the standalone FHIR server | `<sha>`, `latest` |

The image name `viscorunner` means the suite (ViscoLink plus ViscoStore), not the packaging in
general. Other combinations, such as Core with ViscoStore, are not published.

`<sha>` is the commit; there are no release-number tags, so an image cannot be pinned to a
release such as v0.20.0, only to a commit. The unsuffixed tags keep their long-standing meaning
(the healthcare images) and `-health` is the explicit spelling. `-core` is the market-neutral
integration platform: Frank!Framework, the console with OIDC, ViscoFlow and Ladybug, with no FHIR
servlets, no MLLP, no HL7v2 pipes and no HAPI FHIR libraries. Its subject identifier is a neutral
"Subject" instead of "Patient", the pack descriptor endpoint (`GET /viscolink/api-service/pack`)
reports `core`, and its demo is a one-adapter echo (`viscolink/demo-configurations/echo/`; copy it into
`viscorunner/configurations/` to load it). Try the core from `viscorunner/`: `cp secrets/credentials.properties.example secrets/credentials.properties`,
then `docker compose -f docker-compose.core.yml up`. All images contain third-party components
that keep their own licences (see License).

## Versioning and compatibility

ViscoSuite follows semantic versioning (pre-1.0: breaking changes bump the minor version).
The latest release is **v0.20.0** (2026-09-25); **v1.0.0 lands together with Frank!Framework
10.3 GA**. Until then the builds run on a Frank!Framework 10.3 pre-release (nightly) build, and
each release documents the version it builds on in the
[changelog](https://github.com/viscosiety/viscosuite/blob/main/CHANGELOG.md). The pack
structure and the `-core` and `-health` images described here are on `main` and in the
`latest*` images; they are not part of v0.20.0 and arrive with the next release.

## Community

**This GitHub repository is the community home** — issues, discussions, releases and pull
requests live here. Day-to-day development happens on our GitLab and is mirrored here on
every push, so what you see is always current. Merged PRs are integrated on GitLab by a
maintainer and flow back with authorship preserved.

- Found a bug or have an integration question?
  [Open an issue](https://github.com/viscosiety/viscosuite/issues) — templates
  included, and **never include real patient data**.
- Want to contribute? Read [CONTRIBUTING.md](https://github.com/viscosiety/viscosuite/blob/main/CONTRIBUTING.md).
- Security reports go to [SECURITY.md](https://github.com/viscosiety/viscosuite/blob/main/SECURITY.md) — not to the issue tracker.

## Open source and commercial — the boundary

Everything in this repository — ViscoLink, ViscoStore, ViscoRunner, the Healthcare pack and the
reference configurations — is and stays **Apache-2.0**. (The ViscoLink IG is not part of
this repository.) Viscosiety, the company behind ViscoSuite, additionally offers **ViscoForge**: a
commercial, proprietary overlay on top of ViscoLink — an operator console for organisations
that *run* flows rather than build them (message triage, journey timelines, audited
retry/resolve with hash-chained audit logging, deployment manifests). ViscoForge is not part
of this repository; the suite is fully usable without it, forever, and does not depend on it.

## Support & services

Community support in issues is best-effort. For production deployments, Viscosiety offers:

- **Integration quickscan** — your current landscape, the Wegiz/EHDS gap, a concrete route
- **Fixed-scope pilot** — your first interface live in 30 days, production-grade
- **Support retainer** — SLA, maintenance and on-call for ViscoSuite deployments
- **ViscoForge** — the operator console, with implementation and training

Contact: [viscosiety.com](https://viscosiety.com).

## License

[Apache License 2.0](https://github.com/viscosiety/viscosuite/blob/main/LICENSE) — © 2026 Viscosiety B.V. See
[NOTICE](https://github.com/viscosiety/viscosuite/blob/main/NOTICE).
ViscoSuite is powered by the [Frank!Framework](https://frankframework.org), an open-source
integration framework by WeAreFrank!, and by [HAPI FHIR](https://hapifhir.io). Third-party
components keep their own licences: Frank!Framework and HAPI FHIR are Apache-2.0; HAPI HL7v2
(used by the Healthcare pack) is dual-licensed under the MPL 1.1 and the GPL and used under
the MPL; the LOINC® mapping in the demo contains content from [LOINC](https://loinc.org), used
under the LOINC licence.
