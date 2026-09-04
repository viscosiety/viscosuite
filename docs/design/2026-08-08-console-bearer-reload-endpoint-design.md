# Bearer-authenticated config-reload endpoint

**Date:** 2026-08-08
**Status:** Superseded — the Frank!Framework gained `allowBearerAuthentication` on `OAuth2Authenticator` ([frankframework/frankframework#11542](https://github.com/frankframework/frankframework/pull/11542)), so bearer callers now reach `PUT /iaf/api/configurations` (and the console API generally) directly; `ReloadConfigurationServlet` and the other proxy-shaped `/api-service/*` servlets were removed. `ConfigRefServlet` remains (no `/iaf/api` equivalent), and this document still records why the servlet family was needed pre-#11542.

## Problem

viscoFoundry's portal has a "Reload configuration" button that should let a tenant reload their
running instance's F!F configuration without opening the console UI. It needs to call something
server-to-server (portal backend → instance), authenticated as that tenant's Keycloak service
account (already provisioned, see viscoFoundry's `mintTenantServiceAccountToken`).

F!F's console (`viscolink/iaf/**`) only supports `application.security.console.authentication.type
= OAUTH2`, which wires `OAuth2Authenticator` — `http.oauth2Login(...)` only. That's a browser
redirect + session-cookie flow; it never inspects an `Authorization: Bearer` header on incoming
requests. A bare Bearer PUT against `/iaf/api/configurations` gets redirected into a full Keycloak
login page (confirmed live: fetching `/iaf/api/server/health` with only a Bearer header returns
Keycloak's own login-flow cookies, not any F!F response). Even before that, a same-chain CSRF
filter (`csrf.enabled`, on by default) would reject any bare PUT/POST first regardless — Spring's
`CsrfFilter` runs before authentication is evaluated for unsafe methods.

F!F does ship a authenticator for exactly this — `BearerOnlyAuthenticator`
(`type=BEARER_ONLY`, stateless `oauth2ResourceServer().jwt()`, no session, no CSRF exposure since
there's no cookie to forge). But `application.security.console.authentication.type` is one setting
for the whole console — switching the *existing* console chain to it would replace `oauth2Login()`
entirely and break human browser login to `/iaf/gui/`. F!F doesn't support two authentication modes
in one `SecurityFilterChain`.

## Decision: two chains, not one

Don't touch the console's existing OAUTH2 chain at all. Add a **second, disjoint** authenticator +
endpoint, secured with `BEARER_ONLY`, on a path the console chain never claims. F!F already
supports multiple named authenticators via `application.security.http.authenticators` (a list
property) + `servlet.<name>.authenticator` — this is the same mechanism F!F's own Ladybug debugger
uses for its own separate chain (`LadybugSecurityChainConfigurer`, its own `@Order`). No F!F core
change needed; this is a viscoSuite-only addition, built from the properties-based extension point
F!F already ships, not a chain-structure hack.

Confirmed buildable from viscoSuite: `viscolink.war` is built from viscoSuite source with F!F core
as an ordinary Maven dependency (not a vendored fork) — see `viscolink/pom.xml`. viscoSuite already
injects classes into F!F's own Spring lifecycle this way (`DeploymentSpecificsBeanPostProcessor` in
package `org.frankframework.ladybug`, picked up by `SpringEnvironmentContext.xml`'s component-scan
filter). `ConsoleSecurityRegistrar` (`com.viscosiety.security`, existing) is the nearest precedent
for viscoSuite adding console-adjacent security — but that class exists to *reuse* the console's own
session/OIDC chain for other tool pages (SSO with `/iaf`); this is the opposite: a genuinely
independent, stateless auth mode.

## Components

### 1. New authenticator + servlet registration (properties, no new Java class for the auth wiring itself)

In `viscolink/src/main/resources/DeploymentSpecifics.properties` (or a stage-specific override —
confirm during planning which properties file is instance-templated vs static):

```properties
application.security.http.authenticators=bearer
application.security.http.authenticators.bearer.type=BEARER_ONLY
application.security.http.authenticators.bearer.issuerUri=${KEYCLOAK_ISSUER_URI}
application.security.http.authenticators.bearer.userNameAttributeName=preferred_username
application.security.http.authenticators.bearer.authoritiesClaimName=realm_access.roles

servlet.reload.authenticator=bearer
servlet.reload.urlMapping=/api-service/configurations
servlet.reload.securityRoles=viscoforge-tenant:${ACCOUNT_SLUG}-${INSTANCE_SLUG}
```

`ACCOUNT_SLUG`/`INSTANCE_SLUG` are per-instance values viscoFoundry's `manifests.ts` already
threads through as env vars for other properties (e.g. `configurations.tenant.repoSubdir`) — this
follows the same pattern, no new plumbing concept on the viscoFoundry side.

Note: `BearerOnlyAuthenticator` does **not** run tokens through F!F's `roleMappingFile`/
`AuthorityMapper` (that machinery is OAUTH2-specific, see `frankframework_oauth2_console_auth_facts`
memory point #1). It takes the raw `authoritiesClaimName` claim value and prefixes it
(`ROLE_viscoforge-tenant:<slug>`). So `securityRoles` above must match the *raw* Keycloak role
string, not `IbisAdmin`/`IbisTester`/etc.

### 2. `ReloadConfigurationServlet` (new Java class)

Package `org.frankframework.visco.security` — **not** `com.viscosiety.security` alongside
`ConsoleSecurityRegistrar`. Verified directly against `SpringEnvironmentContext.xml`: its
`@IbisInitializer` component-scan is `base-package="org.frankframework"`, with explicit excludes for
`org.frankframework.ladybug.*`, `.web.*`, and `.console.*`. A class outside `org.frankframework.**`
(or inside one of those three excluded subpackages) is never picked up by this scan — this is the
same scan-visibility requirement `DeploymentSpecificsBeanPostProcessor` satisfies for an unrelated
reason. This matters because the scan runs early enough to feed `ServletManager`'s
`application.security.http.authenticators` SPI, in the *same* context F!F's own authenticators build
their chains in; a bean added only via `ViscoLinkModule`'s `getSpringConfigurationFiles()` XMLs
(where `ConsoleSecurityRegistrar` itself lives) would be too late, per that class's own javadoc
account of why it can't just add a plain new chain bean there.

Behavior on `PUT /api-service/configurations`:

1. By the time this servlet runs, Spring Security's `bearer` chain has already authenticated the
   JWT and enforced `securityRoles` — no auth logic in the servlet itself.
2. Look up `FrankApiService` from the console's own `ApplicationContext` at request time. The
   console's context is a *child* of the one this servlet's bean lives in (see
   `ConsoleSecurityRegistrar`'s context table: EnvironmentContext → Console Boot context →
   IbisApplicationContext), so it can't be `@Autowired` directly — walk down from
   `ServletContext.getAttribute(ROOT_WEB_APPLICATION_CONTEXT)` the same way
   `ConsoleSecurityRegistrar.findConsoleChain` walks *up* from a child, just in the other direction
   (confirm exact direction/attribute during implementation — the goal is "find the live bean,
   don't assume a fixed context reference across a config reload").
3. Build a `RequestMessageBuilder.create(BusTopic.IBISACTION)` with `header("action",
   Action.RELOAD.name())` and `header(BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY,
   BusMessageUtils.ALL_CONFIGS_KEY)` — byte-for-byte what `Configurations.fullAction()` builds for
   an all-configs reload with no `configurations` array in the request body (see
   `frankframework/console/backend/.../controllers/Configurations.java:97-101`).
4. Call `frankApiService.callAsyncGateway(builder)` — same method F!F's own controller calls.
   Respond `202 Accepted` on success (mirroring `Configurations.fullAction()`'s own response code).

This deliberately does **not** forward into `Configurations.fullAction()` itself (that was the
other option considered — an in-process `RequestDispatcher.forward()`, which would let F!F's own
`@RolesAllowed({"IbisAdmin","IbisTester"})` also run as defense-in-depth against our mapped
authority). Calling the Bus gateway directly means our own `securityRoles` check is the *only*
authorization gate for this endpoint — acceptable because Keycloak already scopes which roles a
given tenant's service account can ever hold (a `bo` service account structurally cannot mint a
token carrying an `irisschrijvers-*` role), so the per-instance `securityRoles` match is sufficient,
not just a first layer.

### 3. `ConsoleSecurityRegistrar` touch-up

`isFrankOwnedPath()` (`com/viscosiety/security/ConsoleSecurityRegistrar.java:140-142`) currently
excludes `/iaf/`, `/api/`, `/fhir/` from its blanket `/*` tool-page session-auth filter. Add
`/api-service/` to that list — otherwise `ConsoleReuseSecurityFilter` would also try to gate this
new path with the console's *session*-based auth, on top of (and inconsistent with) the new Bearer
chain.

## Error handling

- Missing/expired/malformed Bearer token → standard `oauth2ResourceServer()` behavior: `401` with
  `WWW-Authenticate: Bearer` challenge, no F!F-specific body.
- Token valid but missing the required `securityRoles` role → `403`, plain Spring Security
  response (matches today's `AccessDeniedException` handling elsewhere in this chain — no need to
  build a custom body, the portal only needs to distinguish 2xx from non-2xx).
- Downstream `callAsyncGateway` failure (e.g. Bus unreachable) → let the underlying exception
  propagate as a `5xx`; no special handling needed, this mirrors how `Configurations.fullAction()`
  itself has no explicit catch around the same call.

## Testing

- Unit test `ReloadConfigurationServlet` with a mocked `FrankApiService`/`ApplicationContext` chain
  to verify the exact `RequestMessageBuilder` shape sent (topic, action, header keys/values).
- Integration test (viscolink's existing Spring-context test setup) verifying: valid Bearer token
  with matching `securityRoles` → `202`; valid token with a *different* instance's role → `403`; no
  token → `401`; confirm `/iaf/gui/` still requires the existing OAUTH2 session flow unaffected by
  this change (regression guard on the two-chains decision).

## Out of scope (viscoFoundry-side follow-up, separate plan)

- `manifests.ts`: add `application.security.http.authenticators*` and `servlet.reload.*` env vars
  per instance; needs `KEYCLOAK_ISSUER_URI`, `ACCOUNT_SLUG`, `INSTANCE_SLUG` values already
  available in `TenantManifestInput`.
- `ff-console-client.ts`: change `reloadAllConfigurations`'s URL from
  `${instanceUrl}/viscolink/iaf/api/configurations` to
  `${instanceUrl}/viscolink/api-service/configurations`.
- New `viscoforge-runner` image build/push (this change lives in `viscolink.war`, rebuilt via
  viscoSuite → viscoForge's `Dockerfile.viscolink` chain) before viscoFoundry can point at it.
- Existing running instances need the new image + the new env vars before the portal's reload
  button works against them — no auto-migration path is in scope here (mirrors the
  `resourcequota_limitrange_no_resync_gap` precedent: new capability lands in new/updated
  manifests, existing instances need an explicit resync).
