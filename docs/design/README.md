# Design notes

Design documents behind decisions that are not obvious from the code alone —
the problem, the alternatives weighed, and why the shipped approach won. Each
document carries a status line naming the implementing class once it landed.

| Document | Subject |
|---|---|
| [F!F console auth extension point](2026-08-06-ff-console-auth-extension-point.md) | How ViscoLink's extra endpoints share the Frank!Console's authenticator and session; includes an open upstream proposal to the Frank!Framework |
| [K8s lifecycle event publisher](2026-08-06-k8s-lifecycle-event-publisher-design.md) | Publishing configuration lifecycle events as Kubernetes Events — since upstreamed into the Frank!Framework; the INFO lifecycle (started / closed / failed to start) is published by `ConfigurationLifecycleEventPublisher` |
| [Context failure events](2026-08-06-viscorunner-context-failure-events-design.md) | Emitting a Kubernetes Event when a WAR context fails to start in the shared Tomcat |
| [Bearer-authenticated config reload](2026-08-08-console-bearer-reload-endpoint-design.md) | A stateless-JWT service endpoint for configuration reloads, independent of the console's browser login |
| [Vertical packs](2026-10-07-vertical-packs-design.md) | A market-neutral core with the healthcare specifics (FHIR, MLLP, HL7v2, the patient identifier, ViscoStore) as a pack, so the same runtime serves other markets; the pack descriptor and the subject-identifier concept the siblings consume |
| [Component delivery](2026-11-04-component-delivery-design.md) | Components as Maven artifacts pinned by a release BOM, one bare `viscobase` image and a fetcher that composes it at build or at start; the Suite-side of the platform's component model |
| [Tunnel API spike](2026-11-12-tunnel-api-spike.md) | Feasibility spike (not built): REST endpoints only on an extra TLS connector; connector added at start, routing by arrival port, closing the public `/api`, filter order |

New documents follow the same shape: dated filename, a status line kept
current, problem before solution, and the rejected alternatives with reasons —
those are usually the most valuable part later.
