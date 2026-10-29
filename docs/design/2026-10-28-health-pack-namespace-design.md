# The health pack in its own Java namespace — Design

**Status:** approved by Tom, 2026-10-28. Built (MR !53, merged 2026-10-28; rolled out 2026-10-29).
**As built, 2026-10-29:** the rollout finished the same morning: runner images rebuilt, every Healthcare instance
restarted, the three tenant configurations that still used old names (all copies of the healthcare demo) and both
health demo templates changed to the new names, and no open draft or casting used an old name. So the seven
compatibility subclasses (D2, D3) were removed the next day instead of "in a later release", together with
`LegacyClassNamesIT`; `LegacyClassNamesTest` now asserts that the old names do NOT load and that the old packages
hold nothing of the pack. An old name now fails a configuration with `ClassNotFoundException`.

**Context.** Vertical packs M2 (2026-10-07, `docs/design/2026-10-07-vertical-packs-design.md`) moved the
healthcare code into its own Maven module, `packs/health` (jar `viscolink-pack-health`), but kept every
class in its old Java package on purpose (M2's D8: "tenant configurations name them"). So the pack's
classes still sit in `com.viscosiety.fhir`, `com.viscosiety.mllp`, `com.viscosiety.hl7util` and
`com.viscosiety.pipes` — the last one shared with core pipes such as `LemmaPipe`. Only the pack's
registration lives in `com.viscosiety.pack.health`. This design gives the pack one namespace of its own
without breaking a single existing configuration.

## 1. Decisions

| # | Decision |
|---|---|
| D1 | Every class of the health pack moves under `com.viscosiety.pack.health`: `.fhir` (14 classes), `.mllp` (5), `.pipes` (4), `.hl7util` (2, the standalone CLI). The pack's root package (`HealthPack`, `HealthPackModule`, `PackVersion`) and its `META-INF/services` files are already there and do not change. |
| D2 | Old `className` values keep working through **thin deprecated subclasses** in the old packages, for the seven classes a configuration names: `com.viscosiety.fhir.FhirListener`, `com.viscosiety.mllp.MllpListener`, `com.viscosiety.mllp.MllpSender`, `com.viscosiety.pipes.{Hl7v2ToXmlPipe,XmlToHl7v2Pipe,FhirFormatPipe,FhirValidatorPipe}`. Classes only Spring or other pack classes name (`FhirServletRegistrar`, `FhirFfBridge`, `MllpConnectionFactoryFactory`, …) move without a shim; `hl7util` moves without a shim (no configuration names it). |
| D3 | An old name **warns from day one**: each shim carries `@Deprecated(since = "0.21.0", forRemoval = true)` and `@ConfigurationWarning("Use className=\"<new name>\"")`. Frank!Framework turns that into a configuration warning on the adapter (console, `/iaf/api/server/warnings`, the portal assistant's `get_config_warnings`), suppressible with `warnings.suppress.deprecated=true`. The new name is silent. |
| D4 | The shims are removed in a **separate, later change**, only once no tenant configuration (default branches and castings) uses an old name. Not part of this work. |
| D5 | `com.viscosiety.pipes` then holds only core pipes plus the four shims; after D4 only core pipes. |
| D6 | Separately, an **upstream Frank!Framework pull request** adds a generic class-name alias mechanism to `ClassNameRewriter` (§5). It does not block this work; once it is merged and ViscoSuite is on an F!F build that has it, the health pack can ship an alias file, and the shims become redundant (removed at D4). |

## 2. Why shims and not an alias in Frank!Framework (today)

Frank!Framework resolves `className` with the digester; the only rewrite hook is
`org.frankframework.configuration.filters.ClassNameRewriter`, an XML filter hardcoded to the legacy
`nl.nn.adapterframework.` prefix and eight renamed pipes. It is not pluggable (checked on
10.3.0-20260924 and on F!F `master` of 2026-10-28). A subclass needs no F!F change, and it is how F!F
itself keeps renamed classes loadable within a release.

How F!F treats the shim (checked in `ValidateAttributeRule` of the pinned build): `addConfigWarning(Class)`
looks up `@ConfigurationWarning` and `@Deprecated` with Spring's `AnnotationUtils.findAnnotation`, which
walks the class and its superclasses. The annotations sit on the shim (subclass) only, so the shim warns
("…is deprecated since 0.21.0 and has been marked for removal: Use className=…") and the moved class,
being the superclass, does not inherit the warning.

The pack's own code checks base types (`instanceof FhirListener`, Spring by-type wiring of
`MllpConnectionFactory`), so an instance of a shim behaves exactly like the new class: the FHIR servlet
registrar finds old-name `FhirListener`s, an old-name `MllpListener` gets its connection factory.

## 3. What changes where

### 3.1 ViscoSuite (`packs/health`)

- `git mv` the 23 runtime classes to `com/viscosiety/pack/health/{fhir,mllp,pipes}/`, their 10 tests to the
  same test packages; update `package`/`import` lines and Javadoc `{@link}`s.
- `springFhir.xml`, `springMllp.xml`: bean classes to the new names (bean ids unchanged).
- Seven shim classes in the old packages (D2/D3): `public class X extends com.viscosiety.pack.health.….X {}`
  with the two annotations; the Javadoc points at the new class.
- `packs/health/util/hl7util`: `git mv` to `com/viscosiety/pack/health/hl7util/`, shade `mainClass` updated.
- Demo configurations (`packs/health/demo-configurations/`, 12 files) and the pack README use the new names;
  the old names are proven by tests (§6), not by the demos.
- `CLAUDE.md` (the "shared package" note on `com.viscosiety.pipes`, the module description), `CHANGELOG.md`
  (Unreleased → Changed: the move, the shims, the warning).

### 3.2 viscoFoundry (portal)

- `src/lib/healthcare-component-scan.ts` already matches `com.viscosiety.pack.health.` as a whole package, so
  new names count as healthcare without a code change. Add tests for new names; keep the old-package entries
  (old names still load). Update the comment on `HEALTH_PACK_PIPES` (old-package shims).
- Assistant manual pages `data/packs/health/*.md` (5): teach the new names, plus one sentence that an older
  configuration may still use `com.viscosiety.pipes.X` / `com.viscosiety.fhir.FhirListener` /
  `com.viscosiety.mllp.*`, which loads with a deprecation warning. Move the `<!-- source: … -->` markers to the
  ViscoSuite commit of this change. `tests/unit/health-pack-knowledge.test.ts` follows.
- `CLAUDE.md` status log entry. Concept pages: no change (they name no Java classes).

### 3.3 The Way (`commercial/the-way`)

`packs/core/01-no-healthcare.md` names the old packages; it gets the new namespace. The health pack text names
components by simple name only and does not change.

### 3.4 Demo templates and tenants (GitLab)

- `viscoforge-tenants/demo-template-health` and `-health-suite`: three `className`s each in
  `Configuration-hl7v2-to-fhir-patient.xml`, changed by hand (template projects are never changed by the
  portal). The template manifest note in viscoFoundry records the new sha256.
- Tenant `benrogmans` (`ff-configurations/demo/`) uses the same three old names: left alone. It keeps
  starting and shows the deprecation warning; the tenant (or their assistant) migrates when they like.
- No other tenant project names a health class (GitLab blob search of every `viscoforge-tenants` project,
  2026-10-28).

### 3.5 viscoForge

Nothing: no file names a health pack class (checked 2026-10-28). Its runner images are rebuilt by the
ordinary ViscoSuite → ViscoForge pipeline bridge.

## 4. Rollout order

The new names only load on images built after this change. An assistant taught the new names, writing to an
instance still on an older image, would produce a configuration that fails with `ClassNotFoundException`.
So, in order:

1. ViscoSuite MR merged → ViscoForge runner images rebuilt (bridge pipeline).
2. Tom restarts every running Healthcare instance (`/admin/instances`); stopped ones take the new image at
   their next start. Check: a restarted instance's log has no `ClassNotFoundException`, and an old-name
   configuration shows the deprecation warning.
3. Then merge the portal MR (manual pages), the The Way MR, and update the two demo templates.

Castings are unaffected: a casting bakes its configuration together with the image it was built on, so an
old-name casting keeps its old image and its old names. A new casting is built on the then-current image.

A run-anywhere bundle or a customer running an older runtime with a new-name configuration fails to load it;
the CHANGELOG says the new names need ViscoSuite 0.21.0 or later.

## 5. Upstream: class-name aliases in Frank!Framework

Pull request against `frankframework/frankframework` `master`, from Tom's fork.

- **Alias files.** Every resource `META-INF/frankframework/class-name-aliases.properties` on the framework's
  class path (found with `getResources` on the class loader that loaded `ClassNameRewriter`, i.e. the
  webapp's — not a configuration's class loader, so a tenant configuration cannot inject aliases), read
  once, merged. Format `old.fully.Qualified=new.fully.Qualified`; blank lines and `#` comments allowed.
- **Rewrite.** `ClassNameRewriter.startElement`: after the existing legacy rewrite, if the (rewritten)
  `className` is an alias key, replace it with the target and add the existing suppressible deprecation
  warning "[old] has been renamed to [new]. Please change the className attribute." When the target cannot
  be loaded, keep the original value (same rule as the legacy rewrite) and warn.
- **Conflicts.** Two files mapping the same name to different targets: the first one found wins, a warning
  names both files. A chain (`a=b`, `b=c`) is not followed (one step only).
- **Installation.** `ConfigurationDigester` installs the filter when `migration.rewriteLegacyClassNames` is
  true (as today) OR any alias exists; the legacy rewrite itself still obeys the property.
- **Tests** in `ClassNameRewriterTest` (alias rewritten + warning, non-alias untouched, unloadable target
  kept, conflict warning, legacy property off but alias still applied), and a line in `RELEASES.md`
  ("Upcoming").

Once merged and ViscoSuite pins an F!F build that has it, `packs/health` adds
`src/main/resources/META-INF/frankframework/class-name-aliases.properties` with the seven mappings. That is
a separate ViscoSuite change, at or before D4.

## 6. Testing

- **Unit (packs/health):** per shim: it extends the new class, carries `@Deprecated(forRemoval = true)` and a
  `@ConfigurationWarning` that names the new class; the new class carries neither (a guard test over all
  seven pairs, so a forgotten annotation fails).
- **Digester (packs/health):** a small configuration with an adapter using an old pipe name and one using the
  new name, digested by F!F's real `ConfigurationDigester` in a test application context: both pipes are
  instantiated with their attributes set, and the configuration warnings contain exactly one deprecation
  warning, for the old name. (If F!F's test fixtures are not usable from the pack, the same proof moves to
  the IT below.)
- **Integration (viscorunner, failsafe, embedded launcher as `LabEnrichmentIT`):** one configuration with an
  old-name and a new-name `Hl7v2ToXmlPipe` adapter behind an `ApiListener`, plus an old-name `FhirListener`:
  both adapters start and convert the same ADT message identically; the FHIR facade of the old-name listener
  answers `metadata`.
- **Local Docker proof before merge** on the real suite runner image: the unchanged demo-template
  configuration (old names) and a copy with the new names both start, zero `ClassNotFoundException`, the
  warning only for the old copy.
- Existing pack tests move with their classes and stay green; `HealthClasspathIT` / `PackOverlayLayoutIT`
  unchanged (no jar added or removed).

## 7. Not in scope

Removing the shims (D4); the ViscoSuite alias file (after §5 merges); renaming the Maven module or artifact;
moving core classes; viscoForge's own packages.
