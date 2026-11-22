# Upstream hashes for the Frank!Framework classpath overrides

Each `*.sha256` here is the SHA-256 of one upstream Frank!Framework source file, at the
`frankframework.version` the build consumes, as shipped in that module's `:sources` artifact.

`viscolink/pom.xml` re-computes the hash during the `validate` phase and fails the build when it
differs, so an F!F bump cannot silently leave a stale copy of an overridden class in
`src/main/java/org/frankframework/`. On such a failure: re-derive the override from the new
version's sources, re-apply the ViscoLink patch described in the override's own banner, and write
the new hash here in the same change.

See `VISCO-OVERRIDES.md` at the repository root for the list of overrides and their removal
conditions.
