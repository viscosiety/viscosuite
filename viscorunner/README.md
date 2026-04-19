# ViscoRunner

Docker packaging module for ViscoSuite. Assembles both WAR files into a single Tomcat image and provides two ready-to-run Docker Compose configurations.

## Prerequisites

**To run the application:**

- Docker ≥ 24 with Compose V2 (`docker compose` as a subcommand, not the standalone `docker-compose`)
- A `secrets/credentials.properties` file (required before first run):

```bash
cp secrets/credentials.properties.example secrets/credentials.properties
```

The file can stay empty for the demo mode; it only needs values when you add configurations that reference credential aliases.

**To run the schema update scripts** (`scripts/update-frankconfig-xsd.sh`, `scripts/update-fhir-xsd.sh`):

- `mvn` (Maven 3.9+) — the scripts call `mvn` directly to download artifacts from Maven Central. Install via [maven.apache.org](https://maven.apache.org/download.cgi) or a package manager (`brew install maven`, `apt-get install maven`).
- `unzip` — used to extract XSD files from downloaded JARs. Pre-installed on most systems; on Debian/Ubuntu: `apt-get install unzip`.

---

## Mode 1 — Demo

Runs the full reference implementation: HL7v2-to-FHIR conversion, FHIR R4/DSTU3 endpoints, and the fake-EMR integration.

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml up --build
```

The demo overlay:
- Mounts `demo-configurations/` as the Frank!Framework configuration directory and enables auto-discovery, so all three reference configurations are loaded without any manual setup.
- Replaces the base Tomcat context with `conf-demo/context.xml`, which adds the `jdbc/fake-emr` JNDI datasource needed by the fake-EMR configuration.

### What loads

| F!F Configuration | Description |
|---|---|
| `hl7v2-to-fhir` | Receives HL7v2 ADT messages over HTTP or MLLP and converts them to FHIR R4 Bundles |
| `fhir-to-fhir` | FHIR R4 and DSTU3 endpoints (bundle transactions, patient reads) bridged to F!F pipelines |
| `fake-emr` | Demonstrates querying a PostgreSQL-backed EMR and emitting FHIR Bundles |

### Key endpoints (all at `http://localhost:8180`)

| Endpoint | Description |
|---|---|
| `/viscolink/iaf/` | Frank!Console and Ladybug flow debugger |
| `POST /viscolink/fhir/r4/fhir-to-fhir` | FHIR R4 bundle transaction |
| `GET /viscolink/fhir/r4/fhir-to-fhir/Patient/{id}` | FHIR R4 patient read |
| `POST /viscolink/fhir/r5/fhir-to-fhir` | FHIR R5 bundle transaction |
| `GET /viscolink/fhir/r5/fhir-to-fhir/Patient/{id}` | FHIR R5 patient read |
| `POST /viscolink/fhir/dstu3/fhir-to-fhir` | FHIR DSTU3 bundle transaction |
| `GET /viscolink/fhir/dstu3/fhir-to-fhir/Patient/{id}` | FHIR DSTU3 patient read |
| `POST /viscolink/hl7v2` | HL7v2 message ingestion (HTTP) |
| `/viscostore/fhir` | HAPI FHIR JPA Server REST API |
| `/viscostore/tester/` | Interactive FHIR Tester UI |
| `/viscostore/fhir/swagger-ui/` | Swagger API docs |
| `POST /viscostore/mcp/messages` | MCP Streamable HTTP (AI/LLM integration) |

---

## Mode 2 — Own configurations

Starts the platform with zero F!F configurations loaded. Use this as the starting point when building your own integrations.

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
conf-demo/                  Demo Tomcat context — adds jdbc/fake-emr on top of the base resources
configurations/             Mount point for user-created F!F configurations
│                           Empty by default; contains FrankConfig.xsd for IDE support
demo-configurations/        Reference implementation F!F configurations (hl7v2-to-fhir, fhir-to-fhir, fake-emr)
resources/                  Shared resources on Tomcat's shared.loader classpath (/opt/frank/resources/)
│   fhir-xsd/               FHIR XSD schemas — regenerate with scripts/update-fhir-xsd.sh
│   ├── dstu3/              fhir-single.xsd, fhir-xhtml.xsd, xml.xsd
│   ├── r4/
│   └── r5/
scripts/                    Developer utilities (see below)
secrets/                    Runtime credentials (gitignored; copy from .example)
src/scripts/                Build-time scripts baked into the Docker image (entrypoint, Tomcat settings)
```

The `conf` / `conf-demo` and `configurations` / `demo-configurations` pairs follow the same pattern: the base directory is for your own work; the `-demo` counterpart contains the reference implementation and is activated by the demo overlay.

---

## Updating schemas

### FrankConfig.xsd

The `FrankConfig.xsd` files in `configurations/` and `demo-configurations/` are used by IDEs to validate and autocomplete Frank!Framework XML. When the F!F version is bumped in `viscolink/pom.xml`, regenerate them:

```bash
./scripts/update-frankconfig-xsd.sh
```

The script reads the version from `viscolink/pom.xml`, downloads the matching `frankframework-core` JAR via Maven, and extracts the XSD into both directories.

### FHIR XSDs

The FHIR XSD schemas in `resources/fhir-xsd/` are used at runtime by the `hl7v2-to-fhir` configuration to validate produced FHIR Bundles. They are sourced from HAPI FHIR's `hapi-fhir-validation-resources-*` artifacts. When the HAPI version is bumped in `viscolink/pom.xml`, regenerate them:

```bash
./scripts/update-fhir-xsd.sh
```

The script reads `hapi.version` from `viscolink/pom.xml`, downloads the matching validation-resources JAR for each FHIR version (DSTU3, R4, R5) via Maven, and extracts `fhir-single.xsd`, `fhir-xhtml.xsd`, and `xml.xsd` into `resources/fhir-xsd/{dstu3,r4,r5}/`.

The `resources/` directory is mounted at `/opt/frank/resources/` in the container, which is on Tomcat's `shared.loader` classpath — making the schemas accessible to all F!F configurations by the path `fhir-xsd/{version}/fhir-single.xsd`.

---

## Ports

| Host port | Container port | Purpose |
|---|---|---|
| `8180` | `8080` | HTTP (viscolink + viscostore) |
| `5005` | `5005` | JPDA remote debugger |
| `2575` | `2575` | MLLP inbound (HL7v2 over TCP) |
