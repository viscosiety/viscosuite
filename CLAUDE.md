# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build all modules and install to local repo (required before docker compose up --build)
./mvnw install -pl viscolink,packs/health,viscostore
./mvnw package -pl viscorunner -DskipTests

# Or in one step:
./mvnw install -pl viscolink,packs/health,viscostore && ./mvnw package -pl viscorunner -DskipTests

# WARNING: copy-dependencies and unpack in viscorunner always resolve from .m2/ (not the reactor).
# Always run `mvn install` for viscolink, packs/health and/or viscostore before building viscorunner,
# otherwise stale .m2/ artifacts end up in the Docker image.
# Never run `mvn package -pl viscorunner` alone after changing viscolink, packs/health or viscostore.

# Run unit tests
./mvnw test -pl viscolink
./mvnw test -pl packs/health
mvn test -pl viscostore

# viscorunner integration tests (after the install above): the class-path oracle HealthClasspathIT,
# the staging layout, and the launcher ITs that run the health pack from its overlay
./mvnw verify -pl viscorunner

# Run unit + integration tests (maven-failsafe-plugin)
mvn verify -pl viscostore

# Run a single test
mvn test -pl viscostore -Dtest=ClassName#methodName

# Build and start with Docker Compose (host port 8180 → container 8080)
cd viscorunner && docker compose up --build
# Other stacks: docker-compose.viscolink.yml (health pack, no ViscoStore), docker-compose.core.yml (core, no pack)
```

Before running `docker compose up`, create `viscorunner/secrets/credentials.properties` (mounted at `/opt/frank/secrets/credentials.properties` inside the container). See `catalinaAdditional.properties` for the credential factory configuration.

## Bumping Frank!Framework (or ladybug, HAPI)
A bump changes the WAR's class path, and `HealthClasspathIT` compares it against the frozen golden `viscorunner/src/test/resources/golden/health-webapp-libs.txt` by exact jar name. CI's `test` job runs that IT (and `PackOverlayLayoutIT`) on every merge request, so a bump MR is red until the golden is updated by hand:

1. Change the version in lock-step, everywhere it is declared: `frankframework.version` in the root `pom.xml`, `viscolink/pom.xml` and `packs/health/pom.xml`; `ladybug.version` in `viscolink/pom.xml` and `packs/health/pom.xml`; for HAPI, `hapi.version` in `packs/health/pom.xml` (the pack's HAPI jars are in the golden; the root `hapi.version` and viscostore's HAPI parent are the store's own pin and do not touch it). The pack's `provided` jars must be the exact versions the WAR ships.
2. See the diff: `./mvnw -q clean install -pl viscolink,packs/health -DskipTests && ./mvnw -q verify -pl viscorunner -Dit.test=HealthClasspathIT,PackOverlayLayoutIT` (viscostore must be in `.m2/` too, `install` it as well if it is not). The assertion prints the golden and the actual jar set (and names any jar that is in both the WAR and the overlay); compare them.
3. Edit the golden BY HAND, only the lines the bump legitimately changes (version strings, a transitive that appeared or went away); never regenerate it, a bump IS a deliberate class-path change and is reviewed line by line. Name those lines in the MR description, then re-run step 2 until green.
4. Refresh `FrankConfig.xsd` (three copies) with `viscorunner/scripts/update-frankconfig-xsd.sh`.

## Architecture

This is a Maven multi-module project for an integration platform whose first market is healthcare (HL7v2 and FHIR): a market-neutral core (`viscolink`) with the healthcare specifics as a pack (`packs/health`). The modules are built in reactor order: `viscolink` → `packs/health` (with `packs/health/util/hl7util`) → `viscostore` → `viscorunner`.

### viscolink (Frank!Framework WAR)
An integration middleware layer based on Frank!Framework (version pinned by `frankframework.version` in `viscolink/pom.xml`), deployed at `/viscolink`.

- **Frank!Console / Ladybug debugger**: `/viscolink/iaf/` (auth disabled in LOC stage)
- **No healthcare code**: the FHIR facade servlets, MLLP and the HL7v2/FHIR pipes live in the health pack (see "Packs" below), not in this WAR.
- **Configurations** are not part of the WAR: they are loaded from `/opt/frank/configurations/` at runtime (outside the image, so they can be updated without rebuilding), or from git. Reference configurations live with the pack that owns them: `packs/health/demo-configurations/` for health, `viscolink/demo-configurations/echo/` (one neutral adapter) for the core.

**Maven note:** `frankframework-parent` is imported as a BOM (not used as `<parent>`) because the upstream bundle cannot be used as a Maven parent in external projects. The health pack's pom imports it the same way.

### packs/health (the healthcare pack)
Jar `viscolink-pack-health` plus the overlay zip that carries it into the image. It brings `com.viscosiety.fhir` (the FHIR facade servlets), `com.viscosiety.mllp`, the pipes `Hl7v2ToXmlPipe`, `XmlToHl7v2Pipe`, `FhirValidatorPipe`, `FhirFormatPipe`, and the reference configurations in `packs/health/demo-configurations/`.

**HL7v2 → FHIR flow** (the `hl7v2-to-fhir` demo configuration):
1. `HL7v2-over-HTTP` receives an HTTP POST on its `ApiListener` (`uriPattern` `hl7v2`, served at `/viscolink/api/hl7v2`); the MLLP adapter takes the same messages on port 2575
2. It forwards to the `HL7v2ToFHIR` adapter via `IbisLocalSender`/`JavaListener`
3. `PutInSessionPipe` extracts the patient id (PID.3) and, from MSH.9, the trigger event and the stylesheet name; a `SwitchPipe` rejects unsupported events
4. `XsltPipe` applies `xslt/<fhir.target.version>/<MSG.1>_<MSG.2>.xslt` (for `r4`: ADT A01/A02/A03/A04/A08/A11/A13, SIU S12 to S15)
5. The stylesheet maps MSH → MessageHeader, PID → Patient, PV1 → Encounter into a FHIR R4 transaction Bundle; `FhirValidatorPipe` validates it and the adapter POSTs it to ViscoStore

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
A `<packaging>jar</packaging>` module (it shades the Kubernetes event-publisher jar) that assembles the deployable images. It:
1. Copies both WARs (version-stripped) to `target/`
2. Copies JDBC drivers (postgresql, h2) to `target/drivers/` — placed at Tomcat's `common.loader` so both WARs share them without bundling drivers inside either WAR
3. Unpacks the health pack's overlay zip to `target/packs/health/`, stages `target/packs/core/` (empty libs plus its own identity), the demo sets `target/demo/{health,core}/` and the store variants `target/store/{viscostore,none}/`
4. Builds Tomcat 11 / JRE 21 Docker images from ONE `Dockerfile` with the build arguments `PACK` (`health` | `core`) and `STORE` (`viscostore` | `none`); the combined image runs both WARs in the same Tomcat instance

**Key runtime directories inside the container:**

| Path | Content |
|---|---|
| `/opt/frank/configurations/` | Frank!Framework XML configurations |
| `/opt/frank/demo-configurations/` | The pack's demo set, baked into the image; never loaded unless mounted over `/opt/frank/configurations` |
| `/opt/frank/webapp-overlay/viscolink/` | The pack overlay (`PreResources` of `/viscolink`): `WEB-INF/lib`, `WEB-INF/classes`, `WEB-INF/pack.properties` |
| `/opt/frank/drivers/` | JDBC drivers (shared via `common.loader`) |
| `/opt/frank/secrets/credentials.properties` | Credentials file (mount from secret) |
| `/opt/frank/resources/` | Frank!Framework shared resources |
| `/opt/frank/testtool/` | Larva test scenarios |

### Packs (the pack descriptor, the health pack and the image matrix)
The healthcare specifics are a pack (design: `docs/design/2026-10-07-vertical-packs-design.md`). Milestone 1 (the descriptor) and milestone 2 (the module split and the image matrix) shipped; the viscoForge, viscoFoundry and public-sector milestones are later.

- **Where things live**: the core WAR (`viscolink`) holds the SPI (`com.viscosiety.pack`, discovered through `META-INF/services/com.viscosiety.pack.PackDescriptor`), `CorePack` and nothing healthcare. `packs/health` (`viscolink-pack-health`) holds `com.viscosiety.fhir`, `com.viscosiety.mllp`, the four pipes, `springFhir.xml`/`springMllp.xml`, the FHIR console script, the FHIR/MLLP property defaults, the `patientid` Ladybug column, `HealthPack` and `HealthPackModule` (`com.viscosiety.pack.health`), `demo-configurations/` and `util/{hl7util,fhirutil,demo}`. `hl7util` is a standalone CLI module (`mvn package -pl packs/health/util/hl7util`) that no image stages.
- **Package rule**: a class in `com.viscosiety.pack` is core; packs use `com.viscosiety.pack.<id>`. A pack uses only `PackDescriptor`, `SubjectIdentifier` and `ConsoleView` from the core (it compiles against `viscolink:classes` with scope `provided`), and nothing in the core names the pack. The core cannot test-depend on the pack, so core tests use the mirror `HealthValuesPack`; discovery by services file is tested in the pack.
- **Registry rule**: always read the pack through `PackRegistry.get()`. No pack on the class path gives `CorePack` (subject `subjectId`), exactly one gives that one, two throw `IllegalStateException` naming the ids, and a pack whose `frankOwnedPaths()` are malformed throws too. All of that fails at console-security start (`ConsoleSecurityRegistrar.afterPropertiesSet`), not on the first request; the result is cached per JVM.
- **Endpoints**: `GET /viscolink/api-service/pack` (bearer JWT only, `PackServlet`; settings `servlet.pack.authenticator` and `servlet.pack.securityRoles`, fail-closed 401 without the roles) and `GET /viscolink/flow-api/pack` (ViscoFlow, console session). Same JSON (`PackJson`); nothing about the pack is public and no secret belongs in a descriptor.
- **The subject identifier is `patientId` for health. Never hardcode it again; read `PackRegistry.get().subject()`.** Ladybug: `SubjectMetadataFieldExtractor` plus the SpEL entries in `springIbisTestToolVisco.xml`. ViscoFlow: `FlowController.subjectFilter` (`patientFilter` stays as an alias for one release) and the JS, which reads the descriptor at load. `SubjectIdentifier` has two labels: `metadataLabel` (Ladybug's column title, "PatientId") and `displayLabel` ("Patient"; the JSON key is `label`).
- **A pack's `DeploymentSpecifics.properties` only ADDS keys the core's file does not define.** `propertyDefaults()` is gone from the SPI; the pack's file is merged next to the core's by the Frank!Framework, and on a clash Tomcat's resource-set order decides, which is not a contract (`HealthDefaultsTest` pins the rule). Tenant `StageSpecifics`, environment and system properties still win.
- **Ladybug column rule**: the core owns `subjectid` (changeSet `LadybugCustom:5`); a pack owns the column of its own `metadataName` in `ladybug/DatabaseChangelog_Pack.xml`, which the core's changelog includes with `errorIfMissing="false"`. The health pack adds `patientid` (`LadybugHealth:1`) under a negated `columnExists` `MARK_RAN` precondition, so databases that got the column from the old core changeSet are left alone. Never edit the core's changelog for a pack column.
- **Console views and scripts**: `consoleViews()` is informational, it mirrors the pack's `customViews.*` keys. The core's file says `customViews.names=viscoLink,${pack.customViews.names:-}`, but deployers that set `customViews.names` themselves (the compose files, viscoFoundry's manifests) bypass it and must list a pack's views. The FHIR UI is a script, not a view: the pack's build patches the console's `index.html` and the overlay serves it from `WEB-INF/classes/console/`.
- **The overlay zip and the class path**: `viscolink-pack-health:zip:overlay` is exactly `/opt/frank/webapp-overlay/viscolink/` (`WEB-INF/lib` = the pack jar plus the libraries the WAR lacks, `WEB-INF/classes/console/index.html`, `WEB-INF/pack.properties`). **Never add a `compile` dependency the WAR ships to a pack's pom; mark it `provided`.** `HealthClasspathIT` (viscorunner, golden `health-webapp-libs.txt`, frozen: edit by hand, never regenerate) is the oracle: WAR libs ∪ overlay libs = the 430 jars the one WAR had before the split, and the intersection is empty. Four class-path pins in `viscolink/pom.xml` keep the WAR's own versions.
- **Images**: build args `PACK` and `STORE` (see viscorunner above). CI publishes `viscorunner:<sha>`/`:latest` (health + ViscoStore), `viscolink:<sha>`/`:latest` (health, no store), the same two also as `-health`, and `viscolink:<sha>-core`/`:latest-core` (no pack, no store: no FHIR servlets, `/pack` answers `core`). There are no version tags. The ROOT landing page is stamped from the pack's `WEB-INF/pack.properties` at image build; `BuildInfo` does not carry the pack.

## Configuration Files

| File | Purpose |
|---|---|
| `viscolink/src/main/resources/DeploymentSpecifics.properties` | Frank!Framework base properties (instance name, configuration names, classpath loaders) |
| `packs/health/src/main/resources/DeploymentSpecifics.properties` | The health pack's property defaults (MLLP identity, FHIR target version, ViscoStore base URL); add-only |
| `viscolink/src/main/resources/StageSpecifics_LOC.properties` | Local-stage overrides (auth disabled, `NotificationProcessorApi.active=true`) |
| `viscolink/src/main/resources/StageSpecifics_STUB.properties` | Stub overrides for testing |
| `viscorunner/src/scripts/catalinaAdditional.properties` | Tomcat `catalina.properties` additions (shared for both WARs: log dir, DTAP stage, credential factory, classpath) |
| `viscorunner/conf/context-viscostore.xml`, `viscorunner/conf/context-none.xml` | Tomcat JNDI datasource definitions; `STORE` picks the file, only `-viscostore` has `jdbc/viscostore` |
| `viscostore/src/main/resources/application.yaml` | All HAPI FHIR / Spring Boot settings |

## Smoke Tests

Integration smoke tests for viscostore are IntelliJ HTTP Client `.rest` files in `viscostore/src/test/smoketest/`. They must be run sequentially (later tests depend on IDs created by earlier ones). Requires IntelliJ Ultimate. Configure the target server in `http-client.env.json`.
