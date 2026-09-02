# ViscoSuite

**Open-source healthcare integration, powered by the [Frank!Framework](https://frankframework.org).**

HL7v2, FHIR and everything in between: ViscoSuite receives, validates, transforms and routes
healthcare messages through declarative, git-native pipelines, and stores the results in a
standard FHIR repository — with every record traceable back to its raw source.

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Powered by Frank!Framework](https://img.shields.io/badge/powered%20by-Frank!Framework-1a7f76.svg)](https://frankframework.org)

- **ViscoLink** — the Frank!Framework integration layer: HL7v2 (MLLP and HTTP), FHIR
  (R4/R5/DSTU3), REST, and database-backed integrations. Extend by dropping F!F XML
  configurations into a mounted directory — no rebuild required.
- **ViscoStore** — a HAPI FHIR JPA Server as the canonical FHIR repository: standard FHIR
  REST API, browser tester UI, Swagger docs, and an MCP endpoint for AI/LLM integration.
- **ViscoRunner** — Docker packaging, reference configurations, and a demo mode that shows
  the whole suite working in five minutes.

## Quick start — five minutes to live traffic

```bash
git clone https://github.com/viscosiety/viscosuite.git
cd viscosuite/viscorunner
docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build
```

Open **http://localhost:8180**. The demo overlay starts a traffic generator that streams
HL7v2 ADT messages, FHIR transaction bundles and R4 nl-core patients through real
pipelines — including deliberately failing messages, so you can watch validation refusals,
error-store parking and retries happen live:

- **`/viscolink/flow/`** — follow any message's journey step by step (per-pipe input,
  output and routing decisions)
- **`/viscostore/tester/`** — browse the FHIR repository
- **`/viscolink/iaf/`** — the full Frank!Console for the expert view

To start blank instead (your own configurations, no demo traffic), see
[`viscorunner/README.md`](viscorunner/README.md).

## Architecture

```
viscosuite/
├── viscolink/               Frank!Framework integration middleware
├── viscostore/              HAPI FHIR JPA Server (persistent FHIR storage + MCP)
└── viscorunner/             Docker packaging and configuration hub
    ├── configurations/          empty scaffold — mount your own integrations here
    ├── demo-configurations/     working reference configurations (see below)
    ├── docker-compose.yml       base service definitions
    └── docker-compose.demo.yml  demo overlay (demo configurations + traffic generator)
```

**ViscoLink owns the integration concern.** It receives messages from source systems,
validates and transforms them through F!F pipelines, and writes results to ViscoStore via
the FHIR REST API. It holds no persistent state of its own — every pipeline execution is
stateless and every outcome ends up in ViscoStore. Source systems talk to ViscoLink;
ViscoLink talks to ViscoStore.

**ViscoStore owns the persistence concern.** It is a standard HAPI FHIR JPA Server with no
integration logic. Consumers that only need to query stored data — a clinical dashboard, an
AI assistant, a reporting tool — go directly to ViscoStore without passing through
ViscoLink. ViscoStore can be replaced by any FHIR-compliant server; ViscoLink can route to
multiple targets; neither side carries the concerns of the other.

**The two-zone model keeps source data honest.** Incoming records are stored in the
**inbound zone** first — tagged, provenance-tracked, and 1-to-1 traceable to the source
system, with no interpretation applied. Semantic mapping to coded, profile-conformant
resources happens as a separate step into the **codified zone**, permanently linked to its
inbound source. When the source corrects a record, the correction propagates; when an
auditor asks "where did this value come from", the answer is one reference away. The
profiles, extensions and conventions live in the
[ViscoLink IG](https://ig.viscosiety.com).

## Why Frank!Framework

Most integration middleware in healthcare falls into one of two traps: either
configuration lives in a proprietary database — invisible to version control, CI/CD, and
locked into a vendor silo — or pipelines are written as imperative scripts that require a
full build cycle for every change. Frank!Framework avoids both.

**Config-first, git-native.** Every integration is a plain XML file in version control. It
gets code review, branching, CI, and rollback for free. The configuration *is* the source
of truth — diffable, reviewable in a pull request, and deployable by volume mount.

**Stateless and DevOps-friendly.** F!F pipelines are stateless — each message flows through
independently. Containers can be replaced, scaled horizontally, or rolled back without
session drain. Platforms that embed session state, channel locks, or in-process queues make
zero-downtime deployments fragile; F!F avoids this entirely.

**Declarative transformations, LLM-friendly.** Pipelines transform data through XSLT and
equivalent declarative mapping documents, not imperative scripts. A transformation is a
*document* with a known grammar: an LLM (or a reviewer) can generate it from a mapping
description, validate it against a FHIR profile or HL7v2 schema, and explain what it does.
Script-based middleware embeds logic as JavaScript or Groovy with implicit side effects —
producible, but not reliably verifiable against a structural contract.

**Open source, proven in the Dutch public sector — applied to healthcare.** F!F has a
strong track record in Dutch government and corporate integration. ViscoSuite is the
healthcare-first implementation on that foundation: fully open source, top to bottom, with
no proprietary engine anywhere in the stack.

## What ViscoSuite adds

Frank!Framework provides the pipeline engine, tooling, and runtime. ViscoSuite extends it
with healthcare-specific components.

**Custom pipes**

| Pipe | Description |
|---|---|
| `Hl7v2ToXmlPipe` | Converts pipe-delimited HL7v2 to HL7v2 XML Encoding Syntax using HAPI HL7v2; supports version enforcement and message validation (per-message override via a `validateMessage` parameter) |
| `XmlToHl7v2Pipe` | Inverse: converts HL7v2 XML back to pipe-delimited format for MLLP transmission or ACK generation |
| `FhirValidatorPipe` | Validates FHIR resources (XML or JSON) against R4, R5, or DSTU3 using the HAPI FHIR instance validator; refuses invalid input on a `failure` forward with an `OperationOutcome` |
| `FhirFormatPipe` | FHIR-aware format conversion between `application/fhir+xml` and `application/fhir+json` — structurally correct (single-element arrays stay arrays), with the target mimetype configurable per deployment, per session key, or per message via `<Param>` |

**Custom listener and sender**

| Component | Description |
|---|---|
| `MllpListener` | TCP server that accepts persistent MLLP connections, frames HL7v2 messages, and returns synchronous ACKs |
| `MllpSender` | TCP client sender that maintains persistent connections to remote MLLP endpoints and reads ACK responses |
| `FhirListener` | Registers FHIR operation endpoints (read, search, bundle-transaction, proxy) with ViscoLink's FHIR facade servlet |

**ViscoFlow**

F!F records every pipeline execution as a structured trace — input and output at every
pipe, session key values, the forward taken, and duration. ViscoFlow is a purpose-built
frontend on top of this: it surfaces those traces with healthcare context (patient ID,
correlation ID, flow name, exit state) and makes them navigable without the
developer-oriented Ladybug interface. Filtering by patient or flow, inspecting a message's
transformation step by step, and auditing routing decisions are first-class operations.

## Reference implementations

The demo overlay ships working F!F configurations — use them as starting points, study them
as patterns, or run them as-is. They follow explicit
[configuration conventions](viscorunner/demo-configurations/README.md).

| Configuration | What it shows |
|---|---|
| `hl7v2-to-fhir` | HL7v2 ADT and SIU over HTTP or MLLP, converted to FHIR R4 Bundles via XSLT |
| `hl7v2-to-xml` | HL7v2 to structured XML — preprocessing step or standalone inspection |
| `fhir-delivery` | Asynchronous, guaranteed FHIR delivery: intake → message store → transacted delivery with retries and error-store parking, converting to the destination's FHIR mimetype in flight |
| `nl-core-intake` | **FHIR R4 / nl-core (Dutch) reference flow**: validate R4 synchronously (422 + OperationOutcome on refusal), tag into the inbound zone, deliver with guaranteed retry |
| `fhir-to-fhir` | FHIR R4 / DSTU3 / R5 facade endpoints routing through ViscoLink into ViscoStore |
| `fhir-store-proxy` | Transparent reverse proxy to ViscoStore with credential injection |
| `loinc-mapping-api` | LOINC lookup from CSV, returning enriched FHIR Observations |
| `fake-emr` | PostgreSQL-backed fake EMR emitting FHIR Bundles — database-sourced FHIR |
| `demo-traffic` | The demo heartbeat: scheduled generator streaming valid and deliberately failing messages through all of the above |

**A note on FHIR versions:** ViscoStore runs FHIR R5 and the ViscoLink IG derives from the
HL7 Europe core profiles (the EHDS foundation). The pipes speak R4, R5 and DSTU3, and the
`nl-core-intake` flow is the R4 reference for the Dutch nl-core install base. First-class
nl-core package validation is on the roadmap.

## Key endpoints

| Endpoint | Description |
|---|---|
| `/` | ViscoSuite landing page — service discovery |
| `/viscolink/` | ViscoLink app launcher (tools + Frank!Console) |
| `/viscolink/flow/` | ViscoFlow — live message flow viewer and trace debugger |
| `/viscolink/iaf/` | Frank!Console / Ladybug flow debugger |
| `/viscostore/fhir` | FHIR REST API (HAPI JPA Server) |
| `/viscostore/tester/` | Interactive FHIR Tester UI |
| `/viscostore/fhir/swagger-ui/` | Swagger API docs |
| `POST /viscostore/mcp/messages` | MCP Streamable HTTP (AI/LLM integration) |

Ports: `8180` HTTP · `2575` MLLP (HL7v2 over TCP) · `5432` PostgreSQL · `5005` JPDA debugger.

## MCP integration

ViscoStore exposes FHIR resources as [MCP](https://modelcontextprotocol.io) tools via
Spring AI, enabling AI assistants to query and write FHIR data:

```json
{
  "mcpServers": {
    "viscosuite": { "url": "http://localhost:8180/viscostore/mcp/messages" }
  }
}
```

## Building from source

Prerequisites: Docker ≥ 24 with Compose V2 (running), JDK 21+ (building). The Maven
wrapper (`./mvnw`) downloads the correct Maven automatically.

```bash
# Build all modules (viscorunner packages the WARs the others install)
./mvnw install -pl viscolink,viscostore && ./mvnw package -pl viscorunner

# Run tests
./mvnw test -pl viscolink
./mvnw verify -pl viscostore        # unit + integration tests
```

Remote debugging (JPDA on `5005`) and smoke tests are described in
[`viscorunner/README.md`](viscorunner/README.md) and
`viscostore/src/test/smoketest/`.

## Versioning and compatibility

ViscoSuite follows semantic versioning. Each release documents the Frank!Framework version
it builds on; **v1.0.0 lands together with Frank!Framework 10.3 GA**. Until then, 0.9.x
releases are feature-complete previews of the 1.0 line.

## Community

**This GitHub repository is the community home** — issues, discussions, releases and pull
requests live here. Day-to-day development happens on our GitLab and is mirrored here on
every push, so what you see is always current. Merged PRs are integrated on GitLab by a
maintainer and flow back with authorship preserved.

- Found a bug or have an integration question? [Open an issue](../../issues) — templates
  included, and **never include real patient data**.
- Want to contribute? Read [CONTRIBUTING.md](CONTRIBUTING.md).
- Security reports go to [SECURITY.md](SECURITY.md) — not to the issue tracker.

## Open source and commercial — the boundary

Everything in this repository — ViscoLink, ViscoStore, ViscoRunner, the reference
configurations and the ViscoLink IG — is and stays **Apache-2.0**. Viscosiety, the company
behind ViscoSuite, additionally offers **ViscoForge**: a commercial operator console for
organisations that *run* flows rather than build them (message triage, journey timelines,
audited retry/resolve with hash-chained audit logging, deployment manifests). The suite is
fully usable without it, forever.

## Support & services

Community support in issues is best-effort. For production deployments, Viscosiety offers:

- **Integration quickscan** — your current landscape, the Wegiz/EHDS gap, a concrete route
- **Fixed-scope pilot** — your first interface live in 30 days, production-grade
- **Support retainer** — SLA, maintenance and on-call for ViscoSuite deployments
- **ViscoForge** — the operator console, with implementation and training

Contact: [viscosiety.com](https://viscosiety.com).

## License

[Apache License 2.0](LICENSE) — © 2026 Viscosiety B.V. See [NOTICE](NOTICE).
ViscoSuite is powered by the [Frank!Framework](https://frankframework.org), an open-source
integration framework by WeAreFrank!, and by [HAPI FHIR](https://hapifhir.io).
