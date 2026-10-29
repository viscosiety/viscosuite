# Changelog

All notable changes to ViscoSuite are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com); versions follow
[semantic versioning](https://semver.org) (pre-1.0: breaking changes bump the
minor version).

## [Unreleased]

### Changed
- The health pack's classes moved to their own namespace, `com.viscosiety.pack.health.{fhir,mllp,pipes,hl7util}`
  (for example `com.viscosiety.pack.health.pipes.Hl7v2ToXmlPipe`, `com.viscosiety.pack.health.fhir.FhirListener`).
  **Breaking:** the old names (`com.viscosiety.fhir.FhirListener`, `com.viscosiety.mllp.MllpListener`,
  `com.viscosiety.mllp.MllpSender` and `com.viscosiety.pipes.{Hl7v2ToXmlPipe,XmlToHl7v2Pipe,FhirFormatPipe,FhirValidatorPipe}`)
  no longer load: a configuration that still uses one fails to start with a `ClassNotFoundException`. Change the
  `className` to the new name (same class, same attributes); a configuration that uses the new names needs this
  release or later. `com.viscosiety.pipes` now holds core pipes only.
- Neutral web paths: the Frank!Framework webapp is served at `/runtime` and the FHIR store at
  `/fhir-store`. The old `/viscolink` and `/viscostore` keep working: a Host-level `RewriteValve`
  serves API calls, POSTs and scripts on the old paths in place and sends a browser page load
  (GET, `Accept: text/html`) to the new address with a 302 (the session cookie is scoped to the new
  path). The health pack's `viscostore.fhir.base.url` default, the landing page, the ViscoLink home
  page, the store tester's links, the compose files, the demo tools and configurations, the
  scripts and the healthchecks use the new paths. Filesystem paths (`/opt/frank/webapp-overlay/viscolink`),
  datasource names (`jdbc/viscolink`), image names and property names are unchanged.

### Added
- The suite's own pages (the landing page, the ViscoLink home and the flow viewer) can show a
  deployment's own names and colours: `brand-loader.js` reads a `brand.json` (`productName`,
  `names.link/store/suite/company`, `colors.primary/dark`) from `/brand/brand.json` (landing page)
  or `/viscolink/brand/brand.json` (ViscoLink pages), served from a mounted directory
  (`/usr/local/tomcat/webapps/ROOT/brand`, `/opt/frank/webapp-overlay/viscolink/brand`). Without
  one, nothing changes.
- The pack descriptor reports the rule engines' versions as `engines` (`{"lemma": "0.10.0"}`),
  read from the loaded engine jar's manifest, so a client that checks rules with its own copy of
  an engine can tell when the two differ.
- The runner images carry Lemma's native library at /opt/frank/native/ (LEMMA_JNI_LIBRARY), built for the image's architecture.
- `com.viscosiety.pipes.LemmaPipe` (core): evaluates business rules written in
  [Lemma](https://lemma.run) from a rules file in the configuration. Each `<Param>` is a `data`
  input of the spec; the result comes back as XML (or JSON) and, with `resultSessionKeyPrefix`,
  per rule in the session. A rule without an answer (a Lemma veto) takes the `veto` forward;
  missing data and a spec that does not load fail. Offline only: LemmaBase imports are refused.
  Uses `com.lemmabase:lemma-engine` 0.10.0 (JNI).
- Vertical packs: a pack SPI in the core (`com.viscosiety.pack`, discovered through
  `META-INF/services/com.viscosiety.pack.PackDescriptor`) with `CorePack` as the default, a
  descriptor served at `GET /viscolink/api-service/pack` (bearer JWT only, settings
  `servlet.pack.authenticator` and `servlet.pack.securityRoles`) and at
  `GET /viscolink/flow-api/pack` (ViscoFlow, console session), and the subject identifier
  (`patientId` for health) read from the descriptor instead of hardcoded.
- `packs/health` (`viscolink-pack-health`): the healthcare specifics as a pack, laid over the core
  WAR as a webapp overlay (FHIR facade servlets, MLLP, the HL7v2/FHIR pipes, their defaults, the
  `patientid` Ladybug column, the demo configurations and the `hl7util`/`fhirutil`/`demo` tools).
- Image tags `:<sha>-health` and `:latest-health` (the same manifest as the unsuffixed tags),
  and `viscolink:<sha>-core` / `:latest-core`, the core without a pack or ViscoStore.
- `viscorunner/docker-compose.core.yml` runs the core image; `viscorunner/postgres/init-ladybug.sql`
  creates Ladybug's database for the stacks without ViscoStore.
- The pack descriptor (`/api-service/pack`, `/flow-api/pack`) ends with `features`, the core's
  capabilities by name (`["lemma"]` from this release), so clients can tell what a runner image carries.

### Changed
- Runner log files are kept for 30 days: rolled Frank!Framework log files older than 30 days are
  deleted at each rollover (a third log4j2 configuration file, `/opt/frank/log4j/log4j4retention.xml`,
  added to `log4j.configurationFile`), and Tomcat's access log and its own daily files keep 30 days
  (were: unlimited and 90). The security, heartbeat, leak and Ladybug logs keep
  `log.minor.maxBackupIndex` rolled files (new property, default 4) instead of `log.maxBackupIndex`.
- One `viscorunner/Dockerfile` with the build arguments `PACK` (`health` | `core`) and `STORE`
  (`viscostore` | `none`) builds every runner image; CI builds the matrix from it.
- The `viscolink` image now carries `server.xml` and the viscorunner jar, like the combined image.
- Tomcat's `conf/context-viscosuite.xml` and `conf/context-viscolink.xml` are now
  `conf/context-viscostore.xml` and `conf/context-none.xml`; `STORE` picks the file.
- The demo configurations moved to `packs/health/demo-configurations`; the core ships a one-adapter
  `echo` demo in `viscolink/demo-configurations`.
- The `util/*` tools moved to `packs/health/util/`.
- Ladybug records the subject identifier in a `subjectid` column; the health pack adds its own
  `patientid` column.
- `customViews.names` in the core's `DeploymentSpecifics.properties` appends the pack's views through
  `pack.customViews.names`.
- HAPI FHIR bumped to `8.12.1` in ViscoStore (from `8.6.0`; Spring Boot 3.5.15, FHIR core 6.9.12) and
  in the health pack (from `8.8.1`). ViscoStore is re-synced with hapi-fhir-jpaserver-starter
  `image/v8.12.0-2`: clinical-reasoning 4.13.0, Spring AI 1.1.8 (was a milestone), Spring Security
  6.5.11, and the starter's CVE pins (logback, embedded Tomcat, PostgreSQL driver). An existing
  ViscoStore database upgrades in place on start (Hibernate adds the new tables and columns; checked
  against a Postgres database written by the 8.6.0 image). Behaviour changes that come with it: CORS no
  longer allows credentials by default (`hapi.fhir.cors.allow_Credentials` defaults to `false`; CORS
  itself is now off, see Security), the actuator's liveness group no
  longer includes readiness, and `inline_resource_storage_below_size` is now
  `binary_storage_minimum_binary_size` (the old setting has done nothing since HAPI 7). The health
  pack's class path loses FHIR core's RDF libraries (Jena, Titanium, libthrift) and gains OkHttp 5,
  Okio and the Kotlin standard library.

### Removed
- `viscorunner/Dockerfile.viscolink`, replaced by `Dockerfile` with `STORE=none`.
- `PackDescriptor.propertyDefaults()`: a pack's own `DeploymentSpecifics.properties` supplies its defaults.
- The FHIR facade servlets, MLLP and the HAPI libraries from the core WAR; the health pack carries them.

### Fixed
- ViscoStore's `ValueSet/$expand` works without Hibernate Search (the default) instead of
  answering 500 "HSEARCH800001: Hibernate Search was not initialized". The starter registered
  HAPI's full-text search service unconditionally while leaving Hibernate Search off, and HAPI's
  terminology code takes that service for "Hibernate Search is on"; it is now registered only
  with `spring.jpa.properties.hibernate.search.enabled=true`, so HAPI expands from the database
  (whole CodeSystems, listed concepts, `is-a` and `property = value` filters). A text filter
  (`$expand?filter=`) is refused with 400 while Hibernate Search is off, because HAPI would
  ignore it and answer every code (`ExpandTextFilterGuard`). Known HAPI limit: its database
  `is-a` leaves out the concept itself; list it explicitly. Covered by `TerminologyExpansionIT`
  and `TerminologyExpansionWithHibernateSearchIT`.
- The demo stack (`docker-compose.yml` + `docker-compose.demo.yml`) deploys `/viscolink` again.
  `viscorunner/demo-conf/context.xml` replaces the image's Tomcat context but lacked the
  `jdbc/ladybug` datasource that 0.9.0 added to `conf/context-*.xml`, so the deploy failed with
  `unable to find resource [jdbc/ladybug]`. `DockerfileContractTest` now checks that every context
  declares `jdbc/ladybug` and that the demo context declares every datasource of the context it replaces.

### Security
- The Frank!Console's file viewer and log directory listing read only the log directory
  (`/usr/local/tomcat/logs`). `FileViewer.permission.rules` in `catalinaAdditional.properties`
  allowed all of `/opt/frank`, which holds the mounted secrets, the keystores, the
  configurations and the databases, so any console user with an Ibis role could list and
  download those files. `/opt/frank/secrets`, `/opt/frank/keystores` and the rest of
  `/opt/frank` are now denied explicitly, ahead of the log directory's allow rule.
  `FileViewerPermissionRulesTest` checks the rules as Frank!Framework evaluates them.
- ViscoStore's `POST /fhir/StructureMap/$compile` now requires the same HTTP Basic
  login as the rest of the FHIR API. `FmlCompileFilter` was registered ahead of
  Spring Security's filter chain, so it never got the login check; it now runs
  after it. `FmlCompileSecurityIT` covers the endpoint and fails when a filter
  that is not on its allow-list is mapped ahead of the security chain. Clients
  that already send credentials (the demo loader does) see no change.
- ViscoStore's CORS is off by default. It used to answer every origin, and with
  `allow_Credentials: true` (application.yaml up to the HAPI 8.12.1 bump) a web
  page on any site could read the store through a browser that held a ViscoStore
  login. Nothing calls the store from another origin (ViscoLink calls it
  server-to-server; the tester and the Swagger UI are same-origin), so the
  `hapi.fhir.cors` block is now commented out; to let a browser app call it,
  list that app's origins there. `allow_Credentials: true` with the origin `"*"`
  (also the default when no origin is listed) now stops the store at startup
  (`CorsSettingsGuard`). Covered by `CorsSecurityIT`, `CorsOptInIT` and
  `CorsSettingsGuardTest`.
- ViscoStore's subscription websocket (`/viscostore/tester/websocket`) and its actuator
  endpoints other than health now require the store's login. The DispatcherServlet is
  mapped at `/tester`, so both live under `/tester/**`, which `SecurityConfig` left open for
  the tester UI: the websocket accepted anonymous connections, and exposing more actuator
  endpoints (as application.yaml's comment offers) would have published metrics and
  Prometheus data without a login. Health (`/tester/actuator/health`, also reached through
  the `/actuator/health` redirect) and the tester UI stay public. The old `/actuator/health`
  rule never matched and is gone. Covered by `TesterPathSecurityIT`.

## [0.20.0] — 2026-09-25

Headless Larva test runs, combined OIDC and Basic API access, and a stateful OAuth2 console
login. Built on Frank!Framework nightly `10.3.0-20260924.042323`. This release predates the
vertical-pack split (the `packs/health` module, the `-core` and `-health` image tags and
`viscorunner/docker-compose.core.yml`), which is on `main` and listed under [Unreleased].

### Added
- `LarvaRunServlet` (bearer-gated `/api-service/larva/runs`) runs Frank!Framework Larva
  scenarios for a configuration and serves JSON results headlessly. `POST {configuration,
  execute?, timeoutMs?}` returns 202 with a runId; `GET …/runs/{runId}` serves per-scenario
  and per-step results with expected-vs-actual. Gated like `ConfigRefServlet`:
  `servlet.larvaRun.authenticator=bearer` + `servlet.larvaRun.securityRoles=<tenant role>`.
  Scenario root, resolved through the configuration's own classloader:
  `<clone>/<repoSubdir>/larva` for git-loaded configurations (ref and commit reported, never
  pulled; `commit` becomes null with a "clone moved during the run" message when HEAD moved
  mid-run), `<DirectoryClassLoader directory>/larva` for castings, else
  `<configurations.<name>.directory or configurations.directory>/<name>/larva`. A directory
  `execute` matches that directory only (not `OrdersIn-rejects` for `OrdersIn`); an `execute`
  that does not exist is a 400. Refused on `dtap.stage=PRD`. One run at a time per instance,
  bounded by a suite deadline of max(4 x timeoutMs, 15 min) checked between scenarios; the
  last 20 runs are kept. Beyond the spec's document shape: `scenarios[].messages[]`
  (`{level, text}`, the failure reasons Larva records without a diff -- timeouts, send
  errors, missing `x.className` -- clipped, never a stack trace), a synthetic `cleanup` step
  for "messages left on actions after the scenario", and `messagesDropped` (run-level
  `messages` are capped at 200). The document is clipped incrementally to 1 MiB while it
  runs (first scenarios keep their detail, `clipped: true`).
- `GitClassLoader` gains `getResourceDir()` and `currentCommit()` accessors to support
  loading Larva scenarios from the configuration's git tree.
- Combined OIDC + Basic API access: the `OAuth2Authenticator` override gains
  `allowBasicAuthentication` and `basicUsersFile` (a `YmlFileAuthenticator`
  user list), so the users of that file are accepted with HTTP Basic on the
  same chain that serves the Keycloak login and bearer tokens. API clients
  without credentials get a 401 naming every accepted scheme; browsers still
  get the login redirect. Basic callers are authenticated per request and
  never receive a session.
- Hosted webcontent pages can call the tenant API with the user's console login:
  same-origin `/api/*` requests from a browser that holds the console's OIDC
  session are authenticated with that session instead of being answered with
  the tenant API chain's `WWW-Authenticate: Basic` challenge (which made the
  browser pop its native credentials dialog on every `fetch('/api/...')` from a
  webcontent page). External API callers are unchanged -- any request carrying
  an `Authorization` header, or without an OIDC session, goes through HTTP Basic
  as before. Limited to same-origin requests (`Sec-Fetch-Site`, with an
  `Origin`/`Referer` fallback) so the session cookie cannot be replayed cross-site;
  only active when the console authenticates with OAUTH2; opt out with
  `viscolink.api.sessionAuth=false`.

### Changed
- Frank!Framework bumped from `10.3.0-20260902.042323` to nightly `10.3.0-20260924.042323`
  (frankframework master `1dcb19c2`; an intermediate bump to `10.3.0-20260910.042327` preceded
  it). The `OAuth2Authenticator` override still matches the upstream file (unchanged since
  `e3803c17`); only its tracking note moved.
  No Java bump: the framework's artifacts are still compiled for JDK 21 —
  JDK 25 is only needed to build the framework itself (Frank!Doc, Javadoc).
- The parent pom now declares the Apache License 2.0 (`<licenses>`), so the published artifact
  metadata carries the licence.

### Fixed
- The `OAuth2Authenticator` override no longer drops bearer authentication.
  It had been derived from Frank!Framework master, where bearer support has
  since moved out of that class, so `allowBearerAuthentication=true` was
  silently ignored on the console chain and bearer callers of `/iaf/api`
  (the portal, the agent's console tools) were refused. The override now
  tracks the source of the consumed nightly (currently `10.3.0-20260924.042323`,
  frankframework `1dcb19c2`) and is pinned by a test that the chain carries
  the bearer filter.
- Deep links into OAuth2-protected pages no longer land on the application
  root after the IdP login. The Frank!Framework applies a STATELESS session
  policy to every security chain, which discards both the originally
  requested URL (Spring Security derives a `NullRequestCache` from it) and
  the login itself — so any direct link, a `/webcontent/<configuration>/...`
  page especially, redirected to `/` after Keycloak, and every follow-up
  request re-ran the whole redirect dance. ViscoLink temporarily ships a
  patched `OAuth2Authenticator` as a `WEB-INF/classes` override (servlet-spec
  precedence over the framework jar) that makes only the interactive login
  chain stateful; bearer/API callers are unaffected. Delete the override once
  the consumed `frankframework.version` carries the upstream fix.
- `LarvaRunServlet`'s JSON run document no longer loses two Larva failure reasons.
  A failed compare's real reason (XMLUnit's diff text, or "Exception during XML
  diff: ..." when even the malformed-but-identical case can't be parsed) is now
  kept as the step's `message` instead of being overwritten by Larva's generic
  "Step '...' failed"; `JsonTestExecutionObserver.stepMessageFailed` records it
  and `finishStep` no longer clobbers an already-recorded reason. A scenario file
  that fails to load entirely (e.g. an `include=` that does not resolve --
  `ScenarioLoader` resolves includes relative to the scenario file's own folder)
  used to be silently dropped, with the run document only saying "no scenarios
  found ..."; `LarvaRunner` now forwards `LarvaTool`'s ERROR/WARNING messages
  produced while loading scenarios as run-level messages, and the "no scenarios
  found" message itself now hints at an unresolved include as a cause.

## [0.10.0] — 2026-09-04

Native bearer authentication. The Frank!Framework's `OAuth2Authenticator`
gained `allowBearerAuthentication`
([frankframework/frankframework#11542](https://github.com/frankframework/frankframework/pull/11542)):
one security chain now serves both the interactive browser login and
stateless bearer-JWT callers, which retires most of ViscoLink's proxy layer.

### Removed — BREAKING
- The Bearer-only `/api-service/*` proxy servlets: reload, adapters, adapter
  control, test-pipeline, warnings, ladybug reads and stubbed-run.
  Programmatic callers now present a bearer JWT to the native endpoints
  directly — `/iaf/api`, `/iaf/ladybug/api` and `/flow-api` — on the same
  chain the browser login uses. Migration requires a Frank!Framework build
  containing #11542 and
  `application.security.console.authentication.allowBearerAuthentication=true`;
  note that bearer authorities are the raw token roles (`roleMappingFile` is
  browser-login-only machinery), so service tokens must carry the F!F console
  roles, with the `audience` attribute available for tenant containment.
- Two `/api-service` servlets stay, each without a native equivalent:
  `ConfigRefServlet` (git ref switching via `GitClassLoader`) and
  `AgentApiServlet` (when a deployment hands `/api/*` to a tenant-facing
  HTTP-Basic authenticator, the gateway's internal forward is the only route
  that keeps platform bearer access working).

### Changed
- Frank!Framework bumped to nightly `10.3.0-20260902.042323`, the first
  nightly carrying #11542.
- Operational note for OAuth2/bearer deployments: prefer configuring
  `jwkSetUri` and omitting `issuerUri` on authenticators — the issuer-based
  decoder performs a blocking OIDC-discovery call during webapp startup, so a
  transient identity-provider timeout can wedge the whole context; the
  jwkSetUri-based decoder is lazy.

## [0.9.1] — 2026-09-03

### Fixed
- The `github-release` tag-pipeline job is now actually runnable and
  diagnosable: it runs on the tagged runners, fails fast with an actionable
  message when `GITHUB_TOKEN` is not injected (Protected variables need a
  protected `v*` tag pattern), treats an already-existing release as success,
  and pins the release to the pipeline's commit so mirror lag cannot land the
  tag elsewhere.
- Quickstart papercut: the demo overlay self-fetches the Nictiz FHIR
  validation packages before the runner starts, so a fresh clone boots the
  strict nl-core intake without a manual download step.

## [0.9.0] — 2026-09-03

First public preview — the feature-complete preview of the 1.0 line.
v1.0.0 lands together with Frank!Framework 10.3 GA.

### Added
- `nl-core-intake` reference flow: FHIR R4 / nl-core intake with synchronous
  validation (422 + OperationOutcome on refusal), inbound-zone tagging and
  guaranteed delivery with error-store parking.
- `FhirFormatPipe`: FHIR-aware XML↔JSON conversion with the target mimetype
  configurable per deployment, per session key, or per message via `<Param>`.
- `fhir-delivery` reference flow: asynchronous guaranteed FHIR delivery with
  in-flight format conversion.
- `demo-traffic`: scheduled generator streaming valid and deliberately failing
  HL7v2, FHIR R5 and R4 nl-core messages through the reference flows.
- Dormant facet instrumentation in the FHIR intakes (property-gated), for
  operator consoles that index business facets.
- `FhirValidatorPipe`: parse-level failures now refuse on the `failure` forward
  with an OperationOutcome; new `failOnUnknownProfiles` attribute (default
  false — resources may claim profiles no package is loaded for).
- `FhirValidatorPipe` package-backed profile validation: `validationPackages`
  loads FHIR NPM packages (.tgz), so profile claims resolve and resources are
  validated against them. The demo runs the nl-core intake with the Nictiz
  nl-core + zib2020 packages (CC0, fetched by
  `viscorunner/fhir-packages/download-packages.sh`); a
  `nl-core-patient-nonconformant` traffic variant — valid base R4, missing the
  nl-core name-qualifier extension — demonstrates exactly what it adds.

### Changed
- Kubernetes lifecycle events now come from the Frank!Framework's own
  `KubernetesEventPublisher` (frankframework-kubernetes) — the viscolink
  implementation this design originated from is removed, which also stops
  duplicate Warning events on clusters. Disable with
  `management.kubernetes.events.enabled=false`. The Tomcat-tier
  `ContextFailureEventPublisher` (viscorunner) is unaffected.
- Demo configurations follow explicit conventions (no markup in XML attributes,
  payloads in files, XSLT text in element content).
- Ladybug debug reports use a dedicated datasource so journeys of failed
  transacted messages survive the rollback.
