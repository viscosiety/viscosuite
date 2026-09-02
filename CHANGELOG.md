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

### Changed
- Demo configurations follow explicit conventions (no markup in XML attributes,
  payloads in files, XSLT text in element content).
- Ladybug debug reports use a dedicated datasource so journeys of failed
  transacted messages survive the rollback.

## 0.9.0 — first public preview (pending)

The feature-complete preview of the 1.0 line. v1.0.0 lands together with
Frank!Framework 10.3 GA.
