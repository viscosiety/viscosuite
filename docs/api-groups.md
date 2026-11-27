# Endpoint groups

## What it is

A runtime can serve its REST endpoints in **groups**. A group is a first path segment under `/api`: the group `webhooks` answers `/runtime/api/webhooks/...`, and it has its own login, separate from the default `/api` login and from every other group. This lets one runtime serve, for example, a public webhook next to endpoints that need a Basic login.

Without the setting nothing changes: no group servlet and no filter are registered. The routing rule and its map are always in the image's rewrite rules, and the map class is loaded at every Tomcat start, but without the setting the map knows no group and the rule never matches.

`apiGroups` is a capability name in the release list (`base.servlets`), not a servlet name: the servlets are named `api-<name>`. A deployment reads `apiGroups` as "this release can serve groups". The pack descriptor reports the feature `api-groups`.

## The setting

`visco.api.groups` is a comma-separated list of group names. It is read only as a JVM system property (`-Dvisco.api.groups=webhooks`), or else as an environment variable of that exact name. It is not a Frank!Framework property: a line in a Frank!Framework property file (DeploymentSpecifics, StageSpecifics) is not seen. A line in Tomcat's `catalina.properties` is seen, because Tomcat makes every entry there a system property. It is read once, when the runtime starts; a change needs a restart.

- A name is lowercase letters, digits and `-`, starts with a letter and is 2 to 30 characters long.
- `oauth2`, `openapi.json` and `api-service` are reserved and refused.
- At most 10 groups. A bad or surplus entry is logged and skipped; the other groups still start.

## Addresses

- `/runtime/api/<name>/...` is the address callers use. `/viscolink/api/<name>/...` works too.
- `/runtime/api-group/<name>/...` reaches the same servlet with the same login. It is not an address anyone needs to give out. An OIDC group's sign-in callback is expected to arrive there (the OAuth2 authenticator builds its callback from a servlet mapping); this is not tested here.

The image's routing rewrites the first two forms to the internal one, on the public port only (not on a tunnel port). A group name reserves that first segment: on `/api` itself a group path answers 404 (the default servlet never serves it).

## The servlet settings

Each group is a servlet `api-<name>`, secured only by its own settings:

- `servlet.api-<name>.authenticator`
- `servlet.api-<name>.securityRoles`
- a named authenticator must be listed in `application.security.http.authenticators`; the built-in `NONE` needs no entry.

Settings are per servlet because Frank!Framework attaches the login to the servlet: one servlet, one login. Always set the authenticator explicitly; without it Frank!Framework's default applies (`application.security.http.authenticator` when web security is on, else `NONE`). Do not set `servlet.api-<name>.urlMapping`: the routing sends requests to `/api-group/<name>/*`, and another mapping detaches the servlet from it.

Example: a Basic default and a Public `webhooks` group.

```
application.security.http.authentication=true
application.security.http.authenticators=basic
application.security.http.authenticators.basic.type=IN_MEMORY
application.security.http.authenticators.basic.username=<user>
application.security.http.authenticators.basic.password=<password>
servlet.ApiListenerServlet.authenticator=basic
servlet.ApiListenerServlet.securityRoles=IbisTester
servlet.api-webhooks.authenticator=NONE
```

and, outside the property file, the JVM option `-Dvisco.api.groups=webhooks` (or the environment variable `visco.api.groups=webhooks`).

## What a listener does

Put the group name first in `uriPattern`: `uriPattern="/webhooks/orders"`. Nothing else is needed. The listener is the ordinary `ApiListener`; the first segment decides which servlet (and so which login) serves it.

## The second lock

A group serves only endpoints whose `uriPattern` starts with its own name. Frank!Framework matches `{param}` as any one segment and `**` as anything, so a default listener such as `{tenant}/orders` or `**` would also match `/webhooks/orders`. The group servlet therefore looks up the pattern before it serves a request: a pattern whose first segment is not literally the group's name answers 404 with an empty body there, and its per-pattern OpenAPI document answers "OpenApi specification not found". The default `/api` servlet answers 404 for any group path. A group endpoint is not served under another login, and no other endpoint is served under a group's login.

A listener whose first segment is a wildcard is therefore not reachable for a group name at all: `/api/webhooks/orders` is 404 on `/api` (a group path), and on the group servlet the pattern is not the group's.

## The gateway

A forward inside the web application to `/api` (the platform gateway's `AgentApiServlet`) reaches group endpoints too, under the gateway's own bearer login. This follows from the filter mapping (the path lock and the tunnel-only filter are mapped to the REQUEST dispatch only); it is proven in the platform's live proof, not by a test here.

## The tunnel side

The tunnel port (see `docs/tunnel-api.md`) has one login for all of `/api/**`, groups included. A group does not get a login of its own there. This follows from the tunnel rule (on a tunnel port the routing sends all of `/api/**` to the tunnel servlet before the group rule; the routing test checks that a group path goes there); one login end to end is proven in the platform's live proof, not by a test here.

On the public port a group servlet refuses tunnel-only endpoints (`TunnelApiListener`) under its path the same way the default servlet does: the request answers 404, the per-pattern OpenAPI document answers "OpenApi specification not found", and a pattern that mixes public and tunnel-only methods leaves the tunnel-only ones out of `Allow` and `Access-Control-Allow-Methods`.

## OpenAPI

`/api/openapi.json` lists the default endpoints and leaves group endpoints out. Each group has its own document (`/runtime/api/<name>/openapi.json`), with server URL `<context>/api` and the group's endpoints, tunnel-only endpoints left out. The per-pattern form `/runtime/api/<name>/<pattern>/openapi.json` answers for the group's own patterns with the same server URL. `?uri=<group path>` on the default document answers 404.

## Known limits

- Only the first path segment names a group; there are no nested groups.
- A group name reserves that segment on `/api` for every listener.
- The same-origin Basic skip of the default servlet's `ApiSessionAuthRegistrar` is not registered on group servlets.
- The store-only image does not serve groups: it cannot load the shared jar that holds the routing map.
