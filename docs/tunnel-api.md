# Tunnel API

## What it is

A runtime can serve its REST endpoints over HTTPS on an extra port, a **tunnel port**, with its own certificate and its own login. An endpoint can be limited to that port: it then answers on the tunnel port only, and the public address answers 404 for it. Nothing changes for a runtime that uses the image's own `server.xml` and has no settings file: no extra port opens and port 8080 answers as before. Two things are new in every runtime, though: the routing rules treat every Tomcat port other than 8080 as a tunnel port (a runtime with its own `server.xml` and another HTTP port gets 404 on that port for everything but `/api`), and a servlet named `tunnelApi` is registered (it answers 404 to every request that did not arrive on a tunnel port). The pack descriptor reports the feature name `tunnel-api`.

Three parts work together:

- `TunnelPortListener` (a Tomcat server listener, in the shared jar) opens one HTTPS port per entry of the settings file.
- `TunnelApiServlet` (servlet name `tunnelApi`, in the web application) answers the REST calls that arrive on a tunnel port.
- `com.viscosiety.tunnel.TunnelApiListener` is the listener type for endpoints that are limited to a tunnel port.

## The settings file

The file is `/opt/frank/tunnel-api/connectors.properties`. Another path can be set with the system property `visco.tunnel.api.connectors` (tests). Without the file nothing opens.

| Key | Meaning |
|---|---|
| `ports` | Comma-separated list of tunnel ports. |
| `<port>.keystoreFile` | A keystore with the certificate and its private key. |
| `<port>.keystorePasswordFile` | A file that holds the keystore password. |
| `<port>.keystoreType` | Optional. `PKCS12` (default) or `JKS`. |
| `<port>.certificateFile` | PEM certificate, as an alternative to a keystore. |
| `<port>.certificateKeyFile` | PEM private key for that certificate. |
| `<port>.certificateChainFile` | Optional PEM chain. |

A port gives either a keystore or a PEM pair, not both. A port number is 1024 to 65535 and is not 8005, 8009 or 8080. Port 8080 is the public port: the routing rules name it, and every other Tomcat port counts as a tunnel port.

The password is never a value in this file and never an environment variable. It is the content of the password file. All trailing line breaks are removed from it.

A keystore example:

```
ports=8443
8443.keystoreFile=/opt/frank/tunnel-api/tunnel.p12
8443.keystorePasswordFile=/opt/frank/tunnel-api/tunnel.pw
```

A PEM example, with a second port:

```
ports=8443,8444
8443.keystoreFile=/opt/frank/tunnel-api/tunnel.p12
8443.keystorePasswordFile=/opt/frank/tunnel-api/tunnel.pw
8444.certificateFile=/opt/frank/tunnel-api/site.crt
8444.certificateKeyFile=/opt/frank/tunnel-api/site.key
8444.certificateChainFile=/opt/frank/tunnel-api/chain.crt
```

Mount the folder read-only. The runtime reads the file once, at start. A new certificate needs a restart.

## What a bad entry does

A bad entry is logged once as `tunnel-api: port <port> not opened: <reason>` (level SEVERE) and skipped. The other ports and the runtime still start. Bad means: a bad port number, a port listed twice, a port that gives both a keystore and a PEM pair or neither, a bad keystore type, a missing or unreadable file, a keystore that does not open with the password, a keystore without a private key, a port that is already in use or cannot be bound. A settings file that cannot be read at all (also a malformed path in `visco.tunnel.api.connectors`) is logged as `tunnel-api: port * not opened: the settings file cannot be read` and opens nothing. The reason never contains a password or file content.

The content of a PEM pair, and a keystore whose key has another password than the store, are checked by Tomcat when it starts the port. Tomcat logs the problem in its own lines; after the start the listener logs `tunnel-api: port <port> not opened: the certificate or key could not be loaded (see the log lines above)` for each port that did not start. A PEM key must be unencrypted. A JKS or PKCS12 key must have the same password as the store.

Once Tomcat has started, the log says `tunnel-api: HTTPS on port(s) <ports>` with the ports that really listen.

The ports that really listen are published as the system property `visco.tunnel.api.ports` (at start the property names every port that was set up; a port that then fails to start is removed from it, and the property is cleared when none is left). Code that has to know whether a request came in on a tunnel port compares `request.getLocalPort()` with that list. It never uses `getServerPort()`: that one comes from the `Host` header.

## Addresses

On a tunnel port the address is `https://<name>:<port>/api/<uriPattern>`. The forms `/runtime/api/...` and `/viscolink/api/...` work too. The image's routing sends all of them to the tunnel servlet. On a tunnel port every other path answers 404. `/runtime/tunnel-api/...` is not an address anyone calls: it answers 404 on both sides.

## The servlet settings

The tunnel servlet is secured only by its own `servlet.tunnelApi.*` settings; the console login (`application.security.console.authentication.*`) does not apply to it.

Frank!Framework builds only the authenticators named in `application.security.http.authenticators=<comma list>`. Every authenticator that a `servlet.*.authenticator` line names must be in that list.

The servlet is registered also when no `servlet.tunnelApi.*` setting exists, with Frank!Framework's default authenticator. That default also applies when a tunnel port is set up but `servlet.tunnelApi.authenticator` is not: always set the authenticator explicitly. It answers 404 to everything that did not arrive on a tunnel port, and with no tunnel port open that is every request. Without tunnel settings the safety of a runtime therefore rests on that check in the servlet, not on the authenticator.

Settings: `servlet.tunnelApi.authenticator`, `servlet.tunnelApi.securityRoles` and `servlet.tunnelApi.transportGuarantee`.

Set `servlet.tunnelApi.transportGuarantee=CONFIDENTIAL`. The image sets `application.security.http.transportGuarantee=NONE` for every servlet, because on the public side HTTPS ends before Tomcat; a tunnel port is HTTPS in Tomcat itself, so the tunnel servlet can demand it. On a plain port the setting makes a request to the servlet answer a redirect to HTTPS instead of 404; the image's routing already answers 404 on the public port for the tunnel servlet's path.

All three blocks below need web security on: `application.security.http.authentication=true`. That is Frank!Framework's default when the setting is empty, except on stage `LOC`, where the default is off.

**Tunnel side with a login.** Without the roles line the endpoints are open.

```
application.security.http.authentication=true
application.security.http.authenticators=publicBasic,tunnelBasic
application.security.http.authenticators.publicBasic.type=IN_MEMORY
application.security.http.authenticators.publicBasic.username=<public user>
application.security.http.authenticators.publicBasic.password=<public password>
application.security.http.authenticators.tunnelBasic.type=IN_MEMORY
application.security.http.authenticators.tunnelBasic.username=<site user>
application.security.http.authenticators.tunnelBasic.password=<site password>
servlet.ApiListenerServlet.authenticator=publicBasic
servlet.ApiListenerServlet.securityRoles=IbisTester
servlet.tunnelApi.authenticator=tunnelBasic
servlet.tunnelApi.securityRoles=IbisTester
servlet.tunnelApi.transportGuarantee=CONFIDENTIAL
```

A `YML` authenticator works the same way. The tunnel login and the public login are separate: a site login does not work on the public side and the other way round.

**Tunnel side without a login.** Only for a tunnel that is trusted by itself. Leave out `servlet.tunnelApi.securityRoles`. A listener with `authenticationMethod="AUTHROLE"` then answers 401 to every caller on the tunnel side: the caller is anonymous and an anonymous caller is never in a role.

```
application.security.http.authentication=true
servlet.tunnelApi.authenticator=NONE
servlet.tunnelApi.transportGuarantee=CONFIDENTIAL
```

**Public side off.** Seal the public servlet with an authenticator of type `SEALED` and give it roles. Do not use `servlet.ApiListenerServlet.enabled=false` for this: that setting also switches off the servlet that the platform's own gateway forwards to.

```
application.security.http.authentication=true
application.security.http.authenticators=off,tunnelBasic
application.security.http.authenticators.off.type=SEALED
application.security.http.authenticators.tunnelBasic.type=IN_MEMORY
application.security.http.authenticators.tunnelBasic.username=<site user>
application.security.http.authenticators.tunnelBasic.password=<site password>
servlet.ApiListenerServlet.authenticator=off
servlet.ApiListenerServlet.securityRoles=IbisTester
servlet.tunnelApi.authenticator=tunnelBasic
servlet.tunnelApi.securityRoles=IbisTester
servlet.tunnelApi.transportGuarantee=CONFIDENTIAL
```

## The listener type

An endpoint that answers on a tunnel port only uses the listener type `TunnelApiListener`. It has the same attributes as `ApiListener`.

```xml
<Listener className="com.viscosiety.tunnel.TunnelApiListener" name="feed" uriPattern="/feed" method="POST"/>
```

## The rules

- Only `/api/**` answers on a tunnel port.
- One `uriPattern` and method belongs to one listener type. The same pattern and method cannot be declared on an `ApiListener` as well. One pattern can mix: a GET on an `ApiListener` and a POST on a `TunnelApiListener`.
- A tunnel-only endpoint has two locks. The first is a filter on the public side: a clean 404 with an empty body, the same as for a path no listener has. The public OpenAPI document leaves the endpoint out. On a mixed pattern the tunnel methods are removed from the `Allow` and `Access-Control-Allow-Methods` headers. The second lock is the listener itself: it refuses any request that did not arrive on a tunnel port, before a pipeline runs. It does that after the servlet's login, content-type, ETag and body-reading steps.
- A pattern with any tunnel-only method is left out of the public OpenAPI document as a whole.
- `AUTHROLE` listeners check the roles of the side the call came in on.

## Known limits

- A forward inside the web application to `/api` reaches tunnel-only endpoints. Today only the platform gateway servlet `AgentApiServlet` does that, and it needs a bearer token.
- On a mixed pattern the pipeline session key `allowedMethods` of the public listener still names the tunnel method.
- A public request that slips past the filter during a configuration reload is refused by the listener with status 500, not 404.
- A tunnel-only pattern takes precedence over an overlapping public wildcard pattern. The public side then answers 404 there.
- The filter is mapped to the servlet named `ApiListenerServlet`, whatever its URL mapping.

## Not built

- Client certificates (mutual TLS).
- A new certificate without a restart.
- Plain HTTP on a tunnel port.

## Not covered by tests here

- `AgentApiServlet`'s forward to `/api` while the public side is off needs a bearer token from an OIDC issuer. It was read in the code only (`SealedAuthenticator` is HTTP Basic over an empty user store, so a request that already carries an authenticated context with the servlet's roles passes). The deployment's own live proof covers it.
- A certificate change needs a restart; that is not tested either.
