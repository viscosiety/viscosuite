# ViscoSuite

ViscoSuite is a self-hosted healthcare integration platform. It combines two components:

- **ViscoLink** — a Frank!Framework integration layer that receives, validates, transforms, and routes healthcare messages from source systems into ViscoStore. Supports HL7v2 (MLLP and HTTP), FHIR, REST, and database-backed integrations. Extend by dropping Frank!Framework XML configurations into a mounted directory — no rebuild required.
- **ViscoStore** — a HAPI FHIR JPA Server that acts as the canonical FHIR repository. It exposes a standard FHIR REST API, a browser-based tester UI, Swagger docs, and an MCP endpoint for AI/LLM integration.

Both run as WARs inside a single Tomcat instance, packaged by **ViscoRunner**.

## Architecture

```
viscoSuite/
├── viscolink/               Frank!Framework integration middleware
├── viscostore/              HAPI FHIR JPA Server (persistent FHIR storage + MCP)
└── viscorunner/             Docker packaging and configuration hub
    ├── configurations/          empty scaffold — mount your own integrations here
    ├── demo-configurations/     reference configurations (hl7v2-to-fhir, hl7v2-to-xml, fhir-to-fhir, fhir-store-proxy, loinc-mapping-api, fake-emr)
    ├── docker-compose.yml       base service definitions
    └── docker-compose.demo.yml  demo overlay (activates demo-configurations + RabbitMQ)
```

Modules are built in reactor order: `viscolink` → `viscostore` → `viscorunner`.

**ViscoLink owns the integration concern.** It receives messages from source systems, validates and transforms them through F!F pipelines, and writes results to ViscoStore via the FHIR REST API. It holds no persistent state of its own — every pipeline execution is stateless and every outcome ends up in ViscoStore. Source systems talk to ViscoLink; ViscoLink talks to ViscoStore.

**ViscoStore owns the persistence concern.** It is a standard HAPI FHIR JPA Server with no integration logic. It stores FHIR resources, serves them over a FHIR REST API, and exposes an MCP endpoint for AI/LLM access. Consumers that only need to query stored data — a clinical dashboard, an AI assistant, a reporting tool — go directly to ViscoStore without passing through ViscoLink.

This separation means the two components can evolve independently: ViscoStore can be replaced with any FHIR-compliant server, ViscoLink can route to multiple targets, and neither side carries the concerns of the other.

## Why Frank!Framework

Most integration middleware in healthcare falls into one of two traps: either configuration lives in a proprietary database — invisible to version control, CI/CD, and locked into a vendor silo you cannot migrate out of — or pipelines are written as imperative scripts that require a full build cycle for every change. Frank!Framework avoids both.

**Config-first, git-native.** Every integration is a plain XML file that lives in version control alongside the rest of the codebase. It gets code review, branching, CI, and rollback for free. Tools that store configuration in a proprietary database or GUI-generated binary make this impossible — the config cannot be diffed, reviewed in a pull request, or rolled back atomically with the application code it serves. F!F inverts this: the configuration *is* the source of truth.

**Stateless and DevOps-friendly.** F!F pipelines are stateless — each message flows through independently with no shared in-memory state between executions. Containers can be replaced, scaled horizontally, or rolled back without session drain or coordination. Health checks are trivial. CI/CD is natural: configurations are files, tested in git, deployed by volume mount. Integration platforms that embed session state, channel locks, or in-process queues make zero-downtime deployments fragile. F!F avoids this entirely.

**Declarative transformations, LLM-friendly.** F!F pipelines transform data through XSLT stylesheets and equivalent declarative mapping documents, not imperative scripts. Declarative transformations are pure functions: given an input they produce an output with no side effects and no implicit runtime state. This matters especially in LLM-aided development. An LLM can generate an XSLT from a mapping description, validate it against a FHIR profile or HL7v2 schema, explain what a transformation does, or review a diff in a pull request — because the transformation is a *document* with a known grammar. Script-based middleware embeds transformation logic as JavaScript or Groovy with implicit dependencies and side effects; LLMs can produce such code, but cannot reliably validate it against a structural contract. When the pipeline config itself is also a document (F!F XML, validated by FrankConfig.xsd), the entire integration surface is reviewable, generatable, and auditable by an LLM.

**Open source, proven in the public sector — applied to healthcare.** F!F has a strong track record in Dutch public sector and corporate integration. ViscoSuite is the healthcare-first implementation: it adds HL7v2 (MLLP and HTTP), FHIR validation, and clinical data routing on top of a battle-tested open-source engine. EHR-bundled integration products are locked to one vendor's data model. General-purpose enterprise service buses carry large operational footprints and expensive licensing. Code-first integration frameworks require a build and redeploy for every pipeline change. F!F combines file-based configuration and a no-rebuild deployment model in a fully open-source package; ViscoSuite makes that foundation healthcare-ready.

## What ViscoSuite adds

Frank!Framework provides the pipeline engine, tooling, and runtime. ViscoSuite extends it with healthcare-specific components and a purpose-built operational interface.

**Custom pipes**

| Pipe | Description |
|---|---|
| `Hl7v2ToXmlPipe` | Converts pipe-delimited HL7v2 to HL7v2 XML Encoding Syntax using HAPI HL7v2; supports version enforcement and message validation |
| `XmlToHl7v2Pipe` | Inverse: converts HL7v2 XML back to pipe-delimited format for MLLP transmission or ACK generation |
| `FhirValidatorPipe` | Validates FHIR resources (XML or JSON) against R4, R5, or DSTU3 profiles using the HAPI FHIR instance validator; routes to an exception forward with an `OperationOutcome` on failure |

**Custom listener and sender**

| Component | Description |
|---|---|
| `MllpListener` | TCP server that accepts persistent MLLP connections, frames HL7v2 messages, and returns synchronous ACKs |
| `MllpSender` | TCP client sender that maintains persistent connections to remote MLLP endpoints and reads ACK responses |
| `FhirListener` | Extends F!F's JavaListener; registers FHIR operation endpoints (read, search, bundle-transaction, proxy) with ViscoLink's FHIR facade servlet |

**ViscoFlow**

F!F records every pipeline execution as a structured trace — input and output at every pipe, session key values, the forward taken, and duration — stored via its Ladybug debugger backbone. ViscoFlow is a purpose-built frontend on top of this: it surfaces those traces with healthcare context (patient ID, correlation ID, flow name, pipeline exit state) and makes them navigable without Ladybug's developer-oriented interface. Filtering by patient or flow, inspecting a specific message's transformation step by step, and auditing routing decisions are first-class operations — accessible to integration engineers and clinical informatics staff alike.

## Prerequisites

| Tool | Version | Purpose |
|---|---|---|
| Docker with Compose V2 | Docker ≥ 24 | Running the application (`docker compose` subcommand) |
| Java JDK | 21+ | Building from source |
| `mvn` (Maven) | 3.9+ | Schema update script (`update-frankconfig-xsd.sh`) |

The Maven **wrapper** (`./mvnw`) is included and downloads the correct Maven version automatically for all `./mvnw` build commands — no separate Maven installation needed for those. The update script is the only place that calls `mvn` directly.

---

## Quick Start

```bash
# Demo mode — no setup required, all reference configurations load automatically
cd viscorunner && docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build

# Base mode — create the credentials file first, then start blank
cp viscorunner/secrets/credentials.properties.example viscorunner/secrets/credentials.properties
cd viscorunner && docker compose up --build
```

The application is available at `http://localhost:8180`. See `viscorunner/README.md` for a full breakdown of both modes, how to add your own Frank!Framework configurations, and how to wire JNDI datasources.

## Key Endpoints

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

## Reference Implementations

The demo overlay ships six working Frank!Framework configurations that you can use as starting points:

| Configuration | What it shows |
|---|---|
| `hl7v2-to-fhir` | Receives HL7v2 ADT (A01/A02/A03/A04/A08/A11/A13) and SIU (S12/S13/S14/S15) messages over HTTP or MLLP and converts them to FHIR R4 Bundles via XSLT |
| `hl7v2-to-xml` | Converts HL7v2 to a structured XML representation — useful as a preprocessing step or standalone inspection tool |
| `fhir-to-fhir` | FHIR R4 / DSTU3 / R5 facade endpoints that route through ViscoLink pipelines into ViscoStore |
| `fhir-store-proxy` | Transparent reverse proxy from a ViscoLink FHIR endpoint to ViscoStore, with credential injection |
| `loinc-mapping-api` | Looks up LOINC codes from a CSV file and returns enriched FHIR Observations |
| `fake-emr` | Queries a PostgreSQL-backed fake EMR and emits FHIR Bundles — demonstrates database-sourced FHIR |

## MCP Integration

ViscoStore exposes FHIR resources as [MCP](https://modelcontextprotocol.io) tools via Spring AI, enabling AI assistants to query and write FHIR data. Configure in Claude Desktop or Cursor:

```json
{
  "mcpServers": {
    "viscosuite": {
      "url": "http://localhost:8180/viscostore/mcp/messages"
    }
  }
}
```

## Build Commands

A Maven wrapper is included — `./mvnw` downloads and uses the correct version automatically.

```bash
# Build all modules
# viscorunner's copy-dependencies reads from .m2/, so viscolink and viscostore
# must be installed before viscorunner is packaged.
./mvnw install -pl viscolink,viscostore && ./mvnw package -pl viscorunner

# Build a single module (viscolink or viscostore only)
./mvnw install -pl viscolink
./mvnw install -pl viscostore

# Run tests
./mvnw test -pl viscostore          # unit tests
./mvnw verify -pl viscostore        # unit + integration tests
```

## Remote Debugging

Port `5005` is exposed for JPDA remote debugging. To attach IntelliJ before startup breakpoints are hit, set `suspend=y` in `docker-compose.yml`:

```yaml
JAVA_OPTS: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
```

Then add two **Before launch** steps to your IntelliJ Remote JVM Debug configuration:
1. `docker compose -f .../docker-compose.yml up --build -d`
2. `bash -c "while ! nc -z localhost 5005 2>/dev/null; do sleep 1; done"`

## Smoke Tests

Integration smoke tests are IntelliJ HTTP Client `.rest` files in `viscostore/src/test/smoketest/`. They must be run sequentially. Configure the target server in `http-client.env.json`.
