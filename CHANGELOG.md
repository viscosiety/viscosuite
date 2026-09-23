# Changelog

All notable changes to ViscoSuite are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com); versions follow
[semantic versioning](https://semver.org) (pre-1.0: breaking changes bump the
minor version).

## [Unreleased]

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
- Frank!Framework bumped to nightly `10.3.0-20260910.042327` (frankframework
  master `a00a4fa2`). The `OAuth2Authenticator` override still matches the
  upstream file (unchanged since `e3803c17`); only its tracking note moved.
  No Java bump: the framework's artifacts are still compiled for JDK 21 —
  JDK 25 is only needed to build the framework itself (Frank!Doc, Javadoc).

### Fixed
- The `OAuth2Authenticator` override no longer drops bearer authentication.
  It had been derived from Frank!Framework master, where bearer support has
  since moved out of that class, so `allowBearerAuthentication=true` was
  silently ignored on the console chain and bearer callers of `/iaf/api`
  (the portal, the agent's console tools) were refused. The override now
  tracks the source of the consumed nightly (currently `10.3.0-20260910.042327`,
  frankframework `a00a4fa2`) and is pinned by a test that the chain carries
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
