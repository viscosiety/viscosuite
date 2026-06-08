# ViscoRunner

Docker packaging module for ViscoSuite. Assembles both WAR files into a single Tomcat image and provides two ready-to-run Docker Compose configurations.

## Prerequisites

**To run the application:**

- Docker ≥ 24 with Compose V2 (`docker compose` as a subcommand, not the standalone `docker-compose`)

**To run the schema update script** (`scripts/update-frankconfig-xsd.sh`):

- `mvn` (Maven 3.9+) — the script calls `mvn` directly to download artifacts from Maven Central. Install via [maven.apache.org](https://maven.apache.org/download.cgi) or a package manager (`brew install maven`, `apt-get install maven`).

---

## Mode 1 — Demo

Runs the full reference implementation: HL7v2-to-FHIR conversion, SIU appointment scheduling, FHIR R4/DSTU3/R5 endpoints, LOINC enrichment, and the fake-EMR integration. Also starts RabbitMQ for AMQP-based event routing.

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build
```

The demo overlay:
- Mounts `demo-configurations/` as the Frank!Framework configuration directory with auto-discovery enabled, so all reference configurations load without manual setup.
- Replaces the base Tomcat context with `demo-conf/context.xml`, which adds the `jdbc/fake-emr` JNDI datasource.
- Starts a RabbitMQ instance and wires it to ViscoLink via the AMQP event bus (`amqp.events.active=true`).
- Mounts `demo-tools/` at `/opt/frank/webapp-overlay/viscolink/demo-tools/` so the browser tools are served without a WAR rebuild.
- Mounts `demo-resources/resources.yml` with MLLP listener (`inbound-2575`) and AMQP connection pre-configured.

### What loads

| F!F Configuration | Description |
|---|---|
| `hl7v2-to-fhir` | Receives HL7v2 ADT (A01/A02/A03/A04/A08/A11/A13) and SIU (S12/S13/S14/S15) messages over HTTP (`POST /viscolink/api/hl7v2`) or MLLP (port 2575) and converts them to FHIR R4 Bundles |
| `hl7v2-to-xml` | Converts HL7v2 messages to a structured XML representation |
| `fhir-to-fhir` | FHIR R4 / DSTU3 / R5 facade endpoints (bundle transactions, patient reads) bridged to F!F pipelines |
| `fhir-store-proxy` | Transparent reverse proxy from a ViscoLink FHIR endpoint to ViscoStore, with credential injection |
| `loinc-mapping-api` | CRUD API for the LOINC mapping table; used by the lab-enrichment facade to inject LOINC codings into uncoded Observations |
| `fake-emr` | Demonstrates querying a PostgreSQL-backed EMR and emitting FHIR Bundles |

### Demo tools

Three browser-based tools are served directly from ViscoLink in demo mode and accessible from the `/viscolink/` launcher:

| Tool | URL | Purpose |
|---|---|---|
| ViscoFlow | `/viscolink/flow/` | Live pipeline trace viewer — shows every message flowing through F!F adapters with per-pipe input/output and forward routing |
| Lab Code Mapper | `/viscolink/demo-tools/loinc-mapping-ui.html` | CRUD UI for the LOINC mapping table consumed by the `loinc-mapping-api` configuration. Illustrates a standalone tool with a specific functional purpose, served via the webapp overlay mechanism without modifying the WAR. |
| Demo Pipeline Tester | `/viscolink/demo-tools/test-client.html` | Drives all demo pipelines end-to-end from the browser — sends HL7v2 ADT/SIU messages, FHIR requests, and EMR queries with configurable parameters and shows raw responses |

Tools are mounted via `demo-tools:/opt/frank/webapp-overlay/viscolink/demo-tools:ro` in the demo overlay. New tools can be added to `demo-tools/` without rebuilding the image.

### Key endpoints (all at `http://localhost:8180`)

| Endpoint | Description |
|---|---|
| `/` | ViscoSuite landing page — probes services and shows navigation cards |
| `/viscolink/` | ViscoLink app launcher (tools registry + Frank!Console link) |
| `/viscolink/flow/` | ViscoFlow — live message flow viewer and pipeline trace debugger |
| `/viscolink/demo-tools/test-client.html` | Demo Pipeline Tester — drive all demo pipelines from the browser |
| `/viscolink/demo-tools/loinc-mapping-ui.html` | Lab Code Mapper — CRUD UI for LOINC mappings |
| `/viscolink/iaf/` | Frank!Console and Ladybug flow debugger |
| `POST /viscolink/api/hl7v2` | HL7v2 message ingestion over HTTP |
| `GET /viscolink/api/emr/patient/{id}` | Fake-EMR → FHIR patient pipeline |
| `GET /viscolink/fhir/r4/loinc-enriched/Observation` | LOINC-enriched Observation search |
| `POST /viscolink/fhir/r4/fhir-to-fhir` | FHIR R4 bundle transaction |
| `GET /viscolink/fhir/r4/fhir-to-fhir/Patient/{id}` | FHIR R4 patient read |
| `POST /viscolink/fhir/r5/fhir-to-fhir` | FHIR R5 bundle transaction |
| `GET /viscolink/fhir/r5/fhir-to-fhir/Patient/{id}` | FHIR R5 patient read |
| `POST /viscolink/fhir/dstu3/fhir-to-fhir` | FHIR DSTU3 bundle transaction |
| `GET /viscolink/fhir/dstu3/fhir-to-fhir/Patient/{id}` | FHIR DSTU3 patient read |
| `/viscostore/fhir` | HAPI FHIR JPA Server REST API |
| `/viscostore/tester/` | Interactive FHIR Tester UI |
| `/viscostore/fhir/swagger-ui/` | Swagger API docs |
| `POST /viscostore/mcp/messages` | MCP Streamable HTTP (AI/LLM integration) |

### Additional services (demo mode only)

| Service | Port | Description |
|---|---|---|
| RabbitMQ AMQP | `5672` | AMQP event bus (credentials: `viscosuite` / `viscosuite`) |
| RabbitMQ Management | `15672` | RabbitMQ management console |

---

## Mode 2 — Own configurations

Starts the platform with zero F!F configurations loaded. Use this as the starting point when building your own integrations.

Before the first run, create the credentials file:

```bash
cp secrets/credentials.properties.example secrets/credentials.properties
```

The file can stay empty initially; add entries when your configurations reference credential aliases.

```bash
docker compose up --build
```

### Adding a configuration

1. Create a subdirectory under `configurations/`, e.g. `configurations/my-integration/`.
2. Add a `Configuration.xml` (use `configurations/FrankConfig.xsd` for IDE validation and autocomplete).
3. Declare it in `docker-compose.yml`:
   ```yaml
   environment:
     configurations.names: my-integration
   ```
4. If your configuration reads files from the mounted directory (almost always the case), also add:
   ```yaml
   environment:
     configurations.my-integration.classLoaderType: DirectoryClassLoader
   ```
5. Start or reload via the Frank!Console (`/viscolink/iaf/`) — no rebuild required as long as the container is already running.

### Adding a JNDI datasource

Add a `<Resource>` entry to `conf/context.xml` and restart the container. The base context already provides `jdbc/viscolink` and `jdbc/viscostore`.

---

## Directory layout

```
conf/                       Base Tomcat context — JNDI datasources for viscolink and viscostore
demo-conf/                  Demo Tomcat context — adds jdbc/fake-emr on top of the base resources
demo-hapi-overlay/          Spring Boot config overlay for ViscoStore in demo mode
demo-rabbitmq/              RabbitMQ config and exchange/queue definitions for demo mode
demo-resources/             resources.yml with MLLP listener and AMQP connection pre-wired
demo-secrets/               Credentials for the demo mode (not for production)
demo-tools/                 Browser tools served at /viscolink/demo-tools/ in demo mode
│                           (test-client.html, loinc-mapping-ui.html)
configurations/             Mount point for user-created F!F configurations
│                           Empty by default; contains FrankConfig.xsd for IDE support
demo-configurations/        Reference F!F configurations:
│                           hl7v2-to-fhir, hl7v2-to-xml, fhir-to-fhir,
│                           fhir-store-proxy, loinc-mapping-api, fake-emr
postgres/                   PostgreSQL init scripts (database + schema setup)
scripts/                    Developer utilities (see below)
secrets/                    Runtime credentials (gitignored; copy from .example)
src/scripts/                Build-time scripts baked into the Docker image (entrypoint, Tomcat settings)
```

---

## Updating FrankConfig.xsd

The `FrankConfig.xsd` files in `configurations/` and `demo-configurations/` are used by IDEs to validate and autocomplete Frank!Framework XML. When the F!F version is bumped in `viscolink/pom.xml`, regenerate them:

```bash
./scripts/update-frankconfig-xsd.sh
```

The script reads the version from `viscolink/pom.xml`, downloads the matching `frankframework-core` JAR via Maven, and extracts the XSD into both directories.

---

## Ports

| Host port | Container port | Purpose |
|---|---|---|
| `8180` | `8080` | HTTP (viscolink + viscostore) |
| `5005` | `5005` | JPDA remote debugger |
| `2575` | `2575` | MLLP inbound (HL7v2 over TCP) |
| `5432` | `5432` | PostgreSQL |
| `5672` | `5672` | RabbitMQ AMQP (demo mode only) |
| `15672` | `15672` | RabbitMQ management (demo mode only) |
