# ViscoSuite

ViscoSuite is a self-hosted healthcare integration platform. It combines two components:

- **ViscoLink** — a Frank!Framework integration middleware that accepts messages in any protocol (HL7v2 MLLP, HL7v2 over HTTP, REST, FHIR, database queries, …) and routes or transforms them into FHIR resources. You extend it by dropping Frank!Framework XML configurations into a mounted directory and reloading — no rebuild required.
- **ViscoStore** — a HAPI FHIR JPA Server that acts as the canonical FHIR R4 repository. It exposes a standard FHIR REST API, a browser-based tester UI, Swagger docs, and an MCP endpoint for AI/LLM integration.

Both run as WARs inside a single Tomcat instance, packaged by **ViscoRunner**.

## Architecture

```
viscoSuite/
├── viscolink/               Frank!Framework integration middleware
├── viscostore/              HAPI FHIR JPA Server (persistent FHIR R4 storage + MCP)
└── viscorunner/             Docker packaging and configuration hub
    ├── configurations/          empty scaffold — mount your own integrations here
    ├── demo-configurations/     reference configurations (hl7v2-to-fhir, fhir-to-fhir, fhir-store-proxy, loinc-mapping-api, fake-emr)
    ├── docker-compose.yml       base service definitions
    └── docker-compose.demo.yml  demo overlay (activates demo-configurations)
```

Modules are built in reactor order: `viscolink` → `viscostore` → `viscorunner`.

## Prerequisites

| Tool | Version | Purpose |
|---|---|---|
| Docker with Compose V2 | Docker ≥ 24 | Running the application (`docker compose` subcommand) |
| Java JDK | 21+ | Building from source |
| `mvn` (Maven) | 3.9+ | Schema update scripts (`update-frankconfig-xsd.sh`, `update-fhir-xsd.sh`) |
| `unzip` | any | Same schema update scripts (extracts XSDs from downloaded JARs) |

The Maven **wrapper** (`./mvnw`) is included and downloads the correct Maven version automatically for all `./mvnw` build commands — no separate Maven installation needed for those. The update scripts are the only place that call `mvn` directly.

`unzip` is pre-installed on most Linux distributions and macOS. On Debian/Ubuntu: `apt-get install unzip`.

---

## Quick Start

```bash
# 1. Create credentials file (required before first run)
cp viscorunner/secrets/credentials.properties.example viscorunner/secrets/credentials.properties

# 2a. Run the demo (loads all reference configurations out of the box)
cd viscorunner && docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build

# 2b. Or start blank — bring your own integrations
cd viscorunner && docker compose up --build
```

The application is available at `http://localhost:8180`. See `viscorunner/README.md` for a full breakdown of both modes, how to add your own Frank!Framework configurations, and how to wire JNDI datasources.

## Key Endpoints

| Endpoint | Description |
|---|---|
| `/viscolink/iaf/` | Frank!Console / Ladybug flow debugger |
| `POST /viscolink/fhir/r4/{facadeName}` | FHIR R4 bundle transaction |
| `GET /viscolink/fhir/r4/{facadeName}/Patient/{id}` | FHIR R4 patient read |
| `POST /viscolink/fhir/r5/{facadeName}` | FHIR R5 bundle transaction |
| `GET /viscolink/fhir/r5/{facadeName}/Patient/{id}` | FHIR R5 patient read |
| `POST /viscolink/fhir/dstu3/{facadeName}` | FHIR DSTU3 bundle transaction |
| `GET /viscolink/fhir/dstu3/{facadeName}/Patient/{id}` | FHIR DSTU3 patient read |
| `/viscostore/fhir` | FHIR R4 REST API (HAPI JPA Server) |
| `/viscostore/tester/` | Interactive FHIR Tester UI |
| `/viscostore/fhir/swagger-ui/` | Swagger API docs |
| `POST /viscostore/mcp/messages` | MCP Streamable HTTP (AI/LLM integration) |

Ports: `8180` HTTP · `2575` MLLP (HL7v2 over TCP) · `5005` JPDA debugger.

## Reference Implementations

The demo overlay ships five working Frank!Framework configurations that you can use as starting points:

| Configuration | What it shows |
|---|---|
| `hl7v2-to-fhir` | Receives HL7v2 ADT messages over HTTP or MLLP and converts them to FHIR R4 Bundles via XSLT |
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

Integration smoke tests are IntelliJ HTTP Client `.rest` files in `viscostore/src/test/smoketest/`. They must be run sequentially. Requires IntelliJ Ultimate; configure the target server in `http-client.env.json`.
