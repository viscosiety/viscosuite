# Contributing to ViscoSuite

Thanks for considering a contribution — healthcare integration gets better when
the people who live it improve the tools together.

## Where things live

Development happens on our GitLab; **this GitHub repository is the community
home**. Issues, discussions, releases and pull requests all live here on GitHub.
Merged pull requests are integrated into GitLab by a maintainer and flow back to
GitHub through the mirror — your authorship is preserved in the commit history.

## Ways to contribute

- **Report a bug or an integration problem** — use the issue templates. For
  integration questions, real payloads (anonymised!) and the relevant F!F
  configuration snippet make all the difference.
- **Improve the demo configurations** — they are the examples people copy, and
  they follow explicit conventions
  (see `viscorunner/demo-configurations/README.md`).
- **Add or improve pipes** — healthcare-specific F!F pipes live in
  `viscolink/src/main/java/com/viscosiety/pipes/`. Generic (non-healthcare)
  framework improvements belong upstream in the
  [Frank!Framework](https://github.com/frankframework/frankframework) — we are
  happy to help you route them.
- **Profiles and the IG** — StructureDefinitions, extensions and mappings for
  the ViscoLink IG (all FHIR artefacts in XML).

## Before you open a pull request

1. **Open an issue first** for anything larger than a small fix, so we agree on
   the direction before you invest the work.
2. **Never include real patient data.** Not in issues, not in tests, not in
   fixtures — anonymised or synthetic data only (the demo roster and 999-range
   test BSNs exist for exactly this). This is a hard rule; PRs containing real
   healthcare data are closed and the history is scrubbed.
3. Build and test locally: `mvn test` in the module you touched. New behaviour
   comes with a test; bug fixes come with a test that fails without the fix.
4. Match the surrounding style. For F!F configurations, follow the demo
   conventions: no markup in XML attributes, payloads in files, XSLT text in
   element content.
5. Keep pull requests focused — one concern per PR reviews faster.

## Licensing

ViscoSuite is licensed under the [Apache License 2.0](LICENSE). By submitting a
contribution you agree that it is licensed under the same terms. There is no
CLA; the Apache 2.0 inbound=outbound model applies.

## Support expectations

Community support in issues is best-effort, by a small team, around real work.
Bug reports with clear reproductions get priority. For guaranteed response
times, implementation help, or advice specific to your environment, see the
commercial support options in the [README](README.md#support--services).
