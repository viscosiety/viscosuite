# ViscoRunner

Docker packaging module for ViscoSuite. Assembles the ViscoLink WAR, a vertical pack and (optionally) the ViscoStore WAR into Tomcat images, all from one `Dockerfile`, and provides ready-to-run Docker Compose configurations for each variant.

## Prerequisites

**To run the application:**

- Docker ≥ 24 with Compose V2 (`docker compose` as a subcommand, not the standalone `docker-compose`)

**To run the schema update script** (`scripts/update-frankconfig-xsd.sh`):

- `mvn` (Maven 3.9+) — the script calls `mvn` directly to download artifacts from Maven Central. Install via [maven.apache.org](https://maven.apache.org/download.cgi) or a package manager (`brew install maven`, `apt-get install maven`).

---

## Images and compose files

One `Dockerfile` builds every image. Two build arguments choose the variant:

| Argument | Values | Meaning |
|---|---|---|
| `PACK` | `health` (default), `core` | The vertical pack laid over the ViscoLink WAR: its libraries, console patch, demo configurations and identity. `core` is no pack at all: an empty overlay. |
| `STORE` | `viscostore` (default), `none` | Whether the ViscoStore WAR, and the `jdbc/viscostore` datasource, are part of the image. |

The Maven build stages what the `Dockerfile` copies: `target/packs/<pack>/` (the unpacked pack overlay, copied to `/opt/frank/webapp-overlay/viscolink/`), `target/demo/<pack>/` (the pack's demo configurations, baked into `/opt/frank/demo-configurations/` and never loaded unless mounted over `/opt/frank/configurations`) and `target/store/<store>/` (the ViscoStore WAR, or nothing). The Tomcat context file is `conf/context-<store>.xml`. At build time the pack's `WEB-INF/pack.properties` (id, display name, version, tagline, link blurb) stamps the landing page.

| Compose file | `PACK` | `STORE` | Stack |
|---|---|---|---|
| `docker-compose.yml` | `health` | `viscostore` | The suite: ViscoLink + ViscoStore + PostgreSQL (the `viscorunner` image) |
| `docker-compose.viscolink.yml` | `health` | `none` | ViscoLink with the health pack, no store (the `viscolink` image) |
| `docker-compose.core.yml` | `core` | `none` | The core: no pack, no store, no FHIR (`viscolink:latest-core`) |

The other compose files are overlays or single-purpose stacks: `docker-compose.demo.yml` (Mode 1, layered on the suite), `docker-compose.debug.yml` and `docker-compose.viscolink.debug.yml` (JPDA on `5005`), `docker-compose.git.yml` (git-sourced configurations, layered on the demo) and `docker-compose.viscostore.yml` (the standalone ViscoStore image). All three stacks above mount `secrets/credentials.properties` (see Mode 2).

```bash
docker compose up --build                                  # the suite
docker compose -f docker-compose.viscolink.yml up --build  # health pack, no store
docker compose -f docker-compose.core.yml up --build       # the core
```

The core's demo is a one-adapter echo: mount `../viscolink/demo-configurations` over `/opt/frank/configurations` (or copy `echo/` into your own configuration directory) and `POST /viscolink/api/echo?subjectId=SUBJ-001`; Ladybug and ViscoFlow show the id in the Subject column.

**Databases.** The suite's PostgreSQL runs everything in `postgres/` as init scripts; `init-databases.sql` creates `viscolink`, `viscostore` and `ladybug`. The two store-less stacks create only `viscolink` (through `POSTGRES_DB`) and mount `postgres/init-ladybug.sql` for Ladybug's own database; without a `ladybug` database the context never starts. That file is idempotent (`\gexec` creates the database only when it is missing), so it is harmless in the suite, where both files run.

**Gotcha.** The suite compose names its PostgreSQL container `postgres`. A stopped container with that name left from an earlier run makes `docker compose up` fail on the name conflict; remove it (`docker rm postgres`) first.

---

## Mode 1 — Demo

Runs the full reference implementation: HL7v2-to-FHIR conversion, SIU appointment scheduling, FHIR R4/DSTU3/R5 endpoints, LOINC enrichment, and the fake-EMR integration. Also starts RabbitMQ for AMQP-based event routing.

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build
```

The demo overlay:
- Mounts `../packs/health/demo-configurations/` as the Frank!Framework configuration directory with auto-discovery enabled, so all reference configurations load without manual setup.
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

Add a `<Resource>` entry to `conf/context-viscostore.xml` (the suite) or `conf/context-none.xml` (store-less images) and rebuild (`docker compose up --build`), or mount your own file over `/usr/local/tomcat/conf/context.xml` as the demo overlay does. `context-viscostore.xml` provides `jdbc/viscolink`, `jdbc/ladybug` and `jdbc/viscostore`; `context-none.xml` the first two.

---

## Directory layout

```
conf/                       Tomcat context files context-viscostore.xml and context-none.xml (picked by STORE),
│                           server.xml, and the /viscolink PreResources overlay definition
demo-conf/                  Demo Tomcat context — adds jdbc/fake-emr on top of the base resources
demo-hapi-overlay/          Spring Boot config overlay for ViscoStore in demo mode
demo-rabbitmq/              RabbitMQ config and exchange/queue definitions for demo mode
demo-resources/             resources.yml with MLLP listener and AMQP connection pre-wired
demo-secrets/               Credentials for the demo mode (not for production)
demo-tools/                 Browser tools served at /viscolink/demo-tools/ in demo mode
│                           (test-client.html, loinc-mapping-ui.html)
configurations/             Mount point for user-created F!F configurations
│                           Empty by default; contains FrankConfig.xsd for IDE support
../packs/health/demo-configurations/
│                           Reference F!F configurations (they live with the health pack):
│                           hl7v2-to-fhir, hl7v2-to-xml, fhir-to-fhir,
│                           fhir-store-proxy, loinc-mapping-api, fake-emr
../viscolink/demo-configurations/
│                           The core's neutral echo demo
postgres/                   PostgreSQL init scripts (database + schema setup; init-ladybug.sql for the store-less stacks)
src/packs/core/             The core pack's identity (WEB-INF/pack.properties); the health pack's lives in packs/health
scripts/                    Developer utilities (see below)
secrets/                    Runtime credentials (gitignored; copy from .example)
src/scripts/                Build-time scripts baked into the Docker image (entrypoint, Tomcat settings)
```

---

## Updating FrankConfig.xsd

The `FrankConfig.xsd` files in `configurations/`, `../packs/health/demo-configurations/` and `../viscolink/demo-configurations/` are used by IDEs to validate and autocomplete Frank!Framework XML. When the F!F version is bumped in `viscolink/pom.xml`, regenerate them:

```bash
./scripts/update-frankconfig-xsd.sh
```

The script reads the version from `viscolink/pom.xml`, downloads the matching `frankframework-core` JAR via Maven, and extracts the XSD into all three directories.

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
