# Design: viscorunner emits a Kubernetes Event when a WAR context fails to start

**Date:** 2026-08-06
**Module:** viscorunner (the shared Tomcat under viscolink + viscostore)
**Status:** Approved for planning

## Problem

When a WAR context fails to bootstrap — e.g. `/viscolink` failing its Spring Security refresh because
an OAuth `authAlias` is missing — Tomcat marks **that context** `FAILED` but keeps running. The pod
stays "up" with a dead context and, unless a probe happens to catch it, **Kubernetes gets no signal**.

The in-WAR `LifecycleEventK8sPublisher` (viscolink) cannot cover this: it is a bean *inside* the
context whose refresh aborts, so it never initializes, and the failure is a thrown exception + a
`FATAL`/`SEVERE` log, not a Frank!Framework `MessageEvent`. The shared Tomcat layer, which survives a
single WAR's failure and sees every context, is the right place to report it.

## Coverage: two failure subclasses (only one is a real gap)

A context-start failure lands in one of two subclasses, and this feature deliberately targets only
the second — verified against a live k3d cluster:

1. **Startup aborts (`exit 1`).** Some failures propagate up `StandardContext → Host → Engine →
   Service → Server` to `Catalina.start`, so `Server.start` throws and the JVM exits 1. The container
   then crash-loops, and **kubelet already emits `Warning BackOff` / `CrashLoopBackOff` Events** on the
   pod — Kubernetes covers this natively. (Also, `AFTER_START` never fires, and the cause often lives
   only in the WAR's log4j2 `FATAL`, not JULI, so a shared-layer capture would add little.) A viscolink
   `console.authentication.type=OAUTH2` with no client credentials is an example — observed to exit 1
   and crash-loop, with a kubelet `BackOff` Event already present.
2. **Context `FAILED`, Tomcat survives.** A descriptor/WAR context goes `FAILED` (HostConfig catches
   it) while `Server.start` completes normally. The pod stays `Running` with a dead context and **no
   Kubernetes signal at all** unless a probe happens to catch it. This is the silent gap this
   listener fills: `AFTER_START` fires, the scan finds the `FAILED` context(s), and an Event is
   emitted. Verified in k3d — a broken `/badapp` descriptor (missing Valve class) produced a real
   `ContextStartFailed` Event while viscolink/viscostore deployed alongside it.

So the `AFTER_START` scan is the correct hook: it catches exactly the subclass Kubernetes does not.

## Corrected premise (JUL, not log4j2)

The rich cause (`Error deploying deployment descriptor [.../viscolink.xml] … Caused by:
NoSuchElementException: authAlias [viscoforge-tenant-client] not found`) is logged by **Tomcat's own
JULI** (`org.apache.catalina.startup.HostConfig`, `SEVERE`) — on the shared classloader. The WAR's
log4j2 `FATAL` lives inside the webapp's log4j2 context, unreachable from the shared layer without
editing each WAR's logging config. So the cause-capture hook is a bounded **root `java.util.logging`
`Handler`**, not a log4j2 appender.

## Registration decision (the flagged item)

Register **one `LifecycleListener` on `<Server>`** via a shipped `server.xml`. Rejected alternatives:

- **`conf/logging.properties` handler only** — lighter, but the handler + fabric8 must load under
  JULI's early classloader; fragile.
- **Global `conf/context.xml` `<Listener>`** — lightest (viscorunner already ships `context.xml`),
  but a context-attached listener does not reliably fire on a hard start-failure (`StandardContext`
  enters `FAILED` without a guaranteed `AFTER_START` listener event) — it can miss the very failures
  we target.

`server.xml` is the only reliable Server-scope hook. **Tradeoff:** we ship a `server.xml` pinned to
the base image's `tomcat:11.0.14` default and must re-sync it on a Tomcat bump. The listener
self-installs the JUL handler, so no `logging.properties` change and no JULI-init classloader fight.

## Component

`com.viscosiety.viscorunner.k8s.ContextFailureEventPublisher` — a Tomcat
`org.apache.catalina.LifecycleListener` registered as `<Listener>` under `<Server>`. It ships in a
small viscorunner jar placed on Tomcat's shared classloader (a new `/opt/frank/lib/*.jar` appended to
`common.loader`), together with the fabric8 `kubernetes-client`.

### Flow

- **`BEFORE_START_EVENT`** — off-cluster (no in-cluster fabric8 client) → do nothing further. Otherwise
  attach a bounded root JUL `Handler` (the cause buffer) that retains the last N `SEVERE` records that
  carry a `Throwable`.
- **`AFTER_START_EVENT`** — walk `Server → findServices() → getContainer() (Engine) → findChildren()
  (Hosts) → findChildren() (Contexts)`; for each `Context` whose `getState() == LifecycleState.FAILED`,
  emit one core/v1 Event, then detach the handler.

### Event mapping (Kubernetes core/v1)

| Field | Value |
|---|---|
| `type` | `Warning` |
| `reason` | `ContextStartFailed` |
| `message` | `context [<contextPath>] failed to start` + the best-matching captured cause (match the buffer by context path, else the most recent `SEVERE`) |
| `involvedObject` | this Pod (kind `Pod`, name from `POD_NAME`→`HOSTNAME`, namespace from the in-cluster file) |
| `reportingComponent` | `viscorunner` |
| timestamps | now |

### Enablement / identity

Same as the viscolink publisher: build an in-cluster fabric8 `KubernetesClient` and probe
(`getKubernetesVersion()`); success → active, any failure → log once and no-op. `POD_NAME` downward-API
env with `HOSTNAME` fallback; namespace from the in-cluster namespace file. Off-cluster (docker, LOC)
→ silent no-op. **Same `events: create` RBAC and `POD_NAME` env** as the viscolink publisher, so a
deployment that already granted those for that feature needs nothing new.

### Error handling

The whole listen → scan → POST path is wrapped; any fabric8 error is caught and logged, never
rethrown — reporting a failure must never disturb Tomcat startup. The client is closed when the Server
stops.

## Deliverables

1. `ContextFailureEventPublisher` (+ the bounded JUL `Handler`) in a viscorunner jar.
2. A shipped `server.xml` (the pinned `tomcat:11.0.14` default + our `<Listener>` under `<Server>`),
   `COPY`ed in the Dockerfile.
3. viscorunner pom: add fabric8 (compile) + a `copy-dependencies` execution assembling `target/lib`
   (viscorunner jar + fabric8 + transitives); Dockerfile copies `target/lib → /opt/frank/lib`;
   `catalinaAdditional.properties` appends `/opt/frank/lib/*.jar` to `common.loader`.
4. Unit tests: the FAILED-context tree scan builds the right Event; the JUL buffer extracts a nested
   cause message; POST via fabric8 `KubernetesMockServer`.
5. Live test: deploy a WAR with a deliberately broken context (the real OAuth `authAlias` failure) and
   assert a `ContextStartFailed` Event on the pod.

## Relationship to the viscolink publisher

Complementary, different layers: `LifecycleEventK8sPublisher` (in-WAR) covers *app-is-up, a
config/tenant aborted or warned*; this covers *the whole WAR context failed to bootstrap*, and covers
**both** viscolink and viscostore. Different classloaders, so the small fabric8-event + identity logic
is duplicated (acceptable), with identical reason/type/involvedObject conventions.

## Out of scope

- Failures after startup (lazy reload); only the boot-time `AFTER_START` scan is covered in v1.
- Dedup/throttling.
- Any change to the portal's event-reading side.
