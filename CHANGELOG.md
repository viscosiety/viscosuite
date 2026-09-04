# Changelog

All notable changes to ViscoSuite are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com); versions follow
[semantic versioning](https://semver.org).

## [Unreleased]

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
  `viscorunner/fhir-packages/download-packages.sh`); a new
  `nl-core-patient-nonconformant` traffic variant — valid base R4, missing the
  nl-core name-qualifier extension — demonstrates exactly what it adds.

### Removed
- The Bearer-only `/api-service/*` proxy servlets (reload, adapters, adapter
  control, test-pipeline, warnings, ladybug reads, stubbed-run, agent API
  gateway). The Frank!Framework's `OAuth2Authenticator` gained
  `allowBearerAuthentication`
  ([frankframework/frankframework#11542](https://github.com/frankframework/frankframework/pull/11542)),
  so programmatic callers now present a bearer JWT to the console's own
  `/iaf/api`, Ladybug and `/api/*` endpoints directly — the same chain the
  browser login uses. Two servlets stay, each without a native equivalent:
  `ConfigRefServlet` (git ref switching) and `AgentApiServlet` (restored
  after the initial removal: when a deployment hands `/api/*` to a
  tenant-facing HTTP-Basic authenticator, the gateway's internal forward is
  the only route that keeps platform bearer access working). Requires a
  Frank!Framework build containing #11542 and
  `application.security.console.authentication.allowBearerAuthentication=true`.

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

## 0.9.0 — first public preview (pending)

The feature-complete preview of the 1.0 line. v1.0.0 lands together with
Frank!Framework 10.3 GA.
