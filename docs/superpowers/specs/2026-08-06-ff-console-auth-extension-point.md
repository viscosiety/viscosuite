# Upstream proposal: let external modules attach URL patterns to an existing F!F authenticator

**Date:** 2026-08-06
**Status:** Proposal (approach 2). The in-viscoLink runtime reuse (approach 1) is the current bridge; see `ConsoleSecurityRegistrar`.

## Problem

viscoSuite (and, generally, anyone extending Frank!Framework) adds tools/apps that live in the same
WAR as F!F and must authenticate **exactly** like the Frank!Console — Basic when the console is
`IN_MEMORY`, OIDC when it is `OAUTH2`, open when `NONE` — sharing the same session (SSO with `/iaf`).

F!F offers no supported way for an external module to say *"secure these extra URL patterns with the
console authenticator."* The console authenticator and its `SecurityFilterChain` are built during F!F
startup from `application.security.console.authentication.*`; external module beans (in the child
`IbisApplicationContext`) run afterwards and cannot contribute endpoints to it.

Two workarounds both have real downsides:

1. **Stand up a second authenticator** from the same property prefix (what viscoLink first tried).
   Fails for OAuth: `OAuth2Authenticator.getOrCreateClientRegistrationRepository()` does
   `SpringUtils.registerSingleton(ctx, "clientRegistrationRepository", …)` with a **hard-coded** bean
   name, so a second instance in the same context throws
   `IllegalStateException: Could not register … under bean name 'clientRegistrationRepository'`.
   It also creates a *separate* OAuth client/registration — not aligned with the console.

2. **Reuse the built console chain at runtime** (approach 1, current). Locate F!F's console
   `SecurityFilterChain` by matching a synthetic `/iaf/gui/` request against the registered
   `SecurityFilterChain` beans, keep its authentication filters, drop its `/iaf`-scoped
   `AuthorizationFilter`, append an `authenticated()` filter over the tool paths, and run it as a
   Tomcat filter. Works and is reflection-free, but it **couples to F!F's internal chain structure**
   (filter ordering, the assumption that dropping the `AuthorizationFilter` leaves a usable
   authentication chain) and can break on F!F upgrades.

## Proposed extension point

Give `ServletManager` (or the console/`AbstractServletAuthenticator` machinery) a **supported hook**
for external code to register additional secured URL patterns against an existing authenticator
*before* that authenticator's chain is built. Two concrete shapes, in order of preference:

### Option A — a `SecurityEndpointContributor` SPI (preferred)

A bean interface collected during F!F's security bootstrap, before `startAuthenticators()`:

```java
package org.frankframework.lifecycle.servlets;

/** Contributes extra secured endpoints to a named authenticator before its chain is built. */
public interface SecurityEndpointContributor {
    /** Called before authenticators are built; register endpoints via the callback. */
    void contribute(SecurityEndpointRegistry registry);
}

public interface SecurityEndpointRegistry {
    /**
     * Secure {@code urlPatterns} using the same authenticator that secures {@code sampleUrl}
     * (e.g. "/iaf/gui/" to mean "the console authenticator"), as private (authenticated) endpoints.
     * {@code publicPatterns} are registered as permit-all.
     */
    void secureLikeConsole(List<String> urlPatterns, List<String> publicPatterns);

    /** Same, but targeting an authenticator by its configured name. */
    void secureWith(String authenticatorName, List<String> urlPatterns, List<String> publicPatterns);
}
```

`ServletManager.startAuthenticators()` would, before calling `authenticator.build()`, invoke each
`SecurityEndpointContributor`, translating `secureLikeConsole(...)` into extra `ServletConfiguration`
registrations on the console authenticator (`authenticator.registerServlet(config)` with the given
roles/url-mappings). Because the endpoints are added **before** `build()`, they land in the single
console chain with its single `clientRegistrationRepository`, its session handling, and its entry
point — no duplicate authenticator, no reflection, deterministic.

viscoLink then replaces `ConsoleSecurityRegistrar` with a tiny bean:

```java
class ViscoLinkConsoleEndpoints implements SecurityEndpointContributor {
    public void contribute(SecurityEndpointRegistry r) {
        r.secureLikeConsole(
            List.of("/", "/home.html", "/branding.js", "/flow/*", "/flow-api/*", "/tools/*", "/demo-tools/*"),
            List.of("/tools/health"));
    }
}
```

Note on `servletPath`: `OAuth2Authenticator` derives its OIDC endpoints from
`getPrivateEndpoints().stream().findFirst()`. Adding external endpoints changes that set, so the SPI
should either (a) keep OIDC endpoints pinned to the console's primary servlet (e.g. always compute
`servletPath` from a designated primary endpoint), or (b) document that the resulting redirect URI is
stable-but-derived and must match the IdP client. Pinning (a) is the safer upstream behaviour and is
worth fixing alongside this SPI.

### Option B — expose the console authenticator + a public `addSecuredEndpoints`

Less structured: publish the resolved console `IAuthenticator` (or a facade) as a bean, and add a
public `addSecuredEndpoints(List<String> urlPatterns, List<String> publicPatterns)` that is valid to
call only during a defined pre-build lifecycle phase. Simpler to add, but ordering/lifecycle contract
is easier to misuse than the contributor SPI.

## Secondary upstream fix (independent, still worthwhile)

Make `OAuth2Authenticator` tolerate an already-present `clientRegistrationRepository`: reuse the
existing bean instead of blindly re-registering (`if (ctx.containsBean("clientRegistrationRepository"))
reuse; else create+register`). This removes the hard failure when two OAuth authenticators exist in
one context and would let approach-1-style reuse be far less fragile even without the SPI.

## Acceptance

- An external module secures arbitrary URL patterns with the console authenticator via a public API,
  no reflection, no chain-structure assumptions.
- Basic/OIDC/NONE all behave identically to `/iaf`, sharing the session (SSO).
- OIDC redirect URI is deterministic and documented.
- viscoLink's `ConsoleSecurityRegistrar` runtime-reuse is deleted in favour of the SPI bean.
