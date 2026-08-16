# Task 12: BEARER_ONLY StubbedRunServlet

## What was built

New `viscolink/src/main/java/org/frankframework/visco/security/StubbedRunServlet.java`:
a BEARER_ONLY sibling of `com.viscosiety.flow.FlowController`'s `POST /flow-api/stubbed-run`,
mapped at `/api-service/stubbed-run`, in the `AbstractBearerServiceServlet` family
(same shape as `TestPipelineServlet`: `rejectUnauthorized` gate, `callElevated` wrapping the
actual call with the `IbisTester` role, `writeJson` for the response).

`doPost` reads `config`/`adapter`/`originId`/`cidPrefix` as request parameters (query string —
mirrors `FlowController.handleStubbedRun`'s own `param(qs, ...)` reads of the same four names),
reads the raw message body via `req.getInputStream().readAllBytes()`, and delegates to
`com.viscosiety.ladybug.StubbedRunner.getInstance().runStubbed(...)`. Response body:
`{correlationId, configuration, adapter, state}` — identical shape to `FlowController`'s.

## Null-safety check on `configuration` (the one param the task asked to double-check)

Read `StubbedRunner.runStubbed` and `resolveAdapter`:

```java
private Adapter resolveAdapter(String configurationName, String adapterName) {
    ...
    if (configurationName != null && !configurationName.isBlank()
            && !configuration.getName().equals(configurationName)) {
        continue;
    }
    ...
}
```

`configurationName` is guarded with a `!= null` check before `.isBlank()`, same pattern as
`originId` (`if (originId != null && !originId.isBlank())`) and `cidPrefix`
(`normalizePrefix` starts with `if (cidPrefix == null) return DEFAULT_CID_PREFIX;`). All three
optional params — `configuration`, `originId`, `cidPrefix` — are fully null-safe in
`StubbedRunner`. **No adjustment needed**: passing `req.getParameter("config")` straight through
(which is `null` when the query param is absent, not `""`) is correct and matches how the other
two optional params are already handled. `FlowController`'s own `param(qs, "config", "")`
default-to-empty-string convention is a `FlowController`-local artifact of its hand-rolled query
parser having no other way to express "absent" — it is not something `StubbedRunner` requires;
`null` and `""` are handled identically by `resolveAdapter`'s `isBlank()` check.

`adapter` is the one required param — the servlet 400s on `null`/blank before calling
`runStubbed`, so the non-null contract at the `resolveAdapter`/`getRegisteredAdapter` call site
is preserved by the caller, not by a default-empty-string trick.

## Test

No test added. Reasoning: the file has no pure, standalone-testable helper — `doPost` just
threads four request parameters and the raw body through to `StubbedRunner.runStubbed`, which
needs a live `Adapter`/`IbisManager` to do anything meaningful and isn't easily mockable at this
level. This matches the existing precedent: `TestPipelineServlet` and `AdaptersServlet` — the
closest analogues, also thin parameter-threading BEARER_ONLY servlets — have no dedicated
request-flow tests in this codebase either. Only `LadybugServlet`'s genuinely pure static helpers
(`findStorageIdInRows`, `clampLimit`, `parseReportId`) get unit tests in
`TestLoopServletHelpersTest.java`. Confirmed by reading that file before deciding.

## Build/test

`./mvnw -pl viscolink -am -q test` — exit code 0, no failures. Log noise present (all
pre-existing, unrelated to this change): `FrankApiService bean not found in console context`
(expected — that bean isn't wired in this test context) and a `FhirMetadataBuilder` "Connection
refused" fetching CDR metadata from a fake `cdr.example.com` (expected fallback-path exercise,
not a real network dependency). No test failures, no compile errors.

## Files changed

- `viscolink/src/main/java/org/frankframework/visco/security/StubbedRunServlet.java` (new)

## Fix round 1

Task review flagged two Important issues in commit `2ba4b41`. No Critical issues (auth gating,
elevation, and exception mapping were all confirmed correct). Both fixed:

**Finding 1 — no body-size cap.** Added `MAX_BODY_BYTES = 64 * 1024` (reused
`TestPipelineServlet.MAX_BODY_BYTES`'s value — stubbed-run inputs aren't expected to be larger
than a single test-pipeline message, so the same bound applies) and a
`req.getContentLengthLong() > MAX_BODY_BYTES` check, returning
`SC_REQUEST_ENTITY_TOO_LARGE`, before touching the input stream. Placed after the required-param
check (cheap, no I/O) but before resolving `StubbedRunner.getInstance()` — rejecting an
oversized body doesn't need the runner to be up first.

**Finding 2 — `req.getParameter()` could silently truncate the body.** Replaced all four
`req.getParameter(...)` calls with a package-private `param(qs, name, def)` static helper copied
into `StubbedRunServlet` itself, mirroring `FlowController`'s own private `param` helper
verbatim (same split-on-`&`/`startsWith(name + "=")`/`URLDecoder.decode` shape). `doPost` now
reads `req.getQueryString()` once into `qs` and calls `param(qs, "config", null)` etc. This
avoids Tomcat's `application/x-www-form-urlencoded` POST body-parsing trap entirely: calling
`getParameter()` on a POST can consume the input stream before `getInputStream()` is read,
silently handing `runStubbed` an empty/truncated message instead of throwing. Chose a small
self-contained copy over extracting a shared helper — four lines, and `FlowController` is in a
different package (`com.viscosiety.flow`) with no natural shared home; a copy keeps
`StubbedRunServlet` self-contained like the rest of the `AbstractBearerServiceServlet` family.

Defaults changed from `""` (original) to `null` for the three optional params (`configuration`,
`originId`, `cidPrefix`) to match `param`'s natural "absent" value — already confirmed
null-safe in `StubbedRunner.runStubbed`/`resolveAdapter` per the original null-safety check
above, so this is a no-op behaviorally, just a more direct match between "absent" and `null`
than round-tripping through `""`.

Re-ran `./mvnw -pl viscolink -am -q test`: exit code 0, no failures, no compile errors. Same
pre-existing benign log noise as before (`FrankApiService bean not found in console context`,
`FhirMetadataBuilder` CDR-metadata connection-refused fallback) — both unrelated to this change,
present in the round-1 run too.
