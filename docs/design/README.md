# Design notes

Design documents behind decisions that are not obvious from the code alone —
the problem, the alternatives weighed, and why the shipped approach won. Each
document carries a status line naming the implementing class once it landed.

| Document | Subject |
|---|---|
| [F!F console auth extension point](2026-08-06-ff-console-auth-extension-point.md) | How ViscoLink's extra endpoints share the Frank!Console's authenticator and session; includes an open upstream proposal to the Frank!Framework |
| [K8s lifecycle event publisher](2026-08-06-k8s-lifecycle-event-publisher-design.md) | Publishing configuration lifecycle events as Kubernetes Events — since upstreamed into the Frank!Framework |
| [Context failure events](2026-08-06-viscorunner-context-failure-events-design.md) | Emitting a Kubernetes Event when a WAR context fails to start in the shared Tomcat |
| [Bearer-authenticated config reload](2026-08-08-console-bearer-reload-endpoint-design.md) | A stateless-JWT service endpoint for configuration reloads, independent of the console's browser login |

New documents follow the same shape: dated filename, a status line kept
current, problem before solution, and the rejected alternatives with reasons —
those are usually the most valuable part later.
