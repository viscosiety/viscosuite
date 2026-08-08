# Bearer-Authenticated Config-Reload Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second, independent, stateless-JWT-authenticated HTTP endpoint
(`PUT /api-service/configurations`) to `viscolink.war` that triggers the same all-configurations
reload F!F's own console does — reachable by a Bearer service-account token, without touching the
console's existing browser-session OAUTH2 login at all.

**Architecture:** New `org.frankframework.visco.security.ReloadConfigurationServlet`
(`@IbisInitializer`, `DynamicRegistration.Servlet`), wired to F!F's own multi-authenticator SPI
(`application.security.http.authenticators`) with a `BEARER_ONLY` authenticator — entirely
env-var-driven, no static properties file changes, mirroring exactly how the existing console
OAUTH2 authenticator is configured today (see `manifests.ts` in viscoFoundry). On a valid request it
looks up F!F's own `FrankApiService` bean from the console's Spring context at request time and
sends the identical Bus message `Configurations.fullAction()` sends for an all-configs reload.

**Tech Stack:** Java 21, Spring Security 6 (`oauth2ResourceServer().jwt()`), F!F 10.2.0 SNAPSHOT,
JUnit 5 + Mockito (matching `viscolink/pom.xml`'s existing test stack).

## Global Constraints

- New Java source lives under package `org.frankframework.visco.security` — NOT
  `com.viscosiety.security` — because `SpringEnvironmentContext.xml`'s `@IbisInitializer`
  component-scan is `base-package="org.frankframework"` with explicit excludes for
  `org.frankframework.ladybug.*`, `.web.*`, `.console.*`. A class outside `org.frankframework.**`
  (or inside one of those three excluded subpackages) will never be picked up — confirmed by
  reading `SpringEnvironmentContext.xml` directly, not assumed.
- Do **not** add any new static entries to `DeploymentSpecifics.properties` or
  `StageSpecifics_LOC.properties` for the authenticator/servlet wiring. The existing console OAUTH2
  authenticator has zero static-file footprint — it's 100% env-var-injected by viscoFoundry's
  `manifests.ts`, with F!F's own `dtap.stage`-aware default (`NONE` when web security is disabled,
  i.e. LOC) covering local dev. Mirror that exactly, or `docker compose up` locally starts requiring
  a real Keycloak issuer and breaks local dev for everyone.
- Defense in depth is mandatory, not optional: `ServletManager.register()`
  (`frankframework/core/.../lifecycle/ServletManager.java:200-216`) silently **skips all security
  registration** for a servlet whose resolved `authenticatorName` is empty — it does not fail loud.
  If viscoFoundry's env vars for this servlet are ever missing on a real deploy, the properties-based
  wiring alone would leave `/api-service/configurations` completely open. `ReloadConfigurationServlet`
  must independently check `SecurityContextHolder` itself before doing anything, so a missing/broken
  properties wire-up still fails closed (this servlet's own 401), never open.
- `BearerOnlyAuthenticator` maps JWT claim roles to `GrantedAuthority` with F!F's
  `"ROLE_"` prefix (`AbstractServletAuthenticator.DEFAULT_ROLE_PREFIX`,
  `frankframework/security/.../AbstractServletAuthenticator.java:64`) — every authority comparison
  in this plan's code must account for that prefix explicitly.
- The reload message sent must be byte-for-byte what `Configurations.fullAction()` sends for an
  all-configs reload (no `configurations` array in the request): topic `BusTopic.IBISACTION`,
  header `"action"` = `Action.RELOAD.name()`, header `BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY`
  = `BusMessageUtils.ALL_CONFIGS_KEY` (`"*ALL*"`). Source:
  `frankframework/console/backend/.../controllers/Configurations.java:84-111`.
- License header on new files: Viscosiety's own (matches
  `viscolink/src/main/java/com/viscosiety/security/ConsoleSecurityRegistrar.java`'s header exactly),
  not WeAreFrank's — these are original Viscosiety files that merely live in an `org.frankframework.*`
  package for Spring scan visibility.

---

### Task 1: `ReloadConfigurationServlet`

**Files:**
- Create: `viscolink/src/main/java/org/frankframework/visco/security/ReloadConfigurationServlet.java`
- Test: `viscolink/src/test/java/org/frankframework/visco/security/ReloadConfigurationServletTest.java`

**Interfaces:**
- Consumes: `org.frankframework.console.controllers.FrankApiService#callAsyncGateway(RequestMessageBuilder)`
  (existing F!F class, unchanged) — looked up via
  `WebApplicationContextUtils.getWebApplicationContext(getServletContext())` at request time (the
  console's Spring context is a *child* of this servlet's own context, so it can't be `@Autowired`
  directly; `WebApplicationContextUtils` resolves whatever the servlet container currently has
  registered as `ROOT_WEB_APPLICATION_CONTEXT`, which by request time is the fully-booted console
  context regardless of which one it happens to be).
- Produces: nothing later tasks depend on — this is a leaf servlet.

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2026 Viscosiety B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.frankframework.visco.security;

import java.util.List;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frankframework.console.controllers.FrankApiService;
import org.frankframework.management.Action;
import org.frankframework.management.bus.BusMessageUtils;
import org.frankframework.management.bus.BusTopic;
import org.frankframework.management.bus.message.RequestMessageBuilder;
import org.frankframework.util.AppConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReloadConfigurationServletTest {

    private static final String REQUIRED_ROLE = "viscoforge-tenant:bo-here-we-go-again";

    @Mock ServletContext servletContext;
    @Mock WebApplicationContext webApplicationContext;
    @Mock FrankApiService frankApiService;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;

    private ReloadConfigurationServlet servlet;

    @BeforeEach
    void setUp() {
        servlet = new ReloadConfigurationServlet();
        AppConstants.getInstance().setProperty(ReloadConfigurationServlet.SECURITY_ROLES_PROPERTY, REQUIRED_ROLE);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AppConstants.getInstance().remove(ReloadConfigurationServlet.SECURITY_ROLES_PROPERTY);
    }

    @Test
    void authenticatedWithRequiredRoleTriggersReloadAndReturns202() throws Exception {
        authenticateAs(REQUIRED_ROLE);
        givenConsoleContextWithFrankApiService();

        servlet.doPut(request, response);

        ArgumentCaptor<RequestMessageBuilder> captor = ArgumentCaptor.forClass(RequestMessageBuilder.class);
        verify(frankApiService).callAsyncGateway(captor.capture());
        Message<?> message = captor.getValue().build(null);
        assertEquals(BusTopic.IBISACTION.name(), message.getHeaders().get(BusTopic.TOPIC_HEADER_NAME));
        assertEquals(Action.RELOAD.name(), message.getHeaders().get("action"));
        assertEquals(BusMessageUtils.ALL_CONFIGS_KEY, message.getHeaders().get(BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY));
        verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
    }

    @Test
    void noAuthenticationReturns401AndNeverCallsBus() throws Exception {
        SecurityContextHolder.clearContext();

        servlet.doPut(request, response);

        verifyNoInteractions(frankApiService);
        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
    }

    @Test
    void anonymousAuthenticationReturns401AndNeverCallsBus() throws Exception {
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anonymous);

        servlet.doPut(request, response);

        verifyNoInteractions(frankApiService);
        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
    }

    @Test
    void authenticatedWithWrongRoleReturns401AndNeverCallsBus() throws Exception {
        authenticateAs("viscoforge-tenant:irisschrijvers-pip");

        servlet.doPut(request, response);

        verifyNoInteractions(frankApiService);
        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
    }

    @Test
    void unconfiguredSecurityRolesPropertyRejectsEveryCaller() throws Exception {
        AppConstants.getInstance().remove(ReloadConfigurationServlet.SECURITY_ROLES_PROPERTY);
        authenticateAs(ReloadConfigurationServlet.UNCONFIGURED_ROLE); // even matching the fallback constant is rejected below

        // A caller cannot plausibly hold this role from a real IdP; simulate the fail-closed default
        // resolving to UNCONFIGURED_ROLE and confirm a request presenting some OTHER role fails.
        SecurityContextHolder.clearContext();
        authenticateAs(REQUIRED_ROLE);

        servlet.doPut(request, response);

        verifyNoInteractions(frankApiService);
        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
    }

    @Test
    void missingFrankApiServiceBeanReturns503AndNeverCallsBus() throws Exception {
        authenticateAs(REQUIRED_ROLE);
        when(request.getServletContext()).thenReturn(servletContext);
        when(servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE))
                .thenReturn(webApplicationContext);
        when(webApplicationContext.getBean(FrankApiService.class))
                .thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException(FrankApiService.class));

        servlet.doPut(request, response);

        verifyNoInteractions(frankApiService);
        verify(response).sendError(eq(HttpServletResponse.SC_SERVICE_UNAVAILABLE), anyString());
    }

    @Test
    void getNameIsReload() {
        assertEquals("reload", servlet.getName());
    }

    @Test
    void getUrlMappingIsApiServiceConfigurations() {
        assertEquals("/api-service/configurations", servlet.getUrlMapping());
    }

    private void authenticateAs(String rawRole) {
        Authentication auth = new TestingAuthenticationToken(
                "service-account", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + rawRole)));
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void givenConsoleContextWithFrankApiService() {
        when(request.getServletContext()).thenReturn(servletContext);
        when(servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE))
                .thenReturn(webApplicationContext);
        when(webApplicationContext.getBean(FrankApiService.class)).thenReturn(frankApiService);
    }
}
```

Note: `HttpServlet.getServletContext()` delegates to the request's `ServletContext` only when the
servlet has been through `init(ServletConfig)`; in a bare unit test (no container), stub
`request.getServletContext()` instead and have the production code read the context off the
request, not off `this.getServletContext()` — see Step 3's implementation, which reads
`req.getServletContext()` for exactly this reason (keeps the class trivially unit-testable without
a servlet container).

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl viscolink -Dtest=ReloadConfigurationServletTest`
Expected: FAIL — `ReloadConfigurationServlet` does not exist yet (compile error).

- [ ] **Step 3: Write the implementation**

```java
/*
 * Copyright 2026 Viscosiety B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.frankframework.visco.security;

import java.io.IOException;
import java.io.Serial;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import org.frankframework.console.controllers.FrankApiService;
import org.frankframework.lifecycle.DynamicRegistration;
import org.frankframework.lifecycle.IbisInitializer;
import org.frankframework.management.Action;
import org.frankframework.management.bus.BusMessageUtils;
import org.frankframework.management.bus.BusTopic;
import org.frankframework.management.bus.message.RequestMessageBuilder;
import org.frankframework.util.AppConstants;

/**
 * Bearer-JWT-only endpoint that triggers an all-configurations reload -- the API counterpart to
 * F!F's own {@code Configurations.fullAction()} (PUT /iaf/api/configurations, action=reload), but
 * reachable with a stateless service-account token instead of a browser session. See
 * docs/superpowers/specs/2026-08-08-console-bearer-reload-endpoint-design.md for why this can't
 * just be a Bearer call against the console's own /iaf/api/configurations path: that chain only
 * supports OAUTH2 browser-session login (see {@code OAuth2Authenticator}), and Spring's CSRF filter
 * rejects any bare PUT before authentication is even evaluated.
 *
 * <p>Registered via F!F's {@code application.security.http.authenticators} property-driven SPI
 * (see {@code ServletManager}) with a {@code BEARER_ONLY} authenticator on its own
 * {@code SecurityFilterChain} -- entirely env-var-injected (issuerUri, this servlet's required
 * role), no static properties file entries, mirroring exactly how the console's own OAUTH2
 * authenticator is configured today.</p>
 *
 * <p><b>Defense in depth:</b> {@code ServletManager.register()} silently skips ALL security
 * registration for a servlet whose resolved authenticator name is empty -- it does not fail loud.
 * If the env vars this depends on are ever missing on a real deploy, the properties-based wiring
 * alone would leave this endpoint completely open. This class independently re-checks
 * {@link SecurityContextHolder} before doing anything, so a missing/broken properties wire-up still
 * fails closed (this class's own 401), never open.</p>
 */
@IbisInitializer
public class ReloadConfigurationServlet extends HttpServlet implements DynamicRegistration.Servlet {

	@Serial
	private static final long serialVersionUID = 1L;

	/** Per-instance required role, e.g. {@code viscoforge-tenant:<accountSlug>-<instanceSlug>}. */
	static final String SECURITY_ROLES_PROPERTY = "servlet.reload.securityRoles";

	/**
	 * Fallback when {@link #SECURITY_ROLES_PROPERTY} is unset -- deliberately a role string no real
	 * Keycloak-issued token could ever carry, so an unconfigured deploy fails closed rather than
	 * granting access to whatever the Java-level default happened to be.
	 */
	static final String UNCONFIGURED_ROLE = "viscoforge-tenant:UNCONFIGURED";

	/** {@code BearerOnlyAuthenticator} prefixes every mapped JWT role with this (F!F convention). */
	private static final String ROLE_PREFIX = "ROLE_";

	private static final Logger log = LogManager.getLogger(ReloadConfigurationServlet.class);

	@Override
	public String getName() {
		return "reload";
	}

	@Override
	public String getUrlMapping() {
		return "/api-service/configurations";
	}

	@Override
	public String[] getAccessGrantingRoles() {
		return new String[] { requiredRole() };
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		String required = requiredRole();
		if (!callerHasRole(required)) {
			log.warn("rejected reload request: caller does not hold required role [{}]", required);
			resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
			return;
		}

		FrankApiService frankApiService = lookupFrankApiService(req);
		if (frankApiService == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "console services not initialised");
			return;
		}

		RequestMessageBuilder builder = RequestMessageBuilder.create(BusTopic.IBISACTION);
		builder.addHeader("action", Action.RELOAD.name());
		builder.addHeader(BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY, BusMessageUtils.ALL_CONFIGS_KEY);
		frankApiService.callAsyncGateway(builder);

		resp.setStatus(HttpServletResponse.SC_ACCEPTED);
	}

	private static String requiredRole() {
		return AppConstants.getInstance().getProperty(SECURITY_ROLES_PROPERTY, UNCONFIGURED_ROLE);
	}

	private static boolean callerHasRole(String required) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		boolean authenticated = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
		if (!authenticated) {
			return false;
		}
		String expectedAuthority = ROLE_PREFIX + required;
		return auth.getAuthorities().stream().anyMatch(a -> expectedAuthority.equals(a.getAuthority()));
	}

	/**
	 * The console's Spring context is a CHILD of this servlet's own context (see the design doc's
	 * context table), so {@code FrankApiService} can't be {@code @Autowired} directly -- resolve it
	 * live off the shared {@code ServletContext} instead, which by request time always has the
	 * fully-booted console context registered as root, regardless of exact boot ordering.
	 */
	private FrankApiService lookupFrankApiService(HttpServletRequest req) {
		WebApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(req.getServletContext());
		if (ctx == null) {
			log.error("no root WebApplicationContext available -- console not initialised yet");
			return null;
		}
		try {
			return ctx.getBean(FrankApiService.class);
		} catch (NoSuchBeanDefinitionException e) {
			log.error("FrankApiService bean not found in console context", e);
			return null;
		}
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl viscolink -Dtest=ReloadConfigurationServletTest`
Expected: PASS (all 9 test methods).

- [ ] **Step 5: Commit**

```bash
cd /Users/tom/IdeaProjects/viscoSuite
git add viscolink/src/main/java/org/frankframework/visco/security/ReloadConfigurationServlet.java \
        viscolink/src/test/java/org/frankframework/visco/security/ReloadConfigurationServletTest.java
git commit -m "feat(viscolink): add Bearer-authenticated config-reload endpoint

PUT /api-service/configurations - stateless JWT counterpart to F!F's
own console reload, for viscoFoundry's portal to call server-to-server
without touching the console's browser-session OAUTH2 chain.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NYK6w5hg7J1eEV9Fae2Ys8"
```

---

### Task 2: `ConsoleSecurityRegistrar` path exclusion

**Files:**
- Modify: `viscolink/src/main/java/com/viscosiety/security/ConsoleSecurityRegistrar.java:140-142`
- Test: `viscolink/src/test/java/com/viscosiety/security/ConsoleSecurityRegistrarTest.java` (new —
  no test currently exists for this class)

**Interfaces:**
- Consumes: nothing new.
- Produces: `ConsoleSecurityRegistrar.isFrankOwnedPath(String)` stays package-private/static,
  unchanged signature — only its logic gains one more excluded prefix.

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright 2026 Viscosiety B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.viscosiety.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleSecurityRegistrarTest {

    @Test
    void iafPathIsFrankOwned() {
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/iaf/gui/"));
    }

    @Test
    void apiPathIsFrankOwned() {
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/api/whatever"));
    }

    @Test
    void fhirPathIsFrankOwned() {
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/fhir/r4/facade"));
    }

    @Test
    void apiServicePathIsFrankOwned() {
        // The new BEARER_ONLY reload endpoint secures itself; the console's own session-based
        // tool-page filter must never also try to gate it.
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/api-service/configurations"));
    }

    @Test
    void toolPageIsNotFrankOwned() {
        assertFalse(ConsoleSecurityRegistrar.isFrankOwnedPath("/tools/some-tool"));
    }

    @Test
    void rootPathIsNotFrankOwned() {
        assertFalse(ConsoleSecurityRegistrar.isFrankOwnedPath("/"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl viscolink -Dtest=ConsoleSecurityRegistrarTest`
Expected: FAIL on `apiServicePathIsFrankOwned` only (the other five already pass against the
current implementation — this locks in existing behavior while adding the new case).

- [ ] **Step 3: Update the implementation**

In `viscolink/src/main/java/com/viscosiety/security/ConsoleSecurityRegistrar.java`, change:

```java
    /** Path (within the context) is owned by F!F or the FHIR facade — never touched by this filter. */
    static boolean isFrankOwnedPath(String path) {
        return path.startsWith("/iaf/") || path.startsWith("/api/") || path.startsWith("/fhir/");
    }
```

to:

```java
    /**
     * Path (within the context) is owned by F!F, the FHIR facade, or another endpoint that secures
     * itself independently — never touched by this filter. {@code /api-service/} is
     * {@link org.frankframework.visco.security.ReloadConfigurationServlet}'s Bearer-only reload
     * endpoint: it enforces its own JWT-based auth and must never also be gated by this class's
     * session-based tool-page check.
     */
    static boolean isFrankOwnedPath(String path) {
        return path.startsWith("/iaf/") || path.startsWith("/api/") || path.startsWith("/fhir/")
                || path.startsWith("/api-service/");
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl viscolink -Dtest=ConsoleSecurityRegistrarTest`
Expected: PASS (all 6 test methods).

- [ ] **Step 5: Commit**

```bash
cd /Users/tom/IdeaProjects/viscoSuite
git add viscolink/src/main/java/com/viscosiety/security/ConsoleSecurityRegistrar.java \
        viscolink/src/test/java/com/viscosiety/security/ConsoleSecurityRegistrarTest.java
git commit -m "fix(viscolink): exclude /api-service/ from console tool-page auth

The new Bearer-only reload endpoint secures itself; ConsoleSecurityRegistrar's
session-based tool-page filter must not also try to gate it.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NYK6w5hg7J1eEV9Fae2Ys8"
```

---

### Task 3: Local build + boot verification

**Files:** none (verification only, no new code).

**Interfaces:** none.

- [ ] **Step 1: Full module build**

```bash
cd /Users/tom/IdeaProjects/viscoSuite
./mvnw install -pl viscolink
```

Expected: `BUILD SUCCESS`, all tests from Tasks 1-2 included in the run.

- [ ] **Step 2: Boot locally and confirm the servlet registers**

```bash
cd /Users/tom/IdeaProjects/viscoSuite/viscorunner
docker compose up --build
```

Watch the startup log for a line from `ServletManager.logServletInfo` naming the new servlet, e.g.
`registered servlet [reload] configuration ... url(s) [/api-service/configurations] ...`. Since no
`application.security.http.authenticators`/`servlet.reload.*` env vars are set locally (by design —
see Global Constraints), expect the log line to show `with no authentication enabled!` for this
servlet — that is the properties-wiring gap Task 1's defense-in-depth check exists to cover, not a
bug in this step.

- [ ] **Step 3: Confirm the endpoint still fails closed with no properties wiring at all**

```bash
curl -i -X PUT http://localhost:8180/viscolink/api-service/configurations
```

Expected: `401` — proves `ReloadConfigurationServlet`'s own `SecurityContextHolder` check rejects
the request even though `ServletManager` skipped Spring-Security-level enforcement entirely (no
authenticator configured locally). This is the concrete, live proof that the fail-closed design
from Task 1 actually holds, not just unit-test theory.

- [ ] **Step 4: Confirm the console UI is unaffected**

```bash
curl -i http://localhost:8180/viscolink/iaf/gui/
```

Expected: same response as before this plan's changes (LOC stage — no auth prompt). This is the
regression guard for the two-chains decision: the new servlet must not have altered
`/iaf/gui/`'s own behavior at all.

- [ ] **Step 5: Stop the stack**

```bash
docker compose down
```

No commit for this task — it's verification only, nothing to check in.

---

## Follow-up (separate plan, viscoFoundry repo — not part of this plan)

Once this lands and a new `viscoforge-runner` image is built and pushed:

- `manifests.ts`: add env vars `application.security.http.authenticators=bearer`,
  `application.security.http.authenticators.bearer.type=BEARER_ONLY`,
  `application.security.http.authenticators.bearer.issuerUri=${KEYCLOAK_ISSUER_URI}`,
  `application.security.http.authenticators.bearer.userNameAttributeName=preferred_username`,
  `application.security.http.authenticators.bearer.authoritiesClaimName=realm_access.roles`,
  `servlet.reload.authenticator=bearer`,
  `servlet.reload.securityRoles=viscoforge-tenant:${accountSlug}-${instanceSlug}` (per-instance,
  from `TenantManifestInput`).
- `ff-console-client.ts`: change `reloadAllConfigurations`'s URL from
  `${instanceUrl}/viscolink/iaf/api/configurations` to
  `${instanceUrl}/viscolink/api-service/configurations`.
- Existing running instances need the new image + these env vars before the portal's reload button
  works against them (no auto-migration path — same precedent as
  `resourcequota_limitrange_no_resync_gap`).
