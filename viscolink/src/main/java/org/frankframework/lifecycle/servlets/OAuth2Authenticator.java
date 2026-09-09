/*
   Copyright 2023-2026 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
// ============================================================================
// TEMPORARY CLASSPATH OVERRIDE -- NOT VISCOSIETY CODE
//
// This is org.frankframework.lifecycle.servlets.OAuth2Authenticator exactly as
// built into the consumed Frank!Framework nightly 10.3.0-20260902.042323
// (frankframework master e3803c17, 2026-09-01) plus two ViscoLink changes,
// both in configure(HttpSecurity):
//  1. the interactive login chain is made stateful (IF_REQUIRED +
//     HttpSessionRequestCache + HttpSessionSecurityContextRepository);
//  2. allowBasicAuthentication/basicUsersFile: HTTP Basic for the users of a
//     YmlFileAuthenticator-format file on the SAME chain, so API users coexist
//     with the Keycloak login and bearer tokens on one servlet (the combined
//     "OIDC + Basic" API exposure mode).
// Without it the STATELESS policy of the base class discards the originally
// requested URL (every Keycloak login lands on "/" instead of the deep link,
// observed live with /webcontent/<config>/... pages) and the login itself
// (every follow-up request re-runs the redirect dance).
//
// It lives in the WAR because WEB-INF/classes takes precedence over
// WEB-INF/lib by servlet spec (staged there by the stage-oauth2-override
// execution in the pom), so this class shadows the one in
// frankframework-security.jar. The same fix has been submitted upstream from
// tommy2d/frankframework branch claude/upstream-repo-sync-tu8aso.
//
// MUST track the consumed nightly, never frankframework master: master has
// since moved bearer support out of this class, and an override copied from
// there silently dropped allowBearerAuthentication (2026-09-09). On every
// F!F version bump, re-derive this file from that version's source and
// re-apply the patch; DELETE it once the version carries the upstream fix.
// ============================================================================
package org.frankframework.lifecycle.servlets;

import java.io.FileNotFoundException;
import java.net.URL;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistration.Builder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.config.http.SessionCreationPolicy;
import java.io.InputStream;
import java.io.Reader;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.yaml.snakeyaml.Yaml;

import org.frankframework.util.StreamUtil;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;


import org.frankframework.credentialprovider.CredentialFactory;
import org.frankframework.credentialprovider.ICredentials;
import org.frankframework.doc.Mandatory;
import org.frankframework.util.ClassUtils;
import org.frankframework.util.EnumUtils;
import org.frankframework.util.SpringUtils;
import org.frankframework.util.StringUtil;

/**
 * OAuth2 Authentication provider which contains 5 defaults (Google, GitHub, Facebook, Okta and Keycloak), as well as a custom setting which allows users to
 * use their own IDP.
 * <p>
 * Default redirect url is as follows:
 * <pre>{@code
 *   {baseUrl}/-servlet-name-/oauth2/code/{registrationId}
 * }</pre>
 * </p>
 * <p>
 * {baseUrl} resolves to {baseScheme}://{baseHost}{basePort}{basePath}.
 * <br>
 * The redirect url has been modified to match the servlet path and is deduced from the default
 * {@link OAuth2LoginAuthenticationFilter#DEFAULT_FILTER_PROCESSES_URI}.
 * Authentication base URL is: {@code -servlet-name-/oauth2/authorization}
 * </p>
 * <p>
 * This authenticator should be configured by setting its type to 'OAUTH2', for example:
 * <pre>{@code
 *   application.security.console.authentication.type=OAUTH2
 *   application.security.console.authentication.provider=google
 *   application.security.console.authentication.clientId=my-client-id
 *   application.security.console.authentication.clientSecret=my-client-secret
 * }</pre>
 * </p>
 * <p>
 * Please note that we assume keycloak version 18 or higher is used, so we use the new endpoints and not the old ones. So `HOST_NAME/realms/test` is
 * used and not `HOST_NAME/auth/realms/test`.
 * </p>
 *
 * @author Niels Meijer
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html#oauth2Client-auth-code-redirect-uri">Spring Security OAuth2 Authorization Grants</a>
 * @see OAuth2AuthorizationRequestRedirectFilter#DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
 */
public class OAuth2Authenticator extends AbstractOAuth2Authenticator {

	/**
	 * The scopes to request from the OAuth2 provider.
	 * <p>
	 * Multiple scopes should be comma-separated, e.g. {@code "openid,profile,email"}
	 * </p>
	 */
	private String scopes;

	/**
	 * The authorization endpoint URI used to authenticate users.
	 * This is the endpoint where users are redirected to begin the authentication process.
	 * <p>
	 * e.g. {@code https://accounts.google.com/o/oauth2/v2/auth}
	 * </p>
	 */
	private String authorizationUri;

	/**
	 * The token endpoint URI used to exchange authorization codes for access tokens.
	 * <p>
	 * e.g. {@code https://www.googleapis.com/oauth2/v4/token}
	 * </p>
	 */
	private String tokenUri;

	/**
	 * The base URL used to build the complete redirect URI. Must be an absolute URL for proper OAuth2 flow.
	 * <p>
	 * e.g. external absolute URL (must start with {@code http(s)://})
	 * </p>
	 */
	private String baseUrl;

	/**
	 * When {@code true}, this authenticator additionally validates incoming bearer JWTs as an OAuth2
	 * resource server (on top of the interactive browser login), so both a human in a browser (OIDC
	 * login) and an external system presenting an {@code Authorization: Bearer <jwt>} token can access
	 * the same endpoint. Requires {@code issuerUri} or {@code jwkSetUri} to be set. Defaults to
	 * {@code false}, in which case behaviour is unchanged.
	 */
	private boolean allowBearerAuthentication = false;

	/**
	 * ViscoLink extension: also accept HTTP Basic credentials of the users listed in
	 * {@link #setBasicUsersFile basicUsersFile} (the {@link YmlFileAuthenticator} file format)
	 * on this chain. Basic callers are authenticated per request and get no session.
	 */
	private boolean allowBasicAuthentication = false;

	/** Resource URL of the YML user list used when {@code allowBasicAuthentication} is set. */
	private String basicUsersFile = "localUsers.yml";

	/**
	 * The client ID to use for the OAuth2 provider.
	 */
	private String clientId = null;

	/**
	 * The client secret to use for the OAuth2 provider.
	 */
	private String clientSecret = null;

	/**
	 * The AuthAlias which contains the clientId and clientSecret.
	 */
	private String clientAuthAlias = null;

	private ICredentials clientCredentials;

	/**
	 * The tenant ID to use for the Azure or Keycloak provider.
	 */
	private String tenantId = null;

	/**
	 * The OAuth2 provider to use.
	 * <p>
	 * Supported providers are:
	 * <ul>
	 *   <li>google - Google OAuth2</li>
	 *   <li>github - GitHub OAuth2</li>
	 *   <li>facebook - Facebook OAuth2</li>
	 *   <li>okta - Okta OAuth2</li>
	 *   <li>azure - Microsoft Azure OAuth2</li>
	 *   <li>keycloak - Keycloak</li>
	 *   <li>custom - Custom OAuth2 provider (requires additional configuration)</li>
	 * </ul>
	 * </p>
	 */
	@Mandatory
	private String provider;

	/**
	 * Whether to use PKCE (Proof Key for Code Exchange) for the OAuth2 authorization code flow.
	 * <p>
	 * PKCE enhances security by requiring a code verifier and code challenge during the authorization process.
	 * See <a href="https://oauth.net/2/pkce/">OAuth 2.0 PKCE</a> for more information.
	 * </p>
	 * <p>Please note that IdP's support {@code plain} or {@code S256}. We use {@code S256} since that's the secure option.</p>
	 */
	private boolean usePkce = false;

	private ClientRegistrationRepository clientRepository;

	private String servletPath;

	/**
	 * The redirect URI to use for the OAuth2 provider.
	 * <p>
	 * This URI is used to redirect the user back to the application after authentication.
	 * </p>
	 */
	private String redirectUri;

	/**
	 * The role-mapping file, defaults to `oauth-role-mapping.properties` in the classpath.
	 * This file is used to map OAuth2 roles to Frank roles.
	 * For example:
	 * <pre>{@code
	 * IbisAdmin=admin
	 * IbisObserver=viewer
	 * IbisTester=tester
	 * }</pre>
	 */
	private String roleMappingFile = "oauth-role-mapping.properties";

	private URL roleMappingURL = null;

	private OAuth2AuthorizedClientService clientService;

	@Override
	public SecurityFilterChain configure(HttpSecurity http) throws Exception {
		configure();

		// ViscoLink patch (see banner): the authorization-code flow needs session state so a
		// deep link survives the IdP redirect and the login outlives the request. Only this
		// chain leaves STATELESS; bearer calls below create no session.
		http.sessionManagement(management -> management.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
		http.requestCache(cache -> cache.requestCache(new HttpSessionRequestCache()));
		HttpSessionSecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
		http.setSharedObject(SecurityContextRepository.class, securityContextRepository);
		http.securityContext(context -> context.securityContextRepository(securityContextRepository));

		AuthorityMapper authorityMapper = new AuthorityMapper(roleMappingURL, getSecurityRoles(), getEnvironmentProperties(), authoritiesClaimName);

		// The 3 dynamic URLs use the servlet path, this cannot be changed or contain {baseUrl}.
		http.oauth2Login(login -> login
				.clientRegistrationRepository(clientRepository) // Explicitly set, but can also be implicitly implied.
				.authorizedClientService(clientService)
				.failureUrl(servletPath + "/oauth2/failure/")
				.authorizationEndpoint(this::getOauth2LoginConfigurer)
				.userInfoEndpoint(endpoint -> endpoint.userAuthoritiesMapper(authorityMapper))
				.loginProcessingUrl(servletPath + "/oauth2/code/*"));

		if (allowBasicAuthentication) {
			// ViscoLink extension (see banner): the YML user list as an HTTP Basic user store on
			// this same chain. Spring's ProviderManager routes each Authentication to the provider
			// that supports it, so the DAO provider added here, the oauth2Login provider above and
			// the bearer resource server below coexist. Basic requests never touch the session.
			http.userDetailsService(new InMemoryUserDetailsManager(loadBasicUsers().getUserDetails()));
			http.httpBasic(basic -> basic
					.realmName("Frank")
					.securityContextRepository(new RequestAttributeSecurityContextRepository()));
		}

		if (allowBearerAuthentication) {
			// Also accept a bearer JWT (external systems). Both mechanisms then live on one chain: a request with an `Authorization: Bearer` header is
			// validated statelessly by the resource server, a browser request without a token falls through to the oauth2Login redirect.
			configureBearerTokenResourceServer(http);
		}

		if (allowBearerAuthentication || allowBasicAuthentication) {
			// With several mechanisms configured, unauthenticated requests must resolve to the right challenge:
			// API clients get a 401 naming every accepted scheme (WWW-Authenticate: Bearer and/or Basic), browsers get the login redirect.
			http.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
					apiEntryPoint(), AuthenticatorUtils::isApiRequest));
		}

		return http.build();
	}

	/**
	 * Gets the OAuth2LoginConfigurer with optional PKCE support.
	 */
	private OAuth2LoginConfigurer<HttpSecurity>.@NonNull AuthorizationEndpointConfig getOauth2LoginConfigurer(OAuth2LoginConfigurer<HttpSecurity>.AuthorizationEndpointConfig endpoint) {
		String authorizationRequestBaseUri = servletPath + OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI;

		log.debug("Configuring OAuth2LoginConfigurer with baseUri [{}]", authorizationRequestBaseUri);

		endpoint.baseUri(authorizationRequestBaseUri);

		if (usePkce) {
			log.info("enabling PKCE support for OAuth2 authentication. Make sure your IdP client is configured to use PKCE s256 proof keys.");
			DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(clientRepository, authorizationRequestBaseUri);
			resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

			endpoint.authorizationRequestResolver(resolver);
		}

		return endpoint;
	}

	private void configure() throws FileNotFoundException {
		ICredentials credentials = CredentialFactory.getCredentials(clientAuthAlias, clientId, clientSecret);

		if (StringUtils.isEmpty(credentials.getUsername()) || StringUtils.isEmpty(credentials.getPassword())) {
			throw new IllegalStateException("clientId and clientSecret must be set");
		}

		if (StringUtils.isEmpty(provider)) {
			throw new IllegalStateException("A provider must be set");
		}

		if (StringUtils.countMatches(authoritiesClaimName, ".") > 1) {
			throw new IllegalArgumentException("The authoritiesClaimName must not contain more than one dot (.) to indicate a nested claim. Found: " + authoritiesClaimName);
		}

		this.clientCredentials = credentials;

		roleMappingURL = ClassUtils.getResourceURL(roleMappingFile);
		if (roleMappingURL == null) {
			throw new FileNotFoundException("unable to find OAUTH role-mapping file [" + roleMappingFile + "]");
		}
		log.info("found rolemapping file [{}]", roleMappingURL);

		servletPath = computeRelativePathFromServlet();
		redirectUri = computeRedirectUri();

		log.debug("using oauth servlet-path [{}] and redirect-uri [{}]", servletPath, redirectUri);

		clientRepository = getOrCreateClientRegistrationRepository();
	}

	public ClientRegistrationRepository getOrCreateClientRegistrationRepository() {
		if (clientRepository == null) {
			clientRepository = new InMemoryClientRegistrationRepository(getRegistration(provider, clientCredentials));
			SpringUtils.registerSingleton(getApplicationContext(), "clientRegistrationRepository", clientRepository);
			clientService = new InMemoryOAuth2AuthorizedClientService(clientRepository);
		}
		return clientRepository;
	}

	protected ClientRegistration getRegistration(@NonNull String provider, @NonNull ICredentials credentials) {
		ClientRegistration.Builder builder = switch (provider.toLowerCase()) {
			case "google", "github", "facebook", "okta" -> {
				CommonOAuth2Provider commonProvider = EnumUtils.parse(CommonOAuth2Provider.class, provider);
				yield commonProvider.getBuilder(provider);
			}
			case "azure" -> createAzureBuilder(credentials.getUsername());
			case "keycloak" -> createKeycloakBuilder();
			case "custom" -> createCustomBuilder(provider, provider.toLowerCase());
			default -> throw new IllegalStateException("unknown OAuth provider");
		};

		builder.clientId(credentials.getUsername())
				.clientSecret(credentials.getPassword())
				.redirectUri(getRedirectUri());

		return builder.build();
	}

	/**
	 * @param appId the Azure ClientID
	 *              See <a href="https://login.microsoftonline.com/%s/.well-known/openid-configuration">Microsoft well known information</a>
	 */
	private ClientRegistration.Builder createAzureBuilder(@NonNull String appId) {
		if (StringUtils.isBlank(tenantId)) throw new IllegalStateException("when using Azure provider the tenantId property is required");

		ClientRegistration.Builder builder = ClientRegistration.withRegistrationId("azure");
		builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
		builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);

		// Use the default scopes but allow users to overwrite them
		builder.scope(StringUtil.split(StringUtils.isBlank(scopes) ? "openid,profile,email" : scopes));

		builder.authorizationUri("https://login.microsoftonline.com/%s/oauth2/v2.0/authorize".formatted(tenantId));
		builder.tokenUri("https://login.microsoftonline.com/%s/oauth2/v2.0/token".formatted(tenantId));
		builder.jwkSetUri("https://login.microsoftonline.com/%s/discovery/v2.0/keys?appid=%s".formatted(tenantId, appId));
		builder.issuerUri("https://login.microsoftonline.com/%s/v2.0".formatted(tenantId));
		builder.userInfoUri("https://graph.microsoft.com/oidc/userinfo");
		builder.userNameAttributeName("email");
		builder.clientName("azure");

		return builder;
	}

	/**
	 * Uses Keycloak's OIDC discovery endpoint to autoconfigure all provider URLs. We use the issuerUri to base the discovery url off of.
	 * The discovery document is fetched from: {@code {baseUrl}/realms/{tenantId}/.well-known/openid-configuration}
	 */
	private ClientRegistration.Builder createKeycloakBuilder() {
		if (StringUtils.isBlank(tenantId)) {
			throw new IllegalStateException("when using Keycloak provider the tenantId property is required");
		}

		if (StringUtils.isBlank(baseUrl)) {
			throw new IllegalStateException("when using Keycloak provider the baseUrl property is required");
		}

		String issuerUri = "%s/realms/%s".formatted(baseUrl, tenantId);

		// Fetches .well-known/openid-configuration to auto-configure authorizationUri, tokenUri, jwkSetUri, userInfoUri, etc.
		ClientRegistration.Builder builder = ClientRegistrations.fromOidcIssuerLocation(issuerUri);
		builder.registrationId("keycloak");

		// Use the default scopes but allow users to overwrite them
		builder.scope(StringUtil.split(StringUtils.isBlank(scopes) ? "openid,profile,email" : scopes));

		builder.userNameAttributeName("preferred_username");
		builder.clientName("keycloak");

		return builder;
	}

	public Builder createCustomBuilder(String name, String registrationId) {
		ClientRegistration.Builder builder = ClientRegistration.withRegistrationId(registrationId);

		builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
		builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);

		builder.scope(StringUtil.split(scopes));
		builder.authorizationUri(authorizationUri);
		builder.tokenUri(tokenUri);
		builder.jwkSetUri(jwkSetUri);
		builder.issuerUri(issuerUri);
		builder.userInfoUri(userInfoUri);
		builder.userNameAttributeName(userNameAttributeName);
		builder.clientName(name);

		return builder;
	}

	/**
	 * Absolute base URL starts e.g. `http(s)://{host}:{port}/` or is NULL (relative).
	 */
	@Nullable
	private String determineBaseUrl() {
		if (StringUtils.isEmpty(baseUrl)) {
			log.debug("using no baseUrl");
			return null;
		}

		final String computed;
		if (baseUrl.endsWith("/")) { // Ensure the url does not end with a slash
			computed = baseUrl.substring(0, baseUrl.length() - 1);
		} else {
			computed = baseUrl;
		}

		log.debug("using baseUrl [{}]", computed);
		return computed;
	}

	/**
	 * When no base URL, spring uses {baseUrl} which resolves to: {baseScheme}://{baseHost}{basePort}{contextPath}.
	 * And when no base URL we must add the servlet-path our selves: "{baseUrl}" + servletPath;
	 * <p>
	 * When a base URL has been set, use that instead!
	 * </p>
	 */
	@NonNull
	private String computeRedirectUri() {
		String determinedBaseUrl = determineBaseUrl();

		if (determinedBaseUrl == null) {
			String path = servletPath.startsWith("/") ? servletPath.substring(1) : servletPath;

			String formatted = "{baseUrl}/%s/oauth2/code/{registrationId}".formatted(path);
			log.debug("computed redirect-uri [{}] without baseUrl", formatted);

			return formatted;
		}

		String formatted = "%s/oauth2/code/{registrationId}".formatted(determinedBaseUrl);
		log.debug("computed redirect-uri [{}] with baseUrl", formatted);

		return formatted;
	}

	/**
	 * Servlet-Path that needs to be secured. May not end with a `*` or `/`.
	 * For instance `/iaf/gui`.
	 */
	private String computeRelativePathFromServlet() {
		String servletPath = getPrivateEndpoints().stream().findFirst().orElse("");

		if (servletPath.endsWith("*")) { // Strip the '*' if the url ends with it
			servletPath = servletPath.substring(0, servletPath.length() - 1);
		}

		if (servletPath.endsWith("/")) { // Ensure the url does not end with a slash
			servletPath = servletPath.substring(0, servletPath.length() - 1);
		}

		log.debug("using oauth servlet-path [{}]", servletPath);
		return servletPath;
	}

	/**
	 * 401 for API clients. Bearer keeps Spring's own entry point (it reports invalid-token
	 * details in the challenge); Basic is announced alongside it, or alone when only Basic is on.
	 */
	private AuthenticationEntryPoint apiEntryPoint() {
		AuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
		return (request, response, exception) -> {
			if (allowBearerAuthentication) {
				bearer.commence(request, response, exception);
			} else {
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			}
			if (allowBasicAuthentication) {
				response.addHeader("WWW-Authenticate", "Basic realm=\"Frank\"");
			}
		};
	}

	private YmlFileAuthenticator.LocalUsers loadBasicUsers() {
		URL url;
		try {
			url = ClassUtils.getResourceURL(basicUsersFile);
		} catch (FileNotFoundException e) {
			throw new IllegalStateException("unable to find basicUsersFile [" + basicUsersFile + "]", e);
		}
		if (url == null) {
			throw new IllegalStateException("unable to find basicUsersFile [" + basicUsersFile + "]");
		}
		try (InputStream is = url.openStream(); Reader reader = StreamUtil.getCharsetDetectingInputStreamReader(is)) {
			YmlFileAuthenticator.LocalUsers users = new Yaml().loadAs(reader, YmlFileAuthenticator.LocalUsers.class);
			if (users == null || users.getUserDetails() == null) {
				throw new IllegalStateException("basicUsersFile [" + url + "] contains no users");
			}
			log.info("accepting HTTP Basic for [{}] users from [{}]", users.getUserDetails().size(), url);
			return users;
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("unable to parse basicUsersFile [" + url + "]", e);
		}
	}

	// Plain accessors replacing the upstream source's Lombok annotations
	// (ViscoSuite does not run the Lombok annotation processor).

	public void setScopes(String scopes) {
		this.scopes = scopes;
	}

	public void setAuthorizationUri(String authorizationUri) {
		this.authorizationUri = authorizationUri;
	}

	public void setTokenUri(String tokenUri) {
		this.tokenUri = tokenUri;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public void setAllowBearerAuthentication(boolean allowBearerAuthentication) {
		this.allowBearerAuthentication = allowBearerAuthentication;
	}

	public void setAllowBasicAuthentication(boolean allowBasicAuthentication) {
		this.allowBasicAuthentication = allowBasicAuthentication;
	}

	public void setBasicUsersFile(String basicUsersFile) {
		this.basicUsersFile = basicUsersFile;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}

	public void setClientAuthAlias(String clientAuthAlias) {
		this.clientAuthAlias = clientAuthAlias;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public void setUsePkce(boolean usePkce) {
		this.usePkce = usePkce;
	}

	public void setRoleMappingFile(String roleMappingFile) {
		this.roleMappingFile = roleMappingFile;
	}

	public String getRedirectUri() {
		return redirectUri;
	}
}
