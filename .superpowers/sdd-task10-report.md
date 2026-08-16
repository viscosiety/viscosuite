# Task 10 report — correlationId lookup on LadybugServlet

## What changed

`viscolink/src/main/java/org/frankframework/visco/security/LadybugServlet.java`:

- Added a new mode to the existing `/reports` path: when a `correlationId` query
  parameter is present, resolves that report's `storageId` via `findStorageIdByCorrelationId`
  (scans the most recent `CORRELATION_LOOKUP_SCAN_LIMIT` (50) metadata rows for a matching
  `correlationId`, same technique the old unauthenticated `LadybugClient` used against ~30 rows,
  just with headroom and now behind the servlet's existing bearer-JWT + `callElevated`
  (IbisObserver) auth). Returns `200 {"storageId": <int>}` on a match, `404` if no recent
  report carries that correlationId.
- Existing `GET /api-service/ladybug/reports?limit=N` (no `correlationId`) is unchanged —
  the new branch is checked first and returns early, falling through to the original list
  logic untouched when `correlationId` is absent.
- Added `CORRELATION_LOOKUP_METADATA_NAMES` (`["storageId", "correlationId"]`) and
  `CORRELATION_LOOKUP_SCAN_LIMIT` (50) constants near `METADATA_NAMES`.
- Added `findStorageIdByCorrelationId(Storage, String)` (storage-hitting) and
  `findStorageIdInRows(List<List<Object>>, String)` (pure, unit-testable) helpers near
  `listReports`.
- Updated the class javadoc's bullet list with the new `?correlationId=<id>` operation.

`viscolink/src/test/java/org/frankframework/visco/security/TestLoopServletHelpersTest.java`:

- Added `findStorageIdInRowsMatchesByCorrelationId`, exercising `findStorageIdInRows`
  directly against a small in-memory row list (match and no-match cases), right next to
  the existing `clampLimit`/`parseReportId` tests for `LadybugServlet`. No new test file
  created, per instructions.

## TDD sequence followed

1. Added the test first. Ran `./mvnw -pl viscolink -am -q test -Dtest=TestLoopServletHelpersTest -DfailIfNoTests=false` — confirmed compilation failure (`cannot find symbol: method findStorageIdInRows`).
2. Implemented the servlet changes (constants, `doGet` branch, two helper methods, javadoc bullet).
3. Re-ran the same targeted test command — passed with no output (quiet mode), confirmed via
   the surefire report: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.
4. Ran the broader module build: `./mvnw -pl viscolink -am -q test` — exit code 0, i.e. the
   full viscolink test suite (all other test classes) still passes; nothing else broke.

## Build/test commands used

```
./mvnw -pl viscolink -am -q test -Dtest=TestLoopServletHelpersTest -DfailIfNoTests=false
./mvnw -pl viscolink -am -q test
```

(Repo is a Maven multi-module project; `-pl viscolink -am` builds/tests the `viscolink`
module plus its required upstream modules.)

## Scope confirmation

- Only `LadybugServlet.java` and `TestLoopServletHelpersTest.java` touched.
- No other repo files needed changes, per the task's framing (same URL prefix, same
  servlet name, same `servlet.ladybug.*` property wiring, same security roles).
- Working tree in this worktree was clean before starting and only these two files are
  modified.

## Downstream consumer (not part of this task, informational only)

viscoForge's `ShareController` is expected to call
`GET /api-service/ladybug/reports?correlationId=<id>` (bearer-authenticated) in place of
its current unauthenticated loopback lookup, using the returned `storageId` the same way
it previously used the scanned-metadata result.
