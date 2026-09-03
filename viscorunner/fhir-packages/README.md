# FHIR validation packages

FHIR NPM packages that `FhirValidatorPipe` loads when `validationPackages`
points here — they make profile claims (like the Nictiz nl-core canonicals)
actually resolvable, so `failOnUnknownProfiles="true"` validates resources
against the profiles they claim instead of only base FHIR.

The `.tgz` files are **not committed**. The demo overlay fetches them
automatically before the runner starts (the `fhir-packages` init service);
for other setups, fetch them once with:

```bash
./download-packages.sh
```

Contents (pinned in the script, both published by Nictiz under **CC0-1.0**):

| Package | Purpose |
|---|---|
| `nictiz.fhir.nl.r4.nl-core` | Dutch nl-core R4 profiles (zib 2020 release) |
| `nictiz.fhir.nl.r4.zib2020` | The zib2020 base profiles nl-core derives from |

`hl7.fhir.r4.core` is not needed as a package — HAPI's built-in
`DefaultProfileValidationSupport` provides base R4.

## Enabling profile validation in the demo

The demo overlay mounts this directory and sets two properties on the
`nl-core-intake` configuration:

```
nlcore.validation.packages=/opt/frank/fhir-packages
nlcore.validation.strict=true
```

With them, the `nl-core-patient-nonconformant` demo variant (valid base R4,
but missing the ISO 21090 name-qualifier extension nl-core requires on every
given name) is refused with HTTP 422 and a precise OperationOutcome. Without
them, the intake validates base R4 only and that variant is accepted — the
difference is exactly what package-backed validation adds.

Note: loading the packages adds a few seconds to configuration startup and
profile validation is slower than base validation — position the validator
accordingly in high-volume flows.
