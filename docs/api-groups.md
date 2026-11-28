# Single-servlet API groups

Releases with the `groupedApi` capability (pack feature `grouped-api`) support this mode.
Set the following Frank properties in DeploymentSpecifics to serve the public API through one
`ApiListenerServlet`. Remove `visco.api.groups` and the old servlet-wide security roles before
enabling this mode. The runtime rejects the retired group-list setting at startup. Move each
`servlet.api-<name>.*` authentication setting to `servlet.ApiListenerServlet.group.<name>.*`.
Move formerly ungrouped callers into explicit groups before upgrading.

```properties
servlet.ApiListenerServlet.authenticator=GROUPED
servlet.ApiListenerServlet.authenticationGroups=public,hospital,partner
servlet.ApiListenerServlet.group.public.authenticator=NONE
servlet.ApiListenerServlet.group.hospital.authenticator=basic
servlet.ApiListenerServlet.group.hospital.securityRoles=Hospital
servlet.ApiListenerServlet.group.partner.authenticator=partnerKey
servlet.ApiListenerServlet.group.partner.securityRoles=Partner
application.security.http.authenticators=basic,partnerKey
application.security.http.authenticators.basic.type=IN_MEMORY
application.security.http.authenticators.basic.authAlias=hospital-login
application.security.http.authenticators.partnerKey.type=IN_MEMORY
application.security.http.authenticators.partnerKey.authAlias=partner-login
application.security.http.authenticators.partnerKey.apiKeyHeader=X-Customer-Key
```

Provision each credential alias with a username and password. For a header key, the password is
the key and the username identifies the caller. Each listener declares its group literally, e.g.
`uriPattern="public/hello-world"`. Unknown groups, including formerly ungrouped URLs, receive
403 on the public API. A group cannot claim a wildcard-first listener. Each group has its own
`/runtime/api/<group>/openapi.json`. The console displays the group on the Available ApiListeners
table. Browser console sessions are not bridged into grouped API requests.

Group authentication runs on the external path. For browser OIDC, configure a dedicated named
authenticator per group and register its callback under `/runtime/api/<group>/oauth2/code/<registrationId>`.
Review existing IdP redirects and clients using the old internal `/api-group/` URL when migrating.
The site tunnel and the authorized agent gateway retain their separate authentication policies.
