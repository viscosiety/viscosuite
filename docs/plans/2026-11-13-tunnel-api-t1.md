# Tunnel API, T1: REST endpoints on a tunnel port (ViscoSuite v1.2.0) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A runtime that is given a certificate serves its REST endpoints over HTTPS on extra "tunnel ports", with its own login, and can declare endpoints that answer on a tunnel port only. A runtime without that setting behaves exactly as today.

**Architecture:** A Tomcat lifecycle listener in the shared jar reads one settings file at start and adds one TLS connector per tunnel port. Rewrite rules route by arrival port: on a tunnel port `/api/**` goes to a second servlet, everything else is 404. That servlet, `TunnelApiServlet`, is a subclass of Frank!Framework's `ApiListenerServlet` mapped at `/tunnel-api/*` with its own `servlet.tunnelApi.*` settings; it dispatches to the same listener table, without a forward. `TunnelApiListener` is an `ApiListener` subclass; a filter on the public `/api/*` answers 404 for it.

**Tech Stack:** Java 21, Maven (`./mvnw`), Tomcat 11.0.x, Frank!Framework 10.3.0 (Spring Security 7), JUnit 5 (surefire unit tests, failsafe ITs with `ViscolinkLauncher`), Docker for the image smoke.

**Design:** viscoFoundry `docs/superpowers/specs/2026-11-12-tunnel-api-design.md` (decisions A1–A10, section 4.1). **Spike:** `docs/design/2026-11-12-tunnel-api-spike.md` in this repository; read it before Task 1.

**Branch:** `feat/tunnel-api`, cut from `spike/tunnel-api` (`eb4d700`), in the worktree `/Users/tom/IdeaProjects/viscoSuite-wt-tunnel-api`. Every command runs from that directory.

**Not in this plan:** anything in viscoFoundry (schema, render, dialogs: T2), the assistant and The Way (T3), visco-tunnels (no change).

## Global Constraints

- Nothing changes for a runtime without the settings file: no connector, no listening port, the same answers on 8080. Every task keeps the existing unit tests and ITs green unchanged, except where a step names the change.
- Settings file: `/opt/frank/tunnel-api/connectors.properties`; the path can be overridden with the system property `visco.tunnel.api.connectors` (tests). Keys, exactly: `ports` (comma-separated), then per port `<port>.keystoreFile`, `<port>.keystorePasswordFile`, `<port>.keystoreType` (optional, `PKCS12` default, `JKS` allowed) **or** `<port>.certificateFile`, `<port>.certificateKeyFile`, `<port>.certificateChainFile` (optional). A password is never a value in this file and never an environment variable: it is the content of the password file (trailing line break removed).
- A tunnel port is 1024–65535 and not 8005, 8080 or 8009. A bad entry (bad number, missing or unreadable file, keystore that does not open with the password, keystore without a private key) is logged once as `tunnel-api: port <port> not opened: <reason>` and skipped; the other ports and the runtime still start. A reason never contains a password or file content.
- The listener publishes the ports it opened as the system property `visco.tunnel.api.ports` (comma-separated, absent when none). Web application code decides "did this request arrive on a tunnel port" with `request.getLocalPort()` against that list, never with `getServerPort()` (that one comes from the `Host` header).
- Servlet name `tunnelApi`, mapping `/tunnel-api/*`, settings `servlet.tunnelApi.authenticator`, `servlet.tunnelApi.securityRoles`, `servlet.tunnelApi.transportGuarantee` (left at Frank!Framework's default). The servlet answers 404 with an empty body to any request that did not arrive on a tunnel port, whatever the caller's login.
- Listener class name, exactly: `com.viscosiety.tunnel.TunnelApiListener` (core, both packs). It adds no attribute to `ApiListener`.
- On the public `/api/*`, a request whose listener is a `TunnelApiListener` gets 404 with an empty body, the same answer as for a path no listener has. The filter is mapped for the `REQUEST` dispatch only: `AgentApiServlet`'s forward (the platform's own gateway) still reaches tunnel-only endpoints.
- Anything added to the shared jar (`viscorunner/src/main/java`) uses only Tomcat and JDK classes, or `SharedJarIsolationIT` fails.
- `rewrite.config` has no comment lines (a comment silently disables conditional rules). Explain rules in the Dockerfile comment instead.
- New public texts (log lines, CHANGELOG, docs): plain language, say "tunnel port", never "connector" for the feature (the Tomcat class name `Connector` in code is fine).
- No `git stash`. Commit after each task with a conventional-commit subject and the trailer `Co-Authored-By: <the model doing the work> <noreply@anthropic.com>`. Do not push; do not tag.

## File structure

| File | Responsibility |
|---|---|
| `viscorunner/src/main/java/com/viscosiety/viscorunner/tunnel/TunnelPortSettings.java` | Parses and validates the settings file (pure, no Tomcat types) |
| `viscorunner/src/main/java/com/viscosiety/viscorunner/tunnel/TunnelPortListener.java` | Server lifecycle listener: one TLS connector per valid port, publishes `visco.tunnel.api.ports` |
| `viscorunner/src/test/java/com/viscosiety/viscorunner/tunnel/TunnelPortSettingsTest.java` | Parser and validation cases |
| `viscorunner/src/test/java/com/viscosiety/viscorunner/tunnel/TunnelPortRoutingTest.java` | Real embedded Tomcat: connector, rewrite rules, both ports (replaces the spike tests) |
| `viscorunner/src/test/java/com/viscosiety/viscorunner/tunnel/TestCertificates.java` | `keytool` helper for a PKCS12 file and a PEM pair, HTTPS client that trusts it |
| `viscolink/src/main/java/com/viscosiety/tunnel/TunnelApiListener.java` | The tunnel-only listener type |
| `viscolink/src/main/java/com/viscosiety/tunnel/TunnelPorts.java` | Reads `visco.tunnel.api.ports`; `isTunnelPort(int)` |
| `viscolink/src/main/java/org/frankframework/visco/security/TunnelApiServlet.java` | The tunnel-side servlet |
| `viscolink/src/main/java/com/viscosiety/tunnel/TunnelOnlyApiFilter.java` | Hides tunnel-only endpoints on the public `/api/*`, including the OpenAPI document |
| `viscolink/src/main/java/com/viscosiety/tunnel/TunnelOnlyApiFilterRegistrar.java` | Registers the filter once (survives a configuration reload) |
| `viscolink/src/main/resources/springConsoleSecurity.xml` | One more bean: the registrar |
| `viscolink/src/test/java/com/viscosiety/tunnel/*Test.java` | Unit tests of `TunnelPorts` and the filter's decision |
| `viscorunner/src/test/java/com/viscosiety/viscorunner/it/TunnelApiIT.java` | Real Frank!Framework: both sides, both logins, public side Off |
| `viscorunner/src/test/resources/tunnel-api/tunnelapi/Configuration.xml` | Test configuration: one `ApiListener`, one `TunnelApiListener`, one mixed pattern |
| `viscorunner/src/test/java/com/viscosiety/viscorunner/it/ViscolinkLauncher.java` | Modified: optional tunnel listener, `dtap.stage` not overwritten |
| `viscorunner/conf/server.xml`, `viscorunner/conf/Catalina/localhost/rewrite.config`, `viscorunner/Dockerfile` | Listener line, six rules in front, comment |
| `scripts/ci/smoke-image.sh` | One more check: a tunnel port answers over HTTPS |
| `viscolink/src/main/java/com/viscosiety/pack/CoreFeatures.java` | Feature name `tunnel-api` |
| `CHANGELOG.md`, `CLAUDE.md`, `docs/tunnel-api.md` | What it is, the settings, what is not covered |

Deleted at the end of Task 4: `viscorunner/src/test/java/com/viscosiety/viscorunner/spike/` (its cases live on in `TunnelPortRoutingTest`).

---

### Task 1: Settings file and the tunnel port listener

**Files:** create `TunnelPortSettings.java`, `TunnelPortListener.java`, `TunnelPortSettingsTest.java`, `TestCertificates.java`.

**Interfaces — produces:**

```java
package com.viscosiety.viscorunner.tunnel;

public final class TunnelPortSettings {
    public static final String FILE_PROPERTY = "visco.tunnel.api.connectors";
    public static final String DEFAULT_FILE = "/opt/frank/tunnel-api/connectors.properties";

    /** One usable tunnel port. Exactly one of the two forms is filled. */
    public record Port(int port, String keystoreFile, String keystorePassword, String keystoreType,
                       String certificateFile, String certificateKeyFile, String certificateChainFile) {}
    /** A port that was asked for and cannot be opened; reason is safe to log. */
    public record Refused(String port, String reason) {}
    public record Result(java.util.List<Port> ports, java.util.List<Refused> refused) {}

    /** Absent file: empty result. Never throws. */
    public static Result read(java.nio.file.Path file);
}

public class TunnelPortListener implements org.apache.catalina.LifecycleListener {
    public static final String PORTS_PROPERTY = "visco.tunnel.api.ports";
    public TunnelPortListener();                       // reads FILE_PROPERTY, else DEFAULT_FILE
    public java.util.List<org.apache.catalina.connector.Connector> opened();
}
```

- [ ] **Step 1: `TestCertificates`.** Move `generateKeystore` and the PEM export from `spike/TunnelConnectorSpikeTest.java` into `TestCertificates` (static methods `pkcs12(Path dir, String password)` returning the file, `pemPair(Path dir)` returning `Path[]{certificate, key}`, and `client(Path pkcs12, String password)` returning a `java.net.http.HttpClient` whose trust store is that keystore). Certificate subject and SAN `localhost`. Keep the spike test compiling by calling the helper from it.

- [ ] **Step 2: write `TunnelPortSettingsTest` (failing).** Cases, one test each, files in a `@TempDir`:

| Case | Expect |
|---|---|
| file absent | `ports` empty, `refused` empty |
| `ports=8443` + PKCS12 file + password file (content `secret\n`) | one `Port`, `keystorePassword` = `secret`, type `PKCS12` |
| `ports=8443` + PEM pair | one `Port`, `certificateFile` and `certificateKeyFile` set, keystore fields null |
| `ports=8443,9443`, 9443's keystore file missing | 8443 in `ports`; `refused` = `("9443", "the keystore file does not exist")` |
| wrong password | refused, reason `the keystore does not open with this password` |
| keystore with a certificate but no private key | refused, reason `the keystore holds no private key` |
| `ports=8080`, `ports=80`, `ports=70000`, `ports=abc` | each refused, reason `not a usable port number (1024-65535, not 8005, 8009 or 8080)` |
| `ports=8443,8443` | one `Port`, one refused with reason `listed twice` |
| both forms given for one port | refused, reason `give a keystore or a certificate and key, not both` |
| neither form given | refused, reason `no keystore and no certificate given` |
| PEM pair, key file missing | refused, reason `the key file does not exist` |
| file exists but is a directory / unreadable | `ports` empty, one refused with port `*` and reason `the settings file cannot be read` |
| every reason, every case | contains neither the password nor a path's content (assert the reason equals the fixed text) |

Run: `./mvnw -q test -pl viscorunner -Dtest=TunnelPortSettingsTest` — expected: compilation failure (class missing).

- [ ] **Step 3: implement `TunnelPortSettings`.** `java.util.Properties` load; split `ports` on commas, trim, skip empty; validate as in the table; for a keystore load it with `KeyStore.getInstance(type)` and `load(stream, password)` (an `IOException` whose cause is `UnrecoverableKeyException`, or any `IOException` from `load`, is "does not open with this password"; `KeyStoreException`/`NoSuchAlgorithmException`/`CertificateException` is `the keystore cannot be read`), then require at least one alias with `isKeyEntry`. For a PEM pair check only that the files exist and are readable (Tomcat reports a bad PEM itself). Catch everything per port: one bad port never throws out of `read`.

- [ ] **Step 4: run the test** — expected: all pass.

- [ ] **Step 5: implement `TunnelPortListener`** from `spike/TunnelConnectorSpikeListener.java`, with these changes: the no-argument constructor reads the file named by `FILE_PROPERTY` or `DEFAULT_FILE`; act on `Lifecycle.BEFORE_INIT_EVENT` of a `Server` only; log each `Refused` with `java.util.logging` at SEVERE as `tunnel-api: port <port> not opened: <reason>`; for each `Port` build the connector as in the spike (`Http11NioProtocol`, `setScheme("https")`, `setSecure(true)`, `SSLEnabled=true`, one `SSLHostConfig` with one `SSLHostConfigCertificate`; keystore form: `setCertificateKeystoreFile/Password/Type`; PEM form: `setCertificateFile`, `setCertificateKeyFile`, and `setCertificateChainFile` when given), add it to `server.findServices()[0]`; after the loop set `System.setProperty(PORTS_PROPERTY, "8443,9443")` when at least one was added, else `System.clearProperty(PORTS_PROPERTY)`; log at INFO `tunnel-api: HTTPS on port(s) 8443, 9443` (ports only).

- [ ] **Step 6: commit.** `feat(tunnel-api): settings file and the listener that opens HTTPS tunnel ports`

---

### Task 2: Tunnel servlet and listener type, proven on a real Frank!Framework (go / no-go)

This task proves the design's largest open point: a subclass of `ApiListenerServlet` under its own settings. **If Step 6 cannot be made to pass with the servlet as a subclass, stop and report BLOCKED with what Frank!Framework did (do not switch to a forward: the spike showed why that fails).**

**Files:** create `TunnelApiListener.java`, `TunnelPorts.java`, `TunnelApiServlet.java`, `TunnelPortsTest.java`, `TunnelApiIT.java`, `viscorunner/src/test/resources/tunnel-api/tunnelapi/Configuration.xml`; modify `ViscolinkLauncher.java`.

**Interfaces — consumes:** `TunnelPortListener`, `TunnelPortListener.PORTS_PROPERTY` (the string `visco.tunnel.api.ports`; `viscolink` does not depend on `viscorunner`, so `TunnelPorts` repeats the literal with a comment naming the other class), `TestCertificates`.

**Produces:** `com.viscosiety.tunnel.TunnelPorts.isTunnelPort(int)`, `com.viscosiety.tunnel.TunnelApiListener`, servlet `tunnelApi` at `/tunnel-api/*`.

- [ ] **Step 1: `TunnelPorts` with its test.**

```java
package com.viscosiety.tunnel;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** The HTTPS tunnel ports this runtime opened at start (TunnelPortListener in the shared jar publishes them). */
public final class TunnelPorts {
    /** Same literal as com.viscosiety.viscorunner.tunnel.TunnelPortListener.PORTS_PROPERTY. */
    static final String PORTS_PROPERTY = "visco.tunnel.api.ports";

    private TunnelPorts() {
    }

    /** Read on every call: the property is set once at Tomcat start, before any request. */
    public static boolean isTunnelPort(int localPort) {
        return ports().contains(localPort);
    }

    static Set<Integer> ports() {
        String value = System.getProperty(PORTS_PROPERTY, "");
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> part.matches("\\d{1,5}"))
                .map(Integer::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}
```

`TunnelPortsTest`: property absent → false for 8443; `8443,9443` → true for both, false for 8080; `8443, x,,` → true for 8443 only. Restore the property in `@AfterEach`.

- [ ] **Step 2: `TunnelApiListener`.**

```java
package com.viscosiety.tunnel;

import org.frankframework.http.rest.ApiListener;

/**
 * An {@link ApiListener} that answers on an HTTPS tunnel port only: a system at a customer's site reaches it
 * through the account's private tunnel, and the public address answers 404 for it. Same attributes as
 * ApiListener. Use it as {@code <Listener className="com.viscosiety.tunnel.TunnelApiListener" .../>}.
 * The same uriPattern and method cannot be declared on an ApiListener as well.
 */
public class TunnelApiListener extends ApiListener {
}
```

- [ ] **Step 3: `TunnelApiServlet`.**

```java
package org.frankframework.visco.security;

import java.io.IOException;
import java.io.Serial;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frankframework.http.rest.ApiListenerServlet;
import org.frankframework.lifecycle.IbisInitializer;

import com.viscosiety.tunnel.TunnelPorts;

/**
 * The REST endpoints on the tunnel side: the same listeners as {@code /api/*}, served at
 * {@code /tunnel-api/*} under their own login ({@code servlet.tunnelApi.authenticator},
 * {@code servlet.tunnelApi.securityRoles}). Tomcat's rewrite rules send {@code /api/**} on a tunnel port
 * here; this servlet answers 404 to anything that did not arrive on a tunnel port, so the public port can
 * never reach a tunnel-only endpoint through it. It dispatches itself: no forward to {@code /api}, whose
 * security chain belongs to the public side.
 */
@IbisInitializer
public class TunnelApiServlet extends ApiListenerServlet {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
        return "tunnelApi";
    }

    @Override
    public String getUrlMapping() {
        return "/tunnel-api/*";
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!TunnelPorts.isTunnelPort(request.getLocalPort())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        super.service(request, response);
    }

    /** The parent clears the listener table it shares with the public servlet; that one clears it at shutdown. */
    @Override
    public void destroy() {
        // nothing to release: this servlet owns no state of its own
    }
}
```

- [ ] **Step 4: test configuration.** `viscorunner/src/test/resources/tunnel-api/tunnelapi/Configuration.xml`: configuration `tunnelapi` with three adapters, each one `EchoPipe`-style pipeline that returns a fixed text (`open`, `feed`, `mixed-get` / `mixed-post`): `<ApiListener name="open" uriPattern="open" method="GET"/>`; `<Listener className="com.viscosiety.tunnel.TunnelApiListener" name="feed" uriPattern="feed" method="POST"/>`; pattern `mixed` with an `ApiListener` on GET and a `TunnelApiListener` on POST. Copy the receiver/pipeline skeleton from an existing test or demo configuration that uses an `ApiListener` (`grep -rl "<ApiListener" viscorunner packs`). If the configuration fails to load because the generic `<Listener className=...>` form is refused, report it: that is design point 6.3.

- [ ] **Step 5: `ViscolinkLauncher`.** Two changes: (a) `System.setProperty("dtap.stage", "LOC")` becomes "only when not already set"; (b) before `tomcat.start()`: `if (System.getProperty("visco.tunnel.api.connectors") != null) { tomcat.getServer().addLifecycleListener(new com.viscosiety.viscorunner.tunnel.TunnelPortListener()); }`. If the launcher's class path (see how `LabEnrichmentIT` builds `classpath`) lacks `viscorunner/target/classes`, add it there.

- [ ] **Step 6: `TunnelApiIT` (the proof).** Model the process start on `LabEnrichmentIT` (no ViscoStore: pass `http://localhost:1/fhir/` and a temp-file H2 URL; configuration directory `src/test/resources/tunnel-api`, name `tunnelapi`). JVM arguments, with `<tunnel>` a free port, a settings file for it written to a temp dir, and two generated passwords:

```
-Dvisco.tunnel.api.connectors=<settings file>
-Dapplication.security.http.authentication=true
-Dapplication.security.http.transportGuarantee=NONE
-Dservlet.tunnelApi.transportGuarantee=CONFIDENTIAL
-Dapplication.security.http.authenticators.publicBasic.type=IN_MEMORY
-Dapplication.security.http.authenticators.publicBasic.username=public-user
-Dapplication.security.http.authenticators.publicBasic.password=<p1>
-Dapplication.security.http.authenticators.tunnelBasic.type=IN_MEMORY
-Dapplication.security.http.authenticators.tunnelBasic.username=site-user
-Dapplication.security.http.authenticators.tunnelBasic.password=<p2>
-Dservlet.ApiListenerServlet.authenticator=publicBasic
-Dservlet.ApiListenerServlet.securityRoles=IbisTester
-Dservlet.tunnelApi.authenticator=tunnelBasic
-Dservlet.tunnelApi.securityRoles=IbisTester
```

Wait for readiness by polling the tunnel port until `GET https://localhost:<tunnel>/viscolink/tunnel-api/open` with `site-user` answers 200 (at most 3 minutes; print the process output on timeout). The launcher has no rewrite rules, so this IT calls `/viscolink/tunnel-api/...` on the tunnel port directly; the rewrite is Task 4's.

Tests (one each; `T` = tunnel port over HTTPS, `P` = the launcher's own port over HTTP):

| # | Call | Expect |
|---|---|---|
| 1 | `T` POST `/viscolink/tunnel-api/feed`, `site-user` | 200, body = the `feed` adapter's text |
| 2 | `T` GET `/viscolink/tunnel-api/open`, `site-user` | 200 (a plain `ApiListener` is served on the tunnel side too) |
| 3 | `T` GET `/viscolink/tunnel-api/open`, no login | 401 |
| 4 | `T` GET `/viscolink/tunnel-api/open`, `public-user` + `<p1>` | 401 (the two sides have separate logins) |
| 5 | `P` GET `/viscolink/api/open`, `public-user` | 200 |
| 6 | `P` GET `/viscolink/api/open`, `site-user` + `<p2>` | 401 |
| 7 | `P` GET `/viscolink/tunnel-api/open`, `site-user` + `<p2>` | not 200 (404 from the servlet's port check, or a redirect/refusal from `CONFIDENTIAL`; assert `!= 200` and body does not contain the adapter's text) |
| 8 | `T` GET `/viscolink/tunnel-api/nothing-here`, `site-user` | 404 |
| 9 | `T` POST `/viscolink/tunnel-api/mixed` and GET `/viscolink/tunnel-api/mixed`, `site-user` | 200 both |

Run: `./mvnw -q verify -pl viscolink,viscorunner -am -Dit.test=TunnelApiIT -DfailIfNoSpecifiedTests=false`. Expected: all nine pass. If the context fails to start, read the launcher output first; known suspects: two beans of type `ApiListenerServlet` (something autowiring it by type), the `IN_MEMORY` authenticator's property names, authentication off on stage `LOC` (test 3 shows it).

- [ ] **Step 7: full module tests** `./mvnw -q verify -pl viscolink,viscorunner -am` — expected: green, including `SharedJarIsolationIT` and `HealthClasspathIT`.

- [ ] **Step 8: commit.** `feat(tunnel-api): tunnel-side servlet and the TunnelApiListener type`

---

### Task 3: Tunnel-only endpoints are hidden on the public side

**Files:** create `TunnelOnlyApiFilter.java`, `TunnelOnlyApiFilterRegistrar.java`, `TunnelOnlyApiFilterTest.java`; modify `springConsoleSecurity.xml`, `TunnelApiIT.java`.

**Decision rule (pure, unit-tested), for a request to `/api/*` with path info `uri` (one trailing `/` removed) and method `m`:**

1. `uri` null or empty → pass.
2. `uri` equals `/openapi.json` (any case), no `uri` query parameter → answer the document for the patterns that have **no** `TunnelApiListener` at all: `OpenApiGenerator.generateOpenApiJsonSchema(publicConfigs, endpoint)` (`org.frankframework.http.openapi.OpenApiGenerator`, public static; `endpoint` computed as `ApiListenerServlet.createEndpointUrlFromRequest` does: decoded request URL up to the path info), written as pretty-printed JSON with `Content-Type: application/json`, status 200. When no tunnel listener is registered at all → pass (Frank!Framework answers as today).
3. `uri` equals `/openapi.json` with a `uri` parameter, or `uri` ends with `openapi.json` (the old per-pattern form: strip the last segment): look the pattern up with `findExactMatchingConfigForUri`; when it has any `TunnelApiListener` → 404; else pass.
4. Otherwise `config = findConfigForRequest(m, uri)` (when `m` is not an `ApiListener.HttpMethod` → pass): no config → pass; the listener for `m` is a `TunnelApiListener` → 404; no listener for `m` and every listener of the config is a `TunnelApiListener` → 404; else pass.

404 means `response.setStatus(404)` with no body, and the chain is not called.

- [ ] **Step 1: write `TunnelOnlyApiFilterTest` (failing).** Register listeners in the real `ApiServiceDispatcher.getInstance()` (plain `new ApiListener()` / `new TunnelApiListener()` with `setUriPattern`, `setMethod`, then `ApiServiceDispatcher.getInstance().registerServiceClient(listener)`; `clear()` in `@AfterEach`) and drive a static `decide(String method, String pathInfo, String uriParameter)` returning an enum `PASS | NOT_FOUND | PUBLIC_OPENAPI`. Cases: `GET /open` PASS; `POST /feed` NOT_FOUND; `GET /feed` NOT_FOUND (pattern is tunnel-only); `POST /feed/` NOT_FOUND; `GET /mixed` PASS; `POST /mixed` NOT_FOUND; `PUT /mixed` PASS; `GET /nothing` PASS; `BREW /feed` PASS; `GET /openapi.json` with a tunnel listener registered → PUBLIC_OPENAPI, without one → PASS; `GET /OpenApi.JSON` same; `GET /openapi.json?uri=/feed` NOT_FOUND, `?uri=/open` PASS; `GET /feed/openapi.json` NOT_FOUND; `GET /open/openapi.json` PASS; null path info PASS. One more test for the document: with `open`, `feed` and `mixed` registered, the public document's `paths` holds `/open` and neither `/feed` nor `/mixed`.

- [ ] **Step 2: implement the filter** (`jakarta.servlet.Filter`; `doFilter` casts to the HTTP types, calls `decide`, acts). Implement `decide` exactly by the rule above. Run the test: pass.

- [ ] **Step 3: the registrar.** Copy the shape of `com.viscosiety.security.ApiSessionAuthRegistrar` (`InitializingBean`, `ApplicationContextAware`, `findServletContext`, never fails the context refresh). In `afterPropertiesSet`: when `servletContext.getFilterRegistration("tunnelOnlyApi") != null` → log "already registered (configuration reload)" and return; else `FilterRegistration.Dynamic registration = servletContext.addFilter("tunnelOnlyApi", new TunnelOnlyApiFilter()); registration.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/api/*");` inside a `try` that logs an `IllegalStateException` at ERROR as `tunnel-api: the public-side filter could not be registered; tunnel-only endpoints are NOT hidden on /api` (this must be loud: it is the security boundary). Add the bean to `springConsoleSecurity.xml` after `apiSessionAuthRegistrar`, with a two-line comment saying what it does.

- [ ] **Step 4: extend `TunnelApiIT`.**

| # | Call | Expect |
|---|---|---|
| 10 | `P` POST `/viscolink/api/feed`, `public-user` | 404, empty body |
| 11 | `P` POST `/viscolink/api/feed`, no login | 401 (same as for any path: no hint that the endpoint exists) |
| 12 | `P` POST `/viscolink/api/nothing-here`, no login | 401 |
| 13 | `P` POST `/viscolink/api/mixed`, `public-user` | 404; GET → 200 |
| 14 | `P` GET `/viscolink/api/openapi.json`, `public-user` | 200; `paths` has `/open`, not `/feed`, not `/mixed` |
| 15 | `T` GET `/viscolink/tunnel-api/openapi.json`, `site-user` | 200; `paths` has `/open`, `/feed`, `/mixed` |
| 16 | `P` POST `/viscolink/api/%66eed` and `/viscolink/api/feed/`, `public-user` | 404 both |
| 17 | `P` POST `/viscolink/api/x/../feed`, `public-user` | not 200 |

Run the IT: all pass.

- [ ] **Step 5: commit.** `feat(tunnel-api): tunnel-only endpoints answer 404 on the public side`

---

### Task 4: Routing by arrival port in the image, spike removed

**Files:** modify `viscorunner/conf/Catalina/localhost/rewrite.config`, `viscorunner/conf/server.xml`, `viscorunner/Dockerfile` (comment only), `RuntimeSessionCookieScopeTest.java`; create `TunnelPortRoutingTest.java`; delete `viscorunner/src/test/java/com/viscosiety/viscorunner/spike/`.

- [ ] **Step 1: `TunnelPortRoutingTest` (failing).** Port the spike's `TunnelConnectorSpikeTest` to the production `TunnelPortListener` (settings file in a temp dir, `visco.tunnel.api.connectors` set and cleared around the class) and to the image's real `conf/Catalina/localhost/rewrite.config` (read from the source tree, as `RuntimeSessionCookieScopeTest` does). Stand-in servlets: `/runtime` context with an echo servlet at `/api/*`, one at `/tunnel-api/*`, one at `/iaf/*`; `ROOT` context empty. Remove the `@EnabledIfSystemProperty(named = "spike")` gate. Cases (`T` tunnel port, `P` plain port; the plain connector must listen on 8080 for the rules to apply, so run this class's Tomcat with the plain connector on port 8080 **only if free**, otherwise the test rewrites the rules file it loads by replacing the literal `8080` with its own plain port and says so in a comment):

| Call | Expect |
|---|---|
| `T` `/runtime/api/x`, `/api/x`, `/viscolink/api/x` (also as GET with `Accept: text/html`) | the `/tunnel-api` echo, path info `/x`, no redirect |
| `T` `/runtime/api/x?a=1&b=2` | query string arrives unchanged |
| `T` `/runtime/api` and `/runtime/api/` | the `/tunnel-api` echo |
| `T` `/runtime/iaf/gui/`, `/`, `/console/ui/`, `/fhir-store/fhir/metadata`, `/runtime/tunnel-apix`, `/runtime/apix/y` | 404 |
| `T` `/runtime/api/%2e%2e/iaf/x`, `/runtime/api/../iaf/x`, `/runtime//api/x`, `/RUNTIME/api/x` | never the `/iaf` echo (assert status 404 or the `/tunnel-api` echo, never the `/iaf` body) |
| `P` `/runtime/api/x` | the `/api` echo |
| `P` `/runtime/tunnel-api/x`, `/viscolink/tunnel-api/x`, `/runtime/tunnel-api` | 404 |
| `P` `/viscolink/api/x` | the `/api` echo (old path, in place) |
| `P` GET `/viscolink/iaf/gui/` with `Accept: text/html` | 302 to `/runtime/iaf/gui/` (unchanged) |
| PEM pair on a second tunnel port | HTTPS answers |
| no settings file | one connector; connecting to the tunnel port is refused |
| settings file with one bad and one good port | the good port answers; the runtime started |

- [ ] **Step 2: the rules.** Put these six lines at the very top of `rewrite.config`, no comments, no blank line between them and the existing rules:

```
RewriteCond %{SERVER_PORT} !^8080$
RewriteRule ^/(?:(?:runtime|viscolink)/)?api(/.*)?$ /runtime/tunnel-api$1 [L]
RewriteCond %{SERVER_PORT} !^8080$
RewriteRule ^/.*$ /tunnel-not-found [L]
RewriteCond %{SERVER_PORT} ^8080$
RewriteRule ^/(?:runtime|viscolink)/tunnel-api(/.*)?$ /tunnel-not-found [L]
```

(`%{SERVER_PORT}` is the arrival port in Tomcat's `RewriteValve`. A pattern of a bare `^` did not match in the spike; `^/.*$` does.) If a case of Step 1 fails on these patterns, change the patterns, not the cases. Explain the six rules in the Dockerfile comment above the `COPY ... rewrite.config` line (what they do, and that Tomcat's only other port is 8080).

- [ ] **Step 3: `server.xml`.** After the `ContextFailureEventPublisher` line: `<Listener className="com.viscosiety.viscorunner.tunnel.TunnelPortListener" />`, with a comment: opens HTTPS tunnel ports from `/opt/frank/tunnel-api/connectors.properties`; without that file it does nothing.

- [ ] **Step 4: the order dependence found by the spike.** Tomcat keeps the first embedded Tomcat's configuration source for the whole test JVM, so `RuntimeSessionCookieScopeTest` reads no `rewrite.config` when another embedded-Tomcat test ran first. Fix it in both test classes the same way: set the configuration source explicitly before `start()` (`org.apache.tomcat.util.file.ConfigFileLoader.setSource(new org.apache.catalina.startup.CatalinaBaseConfigurationSource(baseDir.toFile(), null))`) or, if that is not accepted twice in one JVM, give both classes `@Isolated` plus a surefire `reuseForks=false` for exactly these two classes; prove the fix by running `./mvnw -q test -pl viscorunner -Dtest='TunnelPortRoutingTest,RuntimeSessionCookieScopeTest'` and again with the two names swapped: green both times.

- [ ] **Step 5: delete the spike package**, and any `spike` property left in a pom. `docs/design/2026-11-12-tunnel-api-spike.md` stays; replace its first status line with: "Spike of 2026-11-12. Its tests became `TunnelPortRoutingTest` and `TunnelApiIT`; the spike classes are gone."

- [ ] **Step 6: run** `./mvnw -q verify -pl viscolink,viscorunner -am` — green.

- [ ] **Step 7: commit.** `feat(tunnel-api): route by arrival port; the image opens tunnel ports from its settings file`

---

### Task 5: Public side Off, and the checks that cross both sides

**Files:** modify `TunnelApiIT.java` (a second launcher run, as a nested class or a second IT class `TunnelApiPublicOffIT`).

No production code: "Off" is two settings the deployment renders. This task proves them.

- [ ] **Step 1: second run** with the JVM arguments of Task 2 except:

```
-Dapplication.security.http.authenticators.off.type=SEALED
-Dservlet.ApiListenerServlet.authenticator=off
-Dservlet.ApiListenerServlet.securityRoles=IbisTester
```

(no `publicBasic`). Tests: `P` GET `/viscolink/api/open` without login → 401; with `site-user` + `<p2>` → 401; with any made-up login → 401; `T` GET `/viscolink/tunnel-api/open`, `site-user` → 200; `T` POST `/viscolink/tunnel-api/feed`, `site-user` → 200.

- [ ] **Step 2: third run, tunnel side without login:** `-Dservlet.tunnelApi.authenticator=NONE`, no `servlet.tunnelApi.securityRoles`. Tests: `T` GET `/viscolink/tunnel-api/open` without login → 200; `T` POST `/viscolink/tunnel-api/feed` without login → 200; `P` GET `/viscolink/tunnel-api/open` without login → not 200.

- [ ] **Step 3: what this IT cannot prove.** `AgentApiServlet`'s forward to `/api` while the public side is Off needs a bearer token from an OIDC issuer; it was read in code only (`SealedAuthenticator` = HTTP Basic over an empty user store, so a request that already carries an authenticated context with the servlet's roles passes). Add this sentence to `docs/tunnel-api.md` under "Not covered by tests here" (Task 6); viscoFoundry's live proof (T3) covers it.

- [ ] **Step 4: commit.** `test(tunnel-api): public side Off and a tunnel side without login`

---

### Task 6: Feature name, image smoke, documentation

**Files:** modify `CoreFeatures.java` (+ its test), `scripts/ci/smoke-image.sh`, `CiScriptsTest.java` if it pins the script, `DockerfileContractTest.java`, `CHANGELOG.md`, `CLAUDE.md`; create `docs/tunnel-api.md`.

- [ ] **Step 1: `CoreFeatures.ALL`** becomes `List.of("lemma", "lifecycle-events", "tunnel-api")`; Javadoc line: "`tunnel-api`: HTTPS tunnel ports, the tunnel-side REST servlet and `TunnelApiListener` are present." Update the test that pins the list.

- [ ] **Step 2: image smoke.** In `smoke-image.sh`, before the runner starts: make a temp dir with a PKCS12 file from `keytool` (in a `eclipse-temurin:21-jre` container if the CI image has no `keytool`; check first), a password file and `connectors.properties` for port 8443; mount it read-only at `/opt/frank/tunnel-api`. After the existing checks: `docker exec smoke-runner curl --silent --insecure --output /dev/null --write-out '%{http_code}' https://localhost:8443/api/smoke-nothing` must print `404` or `401` (a TLS answer from the tunnel servlet), and the same for `https://localhost:8443/runtime/iaf/gui/` must print `404`. A connection error fails the smoke with the last log lines. `DockerfileContractTest`: assert `server.xml` names `TunnelPortListener` and that the first line of `rewrite.config` is `RewriteCond %{SERVER_PORT} !^8080$` and that no line of the file starts with `#`.

- [ ] **Step 3: `docs/tunnel-api.md`** (plain language; this is the contract viscoFoundry renders against). Sections: what it is (three sentences); the settings file (the keys of Global Constraints, one PKCS12 example, one PEM example, where the password comes from); what a bad entry does (log line, the runtime still starts); the servlet settings with three ready blocks — tunnel side with login (an `IN_MEMORY` or `YML` authenticator named for the tunnel, `servlet.tunnelApi.authenticator`, `servlet.tunnelApi.securityRoles`: **without the roles line the endpoints are open**), tunnel side without login (`NONE`), public side Off (`SEALED` + roles, and why not `servlet.ApiListenerServlet.enabled=false`); the listener type with the XML line; the rules: only `/api/**` answers on a tunnel port, one `uriPattern` + method on one listener type, a pattern with any tunnel-only method is left out of the public OpenAPI document, `AUTHROLE` listeners check the roles of the side the call came in on; what is not built (client certificates, a new certificate without a restart, plain HTTP on a tunnel port); "Not covered by tests here".

- [ ] **Step 4: `CHANGELOG.md`**, under `[Unreleased]`: one "In short" bullet (a runtime can serve its REST endpoints over HTTPS on an extra port with its own certificate and login, and endpoints can be limited to that port; nothing changes without the settings file) and an `### Added` entry naming the settings file, `servlet.tunnelApi.*`, `com.viscosiety.tunnel.TunnelApiListener`, the feature name `tunnel-api` and `docs/tunnel-api.md`. `CLAUDE.md`: one paragraph with the class names, the settings file path, the rule that `rewrite.config` routes by arrival port, and the rule "never use `getServerPort()` for this".

- [ ] **Step 5: run everything** `./mvnw -q verify` (all modules) — green. Build the image and run the smoke locally if Docker is available: `scripts/ci/smoke-image.sh <image>`; if not available, say so in the report.

- [ ] **Step 6: commit.** `docs(tunnel-api): settings contract, changelog, feature name; image smoke checks a tunnel port`

---

## After the tasks (Tom, not a subagent)

1. Final review of the whole branch; push; merge request on `public-applications/viscosuite`.
2. After the merge and a green main pipeline: release **v1.2.0** (decision A10). `[Unreleased]` in `CHANGELOG.md` becomes `[1.2.0]` (it also carries the MLLP resend fix), the tag `v1.2.0` goes on the merge commit (full SHA), the tag pipeline publishes the packages, the BOM and the images. Then poms to `1.3.0-SNAPSHOT`.
3. ViscoForge: `viscosuite.version` to `1.2.0`, a ViscoForge release, then the portal's allow-lists. Only then does T2 (viscoFoundry) render anything.

## Open points this plan does not close

- A new certificate without a restart (design 6.6): not built; a changed settings file is read at the next start.
- `AgentApiServlet` with the public side Off: code reading only (Task 5 Step 3).
- Whether the workspace's keystore upload yields a file Tomcat reads (PKCS12 or JKS with a private key): a viscoFoundry question (T2); this plan accepts both types and a PEM pair.
- `OPTIONS` and the `Allow` header on a mixed pattern can name a tunnel-only method on the public side. Accepted: it names a method, not data, and only to a caller who passed the public login.
