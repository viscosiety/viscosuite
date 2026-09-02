# Design: Kubernetes Event on Frank!Framework lifecycle warnings/errors

**Date:** 2026-08-06
**Module:** viscolink (with a later, out-of-scope upstream Frank!Framework extraction)
**Status:** Implemented — see `LifecycleEventK8sPublisher` (viscolink) and its tests.

## Problem

When a Frank!Framework configuration (tenant) fails to start or emits a lifecycle
warning/error, the only evidence today is a log line. A prior approach scraped pod logs
with regexes to detect "aborted starting" — fragile, gated on the pod actually crashing,
and blind to non-crashing warnings.

Frank!Framework already publishes these conditions as **Spring application events** inside
the container, and the pod already has in-cluster Kubernetes API access (the
`frankframework-kubernetes` credential provider talks to the apiserver with the pod's
ServiceAccount token). We can turn those Spring events directly into **Kubernetes Events**
on the pod object — a first-class, structurally clean channel that `kubectl get events`,
the portal's namespace-event poller, and any other consumer already read. No log scraping,
no regex, and it catches non-crashing warnings that never appear in crash-as-evidence logs.

## Corrected premises (from source investigation)

Two assumptions from the original idea proved wrong and shaped the design:

1. **"Zero new RBAC" is false.** The credential provider only reads Secrets
   (`get`/`list` on `secrets`). Posting an Event is `create` on the distinct `events`
   resource. The pod's ServiceAccount needs a new `events: create` grant. The portal
   *reading* events is unrelated to the pod *writing* them.

2. **"Reuse the same bean" is not a drop-in upstream.** The fabric8 client lives inside
   `KubernetesCredentialFactory`, which is an SPI/reflection-loaded credential provider —
   **not** a Spring bean — in a module that only knows credentials. The lifecycle events
   are Spring events in the application context. `core` has no fabric8 dependency and must
   not depend on `kubernetes`. An upstream Frank!Framework version therefore needs a *new*
   Spring-managed component in a fabric8-capable module. That is out of scope here; viscolink
   is the natural home to ship now (fabric8 `kubernetes-client` 7.7.0 is already on its
   classpath via `frankframework-kubernetes`, viscolink/pom.xml:122).

## Source facts this relies on

- `Configuration.java:233` publishes `ConfigurationMessageEvent(this, "aborted starting; " +
  e.getMessage(), MessageEventLevel.WARN)`. Name/version mismatches at `:422`/`:438` are also
  WARN `ConfigurationMessageEvent`s.
- `ConfigurationMessageEvent.java:36` — the exception-carrying constructor is
  `MessageEventLevel.ERROR`.
- `IbisContext.log(msg, level, e)` (`IbisContext.java:413`) publishes an
  **`ApplicationMessageEvent`**. The hardest failure class — a config throwing during load
  ("an exception occurred while loading configuration [X]", `IbisContext.java:310`/`:387`) —
  and autoload failure (`ConfigurationAutoDiscovery.java:122`) arrive as ERROR
  `ApplicationMessageEvent`s, *not* `ConfigurationMessageEvent`s.
- `MessageEventLevel` = `INFO, WARN, ERROR`.
- `MessageEventListener` (core) is a Spring `ApplicationListener<MessageEvent<?>>` — the
  proven hook shape. Spring propagates child-context events up to parent-context listeners.
- `AbstractKubernetesCredentialProvider` builds an in-cluster fabric8 `KubernetesClient` and
  probes with `getKubernetesVersion()` — the pattern the enablement check mirrors.

## Component

A single new class:

```
com.viscosiety.k8s.LifecycleEventK8sPublisher
    implements ApplicationListener<MessageEvent<?>>, DisposableBean
```

Registered as a bean in a viscolink Spring XML, in the **parent application context** (the
context where core's `MessageEventListener` is registered and where propagated events land —
not a child module context). Confirming the exact spring-file/context seam is the one open
wiring item for the implementation plan.

Deliberately dependency-light — fabric8 client API + F!F event types only — so it lifts
cleanly into an upstream Frank!Framework module later without dragging viscolink specifics.

## Filter

Emit for:

```
(event instanceof ConfigurationMessageEvent || event instanceof ApplicationMessageEvent)
    && (level == WARN || level == ERROR)
```

INFO is skipped (normal "started / closed / reload ok" lifecycle chatter). `AdapterMessageEvent`
is out of scope for this iteration. A single config failure may legitimately produce more than
one Event (e.g. a Configuration WARN abort plus an IbisContext Application ERROR) — acceptable,
since delivery is emit-every-time with no dedup.

## Event mapping (Kubernetes core/v1 Event)

Kubernetes core/v1 `Event.type` is conventionally only `Normal` or `Warning`, so both F!F WARN
and ERROR map to k8s **`type=Warning`**; severity is carried in `reason`, not `type`.

| Field | Value |
|---|---|
| `type` | `Warning` |
| `reason` | `ConfigurationAborted` (message starts with "aborted starting") · `ConfigurationError` (level ERROR) · `ConfigurationWarning` (other WARN) |
| `message` | `<config name or "application">: <event message text>` |
| `involvedObject` | this Pod (kind `Pod`, name = pod name, namespace = pod namespace) |
| `reportingComponent` | `viscolink` |
| timestamps | event time = now (`firstTimestamp`/`lastTimestamp`/`eventTime`) |

Source name extraction: `ConfigurationMessageEvent.getSource().getName()` for the config
subtype; the literal `"application"` for `ApplicationMessageEvent` (source is the
ApplicationContext).

## Enablement (auto-detect, no-op off-cluster)

At bean initialization:

1. Build an in-cluster fabric8 `KubernetesClient` (fabric8 reads the SA token, CA, and
   namespace from `/var/run/secrets/kubernetes.io/serviceaccount`), with short connection/request
   timeouts like the credential provider.
2. Probe with `getKubernetesVersion()`.
3. Success → active. Any failure (no token → docker/LOC, or unreachable) → log **once** at INFO,
   set the client to null; every later `onApplicationEvent` is a no-op.

No configuration property. Works in Kubernetes, invisible everywhere else.

## Pod identity

`involvedObject`:

- kind `Pod`
- name from the `POD_NAME` environment variable (injected via the downward API,
  `fieldRef: metadata.name`), falling back to `HOSTNAME` (which Kubernetes sets to the pod
  name by default).
- namespace from the fabric8 in-cluster namespace file (`client.getNamespace()`).

## Error handling

The entire listen → build → POST path is wrapped. Any fabric8 exception (including a `403`
before the RBAC grant is applied) is caught and logged at WARN, never rethrown — emitting an
Event must never perturb the configuration lifecycle. The client is closed on context shutdown
via `DisposableBean.destroy()`.

## Delivery semantics

Emit every matching event, no dedup and no throttling. Config reloads / ReloadJob retries can
re-fire the same condition; that is accepted for this iteration to keep the component simple.

## Deliverables

1. `LifecycleEventK8sPublisher` class.
2. Spring bean registration in the correct (parent) viscolink context.
3. Unit test using fabric8 `KubernetesMockServer`:
   - a WARN `ConfigurationMessageEvent` produces exactly one core/v1 Event with the expected
     `reason`, `type=Warning`, and `involvedObject`;
   - an ERROR `ApplicationMessageEvent` produces one Event with `reason=ConfigurationError`;
   - an INFO event and the off-cluster (null-client) state each produce nothing.
4. Kubernetes manifest additions (authored in this repo, applied by ops):
   - `POD_NAME` downward-API env on the viscolink Deployment;
   - a `Role` granting `create` on `""/events` in the pod namespace and a `RoleBinding` to the
     pod's ServiceAccount.

## Out of scope

- Upstream Frank!Framework PR (needs a new Spring-managed, fabric8-capable module + a
  bean-registration seam the SPI credential factory cannot provide). Noted so the class stays
  extractable; not built here.
- `AdapterMessageEvent` (per-adapter runtime) Events.
- Dedup / throttling / K8s server-side aggregation.
- Any change to the portal's event-reading side.

## Open item for the plan

Confirm the exact spring configuration file and application-context level at which the bean
must be registered so that propagated `ConfigurationMessageEvent` / `ApplicationMessageEvent`
instances actually reach it (parent application context, alongside core's
`MessageEventListener` — not a child `IbisApplicationContext`).
