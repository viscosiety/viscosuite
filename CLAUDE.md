# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build all modules and install to local repo (required before docker compose up --build)
./mvnw install -pl viscolink,viscostore
./mvnw package -pl viscorunner

# Or in one step:
./mvnw install -pl viscolink,viscostore && ./mvnw package -pl viscorunner

# WARNING: copy-dependencies in viscorunner always resolves from .m2/ (not the reactor).
# Always run `mvn install` for viscolink and/or viscostore before building viscorunner,
# otherwise stale .m2/ artifacts end up in the Docker image.
# Never run `mvn package -pl viscorunner` alone after changing viscolink or viscostore.

# Run unit tests
mvn test -pl viscostore

# Run unit + integration tests (maven-failsafe-plugin)
mvn verify -pl viscostore

# Run a single test
mvn test -pl viscostore -Dtest=ClassName#methodName

# Build and start with Docker Compose (host port 8180 → container 8080)
cd viscorunner && docker compose up --build
```

Before running `docker compose up`, create `viscorunner/secrets/credentials.properties` (mounted at `/opt/frank/secrets/credentials.properties` inside the container). See `catalinaAdditional.properties` for the credential factory configuration.

## Architecture

This is a Maven multi-module project for a healthcare integration platform that bridges HL7v2 and FHIR. The three modules are built in reactor order: `viscolink` → `viscostore` → `viscorunner`.

### viscolink (Frank!Framework WAR)
An integration middleware layer based on Frank!Framework (version pinned by `frankframework.version` in `viscolink/pom.xml`), deployed at `/viscolink`.

- **Frank!Console / Ladybug debugger**: `/viscolink/iaf/` (auth disabled in LOC stage)
- **FHIR endpoint**: `SimpleFhirServer` in `com.viscosiety.fhir` provides a basic FHIR R4 plain server (currently a demo Patient provider only)
- **Configurations** live in `src/main/configurations/` and are loaded from `/opt/frank/configurations/` at runtime (outside the WAR, so they can be updated without rebuilding):
  - `viscolink/` — empty placeholder for future adapters
  - `hl7v2-to-fhir/` — receives HL7v2 messages and converts to FHIR R4

**HL7v2 → FHIR flow:**
1. `HL7v2-over-http-to-FHIR` adapter receives HTTP POST to `ApiListener` at `/hl7v2`
2. Forwards to `HL7v2ToFHIR` adapter via `IbisLocalSender`/`JavaListener`
3. `PutInSessionPipe` extracts message type (e.g. `ADT_A01`) from MSH.9
4. `XsltPipe` applies the matching XSLT from `xslt/<MSG_TYPE>.xslt` — currently `ADT_A01.xslt`
5. `ADT_A01.xslt` maps MSH → MessageHeader, PID → Patient, PV1 → Encounter into a FHIR R4 Bundle

**Maven note:** `frankframework-parent` is imported as a BOM (not used as `<parent>`) because the upstream bundle cannot be used as a Maven parent in external projects.

### viscostore (HAPI FHIR JPA Server WAR)
A full HAPI FHIR JPA Server 8.6.0 for persistent FHIR R4 storage, deployed at `/viscostore`.

Key endpoints:
- FHIR REST API: `/viscostore/fhir`
- Tester UI: `/viscostore/tester/`
- Swagger UI: `/viscostore/fhir/swagger-ui/index.html`
- MCP (Model Context Protocol) Streamable HTTP: `/viscostore/mcp/messages`

Main configuration file: `src/main/resources/application.yaml`

**MCP integration** (`ca.uhn.fhir.jpa.starter.mcp`): exposes FHIR resources and CDS Hooks as MCP tools via Spring AI. The `McpFhirBridge` wraps the `RestfulServer`; `McpCdsBridge` is conditional on `hapi.fhir.cdshooks.enabled=true`.

**Default datasource** is H2 in-memory (`jdbc:h2:mem:test_mem`). For Docker/production, override via environment variables (`SPRING_DATASOURCE_URL`, etc.) to PostgreSQL.

### viscorunner (Docker packaging module)
A `<packaging>pom</packaging>` module that assembles the deployable image. It:
1. Copies both WARs (version-stripped) to `target/`
2. Copies JDBC drivers (postgresql, h2) to `target/drivers/` — placed at Tomcat's `common.loader` so both WARs share them without bundling drivers inside either WAR
3. Copies Frank!Framework configurations from `../viscolink/src/main/configurations/` to `target/configurations/`
4. Builds a Tomcat 11 / JRE 21 Docker image that runs both WARs in the same Tomcat instance

**Key runtime directories inside the container:**

| Path | Content |
|---|---|
| `/opt/frank/configurations/` | Frank!Framework XML configurations |
| `/opt/frank/drivers/` | JDBC drivers (shared via `common.loader`) |
| `/opt/frank/secrets/credentials.properties` | Credentials file (mount from secret) |
| `/opt/frank/resources/` | Frank!Framework shared resources |
| `/opt/frank/testtool/` | Larva test scenarios |

### Packs (the pack descriptor and the subject identifier)
The healthcare specifics are meant to become a pack (design: `docs/design/2026-10-07-vertical-packs-design.md`). Milestone 1 shipped the descriptor inside `viscolink`; the health values still live in `com.viscosiety.pack.HealthPack`, the module split is M2.

- **SPI**: `com.viscosiety.pack.PackDescriptor`, discovered through `META-INF/services/com.viscosiety.pack.PackDescriptor` (today `HealthPack`).
- **Registry rule**: always read the pack through `PackRegistry.get()`. No pack on the class path gives `CorePack` (subject `subjectId`), exactly one gives that one, two throw `IllegalStateException` naming the ids, and a pack whose `frankOwnedPaths()` are malformed throws too. All of that fails at console-security start (`ConsoleSecurityRegistrar.afterPropertiesSet`), not on the first request; the result is cached per JVM.
- **Endpoints**: `GET /viscolink/api-service/pack` (bearer JWT only, `PackServlet`; settings `servlet.pack.authenticator` and `servlet.pack.securityRoles`, fail-closed 401 without the roles) and `GET /viscolink/flow-api/pack` (ViscoFlow, console session). Same JSON (`PackJson`); nothing about the pack is public and no secret belongs in a descriptor.
- **The subject identifier is `patientId` for health. Never hardcode it again; read `PackRegistry.get().subject()`.** Ladybug: `SubjectMetadataFieldExtractor` plus the SpEL entries in `springIbisTestToolVisco.xml`. ViscoFlow: `FlowController.subjectFilter` (`patientFilter` stays as an alias for one release) and the JS, which reads the descriptor at load. `SubjectIdentifier` has two labels: `metadataLabel` (Ladybug's column title, "PatientId") and `displayLabel` ("Patient"; the JSON key is `label`).
- **`consoleViews()` and `propertyDefaults()` are informational until M2**: nothing applies them. How M2 should (properties in the pack jar, no core hook; the FHIR UI is a pom-injected script, not a `customViews` entry) is the "M1 spike result" in section 10 of the design document.

## Configuration Files

| File | Purpose |
|---|---|
| `viscolink/src/main/resources/DeploymentSpecifics.properties` | Frank!Framework base properties (instance name, configuration names, classpath loaders) |
| `viscolink/src/main/resources/StageSpecifics_LOC.properties` | Local-stage overrides (auth disabled, `NotificationProcessorApi.active=true`) |
| `viscolink/src/main/resources/StageSpecifics_STUB.properties` | Stub overrides for testing |
| `viscorunner/src/scripts/catalinaAdditional.properties` | Tomcat `catalina.properties` additions (shared for both WARs: log dir, DTAP stage, credential factory, classpath) |
| `viscorunner/src/conf/context.xml` | Tomcat JNDI datasource definitions |
| `viscostore/src/main/resources/application.yaml` | All HAPI FHIR / Spring Boot settings |

## Smoke Tests

Integration smoke tests for viscostore are IntelliJ HTTP Client `.rest` files in `viscostore/src/test/smoketest/`. They must be run sequentially (later tests depend on IDs created by earlier ones). Requires IntelliJ Ultimate. Configure the target server in `http-client.env.json`.
