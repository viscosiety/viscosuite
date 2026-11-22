# Frank!Framework classpath overrides

ViscoLink ships a few classes under `org.frankframework.*` that **shadow** a class of the same fully
qualified name in a Frank!Framework jar. The servlet spec has the webapp classloader search
`WEB-INF/classes` before `WEB-INF/lib`, so a copy staged into `WEB-INF/classes` wins over the one in
the jar. Nothing is forked and nothing upstream is patched in place.

Every override is **temporary**: a copy of upstream's file at the exact consumed
`frankframework.version`, plus a minimal ViscoLink patch, and it goes away once the consumed F!F
release carries the fix. The table below is the register; `scripts/ci/check-overrides.sh` fails the
build when a file under `viscolink/src/main/java/org/frankframework/` is missing from it.

Each override's own file banner holds the detail (what was changed, why, and what to watch when
re-deriving). This file is the index and the removal checklist.

## What counts as an override

A file under `viscolink/src/main/java/org/frankframework/` that has a same-named class in an F!F jar.
`org/frankframework/visco/**` does **not** count: that is ViscoSuite's own code living in a namespace
of its own (ViscoLink's bearer-auth servlets and the Larva JSON runner), shadowing nothing. The CI
check skips that subtree.

## Staging: compiling the class is not enough

`viscolink/pom.xml` sets `archiveClasses=true` on the WAR plugin, which bundles `target/classes` as a
**JAR in `WEB-INF/lib`**, where it would *not* reliably shadow another jar. Only
`target/console-patched/` is copied in as an exploded `WEB-INF/classes`. An override therefore needs
its own `stage-*-override` antrun execution copying its `.class` files there, and that execution
fails the build when the class is absent. **An override that is not staged is inert** — it ships in
the WAR and the framework's own class is still the one that loads.

## The register

| File (under `viscolink/src/main/java/`) | Shadows jar | Copied from | Why | Upstream | Remove when |
|---|---|---|---|---|---|
| `org/frankframework/filesystem/LocalFileSystem.java` | `frankframework-filesystem` | **10.3.0** (hash pinned in `viscolink/src/overrides/LocalFileSystem.java.sha256`) | `root` is documented as the boundary all folders and files are relative to, but upstream's `toFile` does not enforce it: an absolute filename skips the root and a relative one escapes via `..`. `FileSystemActor.determineFilename` falls back to the input message, so the path is attacker-controlled. Confines every path to `root`, **safe by default** (upstream's PR is opt-in). | issue [#12074](https://github.com/frankframework/frankframework/issues/12074), PR [#12075](https://github.com/frankframework/frankframework/pull/12075) | ViscoLink is on an F!F release containing #12075. Keep the `DeploymentSpecifics.properties` entry even then: that release ships `allowPathsOutsideRoot=true`. |
| `org/frankframework/lifecycle/servlets/OAuth2Authenticator.java` | `frankframework-security` | 10.3.0 | The base class's `STATELESS` policy discards the originally requested URL and the login itself; plus HTTP Basic for `basicUsersFile` users on the same chain as the Keycloak login and bearer tokens. | PR [#11648](https://github.com/frankframework/frankframework/pull/11648) (open) — covers the stateful change only, not Basic-on-the-OAuth2-chain | Both changes are upstream. Adapt `ApiSessionAuthListener` first: it reads the plain `SPRING_SECURITY_CONTEXT` session attribute this copy writes, while upstream stores per security chain. |
| `org/frankframework/ladybug/DeploymentSpecificsBeanPostProcessor.java` | `frankframework-ladybug-debugger` | unknown (added in `6fd8dc3`, "Replace placeholder viscostore with real HAPI FHIR JPA Server fork") | **No known ViscoLink purpose.** Diffed against upstream it carries no ViscoSuite change — only upstream drift (upstream has since added `@NonNull` annotations and a `trim()` on `maxBackupIndex`). It is also not staged into `WEB-INF/classes`, so it does not shadow anything at runtime; it is an inert stale copy. | — | **Candidate for deletion**, not re-derivation. Listed here so the CI check passes and so it is not mistaken for a deliberate override. |

## Re-deriving an override on an F!F bump

The `check-localfilesystem-upstream` execution in `viscolink/pom.xml` hashes the one upstream source
file it pins and fails the build when it changes, so a bump cannot leave a silently stale copy. Then:

1. unpack the new version's `:sources` artifact and take the file verbatim;
2. re-apply the patch the override's banner describes (and write Lombok out — viscolink does not run
   Lombok's annotation processor);
3. check whether the release now carries the upstream fix; if it does, delete the override, its
   `stage-*-override` and hash-gate executions, any startup check and Spring file, and its row here;
4. update the expected hash in `viscolink/src/overrides/` in the same change.

## Startup assertion

`WEB-INF/classes` precedence only exists in a deployed WAR. For the `LocalFileSystem` override, whose
loss is a security regression rather than a behavioural one,
`com.viscosiety.filesystem.LocalFileSystemOverrideCheck` reads the `VISCO_OVERRIDE` marker field
reflectively from the loaded class and **refuses to start** when it is missing.
