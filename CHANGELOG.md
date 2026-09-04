# Changelog

All notable changes to ViscoSuite are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com); versions follow
[semantic versioning](https://semver.org) (pre-1.0: breaking changes bump the
minor version).

## [Unreleased]

Nothing yet.

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
