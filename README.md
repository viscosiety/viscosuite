# ViscoSuite

A healthcare integration platform that bridges HL7v2 and FHIR R4. Deployed as two WARs in a shared Tomcat instance via Docker Compose.

## Architecture

```
viscoSuite/
├── viscolink/     Frank!Framework integration middleware (HL7v2 → FHIR transformation)
├── viscostore/    HAPI FHIR JPA Server (persistent FHIR R4 storage + MCP)
└── viscorunner/   Docker packaging module (assembles image, docker-compose)
```

Modules are built in reactor order: `viscolink` → `viscostore` → `viscorunner`.

## Prerequisites

| Tool | Version | Purpose |
|---|---|---|
| Docker with Compose V2 | Docker ≥ 24 | Running the application (`docker compose` subcommand) |
| Java JDK | 21+ | Building from source |
| `mvn` (Maven) | 3.9+ | Schema update scripts (`update-frankconfig-xsd.sh`, `update-fhir-xsd.sh`) |
| `unzip` | any | Same schema update scripts (extracts XSDs from downloaded JARs) |

The Maven **wrapper** (`./mvnw`) is included and downloads the correct Maven version automatically for all `./mvnw` build commands — no separate Maven installation is needed for those. The update scripts are the only place that call `mvn` directly.

`unzip` is pre-installed on most Linux distributions and macOS. On macOS it ships with Xcode Command Line Tools; on Debian/Ubuntu: `apt-get install unzip`.

---

## Quick Start

```bash
# 1. Create credentials file (required before first run)
cp viscorunner/secrets/credentials.properties.example viscorunner/secrets/credentials.properties

# 2a. Run the demo (full reference implementation — HL7v2→FHIR, FHIR R4/DSTU3, fake-EMR)
cd viscorunner && docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build

# 2b. Or start with zero configurations as a blank slate for your own integrations
cd viscorunner && docker compose up --build
```

The application is available at `http://localhost:8180`. See `viscorunner/README.md` for full details on both modes.

## Key Endpoints

| Endpoint | Description |
|---|---|
| `POST /viscolink/hl7v2` | HL7v2 message ingestion |
| `/viscolink/iaf/` | Frank!Console / Ladybug debugger (auth disabled in LOC) |
| `POST /viscolink/fhir/r4/{facadeName}` | FHIR R4 bundle transaction (demo: `fhir-to-fhir`) |
| `GET /viscolink/fhir/r4/{facadeName}/Patient/{id}` | FHIR R4 patient read |
| `POST /viscolink/fhir/r5/{facadeName}` | FHIR R5 bundle transaction |
| `GET /viscolink/fhir/r5/{facadeName}/Patient/{id}` | FHIR R5 patient read |
| `POST /viscolink/fhir/dstu3/{facadeName}` | FHIR DSTU3 bundle transaction |
| `GET /viscolink/fhir/dstu3/{facadeName}/Patient/{id}` | FHIR DSTU3 patient read |
| `/viscostore/fhir` | FHIR R4 REST API (HAPI JPA Server) |
| `/viscostore/tester/` | Interactive FHIR Tester UI |
| `/viscostore/fhir/swagger-ui/` | Swagger API docs |
| `POST /viscostore/mcp/messages` | MCP Streamable HTTP (AI/LLM integration) |

## Build Commands

A Maven wrapper is included so you don't need Maven installed locally — `./mvnw` downloads and uses the correct version automatically.

```bash
# Build all modules
./mvnw package

# Build a single module
./mvnw package -pl viscolink
./mvnw package -pl viscostore

# Run tests
./mvnw test -pl viscostore          # unit tests
./mvnw verify -pl viscostore        # unit + integration tests
```

## HL7v2 → FHIR Flow

1. HTTP `POST /hl7v2` received by `HL7v2-over-http-to-FHIR` adapter
2. Message type extracted from MSH.9 (e.g. `ADT_A01`)
3. XSLT applied from `demo-configurations/hl7v2-to-fhir/xslt/<MSG_TYPE>.xslt`
4. Output: FHIR R4 Bundle (MessageHeader + Patient + Encounter)

Currently supported: `ADT_A01` (Admit/Visit Notification). Requires the demo overlay (see Quick Start 2a).

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

## Remote Debugging

Port `5005` is exposed for JPDA remote debugging. To attach IntelliJ before startup breakpoints are hit, set `suspend=y` in `docker-compose.yml`:

```yaml
JAVA_OPTS: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005"
```

Then add two **Before launch** steps to your IntelliJ Remote JVM Debug configuration:
1. `docker compose -f .../docker-compose.yml up --build -d`
2. `bash -c "while ! nc -z localhost 5005 2>/dev/null; do sleep 1; done"`

## Configuration

| File | Purpose |
|---|---|
| `viscorunner/docker-compose.yml` | Base services, ports, environment overrides |
| `viscorunner/docker-compose.demo.yml` | Demo overlay — mounts reference configurations and enables auto-discovery |
| `viscorunner/src/scripts/catalinaAdditional.properties` | Tomcat / Frank!Framework settings |
| `viscorunner/conf/context.xml` | Tomcat JNDI datasources (viscolink + viscostore) |
| `viscorunner/conf-demo/context.xml` | Demo context — adds `jdbc/fake-emr` on top of base |
| `viscolink/src/main/resources/DeploymentSpecifics.properties` | Frank!Framework base properties |
| `viscostore/src/main/resources/application.yaml` | HAPI FHIR / Spring Boot settings |

Frank!Framework configurations:
- `viscorunner/configurations/` — empty scaffold for your own integrations; mounted at `/opt/frank/configurations/` in the base compose
- `viscorunner/demo-configurations/` — reference implementation (`hl7v2-to-fhir`, `fhir-to-fhir`, `fake-emr`); activated by the demo overlay

Configurations can be edited and reloaded via the Frank!Console without rebuilding.

## Smoke Tests

Integration smoke tests are IntelliJ HTTP Client `.rest` files in `viscostore/src/test/smoketest/`. They must be run sequentially. Requires IntelliJ Ultimate; configure the target server in `http-client.env.json`.
