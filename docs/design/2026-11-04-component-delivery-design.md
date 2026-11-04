# Component delivery: artifacts in the registry, one bare image, a fetcher

**Status:** built in M1 of the component model (`feat/component-model-m1`, 2026-11); the release BOM is
`viscorunner/release/viscosuite-release.template.json`, the fetcher `viscorunner/src/scripts/fetch-components`,
the image stages `viscobase` and `fat` in `viscorunner/Dockerfile`. The full design, across the three
repositories, is viscoFoundry's `docs/superpowers/specs/2026-11-04-component-model-design.md`.

## Problem

Every optional part of the platform (the store, a pack, the commercial console) was baked into image
variants: one Dockerfile × `PACK` × `STORE` here, two more Dockerfiles downstream, 18 image builds per
commit across the two repositories, and a portal that chose images by tag suffix. Each new optional part
multiplied all of that. Pack identity was defined twice (Java and `pack.properties`), the Tomcat context
three times, the brand six times.

## Decision

A **component** is a versioned Maven artifact (a WAR, an overlay zip) or a container image. A **release
BOM** pins the exact set a release was tested with. The runtime is one bare image, `viscobase`, that a
**fetcher** composes with components: at build time for the open-source fat images, at pod start for the
commercial runner, in the tenant's CI for a casting. Non-Java components (Postgres, later an API gateway)
are containers next to the one Tomcat; Java components share it.

## Rejected

- *Keep baking, generate the matrix.* Writes the variants once but still builds and ships every
  combination; a fourth axis still doubles the builds.
- *A second Tomcat per Java component.* Clean isolation, but memory a single-tenant instance pays for nothing.
- *Components declaring their Kubernetes needs, a generic renderer.* A rewrite of two renderers and every
  golden for a first consumer whose needs are least like the others; the BOM therefore declares only what
  the deployer cannot know (`servlets`, `properties`), the deployer keeps one render block per component id.
- *Independent per-component versions.* A compatibility matrix nobody tests; a release BOM tests one set.
