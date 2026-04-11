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

## Quick Start

```bash
# 1. Create credentials file (required before first run)
cp viscorunner/secrets/credentials.properties.example viscorunner/secrets/credentials.properties

# 2. Build and start
cd viscorunner && docker compose up --build
```

The application is available at `http://localhost:8180`.

## Key Endpoints

| Endpoint | Description |
|---|---|
| `POST /viscolink/hl7v2` | HL7v2 message ingestion |
| `/viscolink/iaf/` | Frank!Console / Ladybug debugger (auth disabled in LOC) |
| `/viscostore/fhir` | FHIR R4 REST API |
| `/viscostore/tester/` | Interactive FHIR Tester UI |
| `/viscostore/fhir/swagger-ui/` | Swagger API docs |
| `POST /viscostore/mcp/messages` | MCP Streamable HTTP (AI/LLM integration) |

## Build Commands

```bash
# Build all modules
mvn package

# Build a single module
mvn package -pl viscolink
mvn package -pl viscostore

# Run tests
mvn test -pl viscostore          # unit tests
mvn verify -pl viscostore        # unit + integration tests
```

## HL7v2 → FHIR Flow

1. HTTP `POST /hl7v2` received by `HL7v2-over-http-to-FHIR` adapter
2. Message type extracted from MSH.9 (e.g. `ADT_A01`)
3. XSLT applied from `configurations/hl7v2-to-fhir/xslt/<MSG_TYPE>.xslt`
4. Output: FHIR R4 Bundle (MessageHeader + Patient + Encounter)

Currently supported: `ADT_A01` (Admit/Visit Notification).

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
| `viscorunner/docker-compose.yml` | Services, ports, environment overrides |
| `viscorunner/src/scripts/catalinaAdditional.properties` | Tomcat / Frank!Framework settings |
| `viscorunner/src/conf/context.xml` | Tomcat JNDI datasource |
| `viscolink/src/main/resources/DeploymentSpecifics.properties` | Frank!Framework base properties |
| `viscostore/src/main/resources/application.yaml` | HAPI FHIR / Spring Boot settings |

Frank!Framework configurations live in `viscorunner/configurations/` and are mounted into the container at `/opt/frank/configurations/` — they can be edited and reloaded via the Frank!Console without rebuilding.

## Smoke Tests

Integration smoke tests are IntelliJ HTTP Client `.rest` files in `viscostore/src/test/smoketest/`. They must be run sequentially. Requires IntelliJ Ultimate; configure the target server in `http-client.env.json`.
