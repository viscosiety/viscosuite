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

package org.frankframework.lifecycle.servlets;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import org.frankframework.util.SpringUtils;

/**
 * Pins ViscoLink's temporary classpath override of the Frank!Framework
 * {@link OAuth2Authenticator} (see that file's banner): the interactive
 * authorization-code chain must be STATEFUL — the originally requested URL is
 * saved across the IdP redirect (or every login lands on "/" instead of the
 * deep link, observed live with {@code /webcontent/<config>/...} pages) and
 * the login persists in the session (or every follow-up request re-runs the
 * redirect dance). Delete together with the override once the consumed
 * Frank!Framework build carries the upstream fix.
 *
 * Harness mirrors the framework's own ServletAuthenticatorTest, inlined here
 * because test classes are not published in the framework jars (plain
 * GenericApplicationContext instead of spring-test: the framework's Spring 7
 * SpringExtension needs a newer JUnit API than the 5.14 this module pins).
 */
class OAuth2AuthenticatorOverrideTest {

	private GenericApplicationContext applicationContext;
	private OAuth2Authenticator authenticator;
	private HttpSecurity httpSecurity;

	private static class AllAuthenticatedProvider implements AuthenticationProvider {
		@Override
		public Authentication authenticate(Authentication authentication) throws AuthenticationException {
			return authentication;
		}

		@Override
		public boolean supports(Class<?> authentication) {
			return true;
		}
	}

	@BeforeEach
	void setup() {
		applicationContext = new GenericApplicationContext();
		SecuritySettings.setupDefaultSecuritySettings(applicationContext.getEnvironment());
		applicationContext.refresh();
		authenticator = new OAuth2Authenticator();
		SpringUtils.autowireByType(applicationContext, authenticator);
		httpSecurity = createHttpSecurity();
	}

	private HttpSecurity createHttpSecurity() {
		ObjectPostProcessor<Object> objectPostProcessor = new ObjectPostProcessor<>() {
			@Override
			public <O> O postProcess(O object) {
				return object;
			}
		};
		AuthenticationManagerBuilder authMgrBuilder = new AuthenticationManagerBuilder(objectPostProcessor);
		authMgrBuilder.authenticationProvider(new AllAuthenticatedProvider());
		Map<Class<?>, Object> sharedObjects = Map.of(ApplicationContext.class, applicationContext,
				PathPatternRequestMatcher.Builder.class, PathPatternRequestMatcher.withDefaults());
		return new HttpSecurity(objectPostProcessor, authMgrBuilder, sharedObjects);
	}

	@AfterEach
	void tearDown() {
		applicationContext.close();
	}

	@Test
	void interactiveLoginChainIsStateful() throws Exception {
		authenticator.setClientId("clientID");
		authenticator.setClientSecret("clientSecret");
		authenticator.setProvider("github");

		ServletConfiguration config = new ServletConfiguration();
		Environment environment = mock(Environment.class);
		when(environment.getProperty(anyString())).thenReturn("CONTAINER");
		config.setEnvironment(environment);
		config.afterPropertiesSet();
		config.setUrlMapping("/webcontent/*");
		config.setSecurityRoles(new String[]{ "IbisTester" });
		authenticator.registerServlet(config);

		assertNotNull(authenticator.configureHttpSecurity(httpSecurity));

		// The authorization-code flow must save the originally requested URL and
		// persist the login in the session -- see the class comment.
		assertInstanceOf(HttpSessionRequestCache.class, httpSecurity.getSharedObject(RequestCache.class));
		assertInstanceOf(HttpSessionSecurityContextRepository.class, httpSecurity.getSharedObject(SecurityContextRepository.class));
	}

	@Test
	void bearerAuthenticationSurvivesTheOverride() throws Exception {
		// Regression (2026-09-09): an override derived from frankframework master -- where bearer
		// support has since moved to BearerOnlyAuthenticator -- silently dropped
		// allowBearerAuthentication, so the portal's and the agent's bearer calls to /iaf/api
		// were refused. The override must keep tracking the CONSUMED nightly's source.
		authenticator.setClientId("clientID");
		authenticator.setClientSecret("clientSecret");
		authenticator.setProvider("github");
		authenticator.setAllowBearerAuthentication(true);
		authenticator.setJwkSetUri("https://idp.example/realms/x/protocol/openid-connect/certs");

		ServletConfiguration config = new ServletConfiguration();
		Environment environment = mock(Environment.class);
		when(environment.getProperty(anyString())).thenReturn("CONTAINER");
		config.setEnvironment(environment);
		config.afterPropertiesSet();
		config.setUrlMapping("/iaf/api/*");
		config.setSecurityRoles(new String[]{ "IbisTester" });
		authenticator.registerServlet(config);

		SecurityFilterChain chain = authenticator.configureHttpSecurity(httpSecurity);

		assertInstanceOf(DefaultSecurityFilterChain.class, chain);
		assertTrue(((DefaultSecurityFilterChain) chain).getFilters().stream().anyMatch(BearerTokenAuthenticationFilter.class::isInstance),
				"bearer resource-server filter missing from the OAUTH2 chain");
	}
}
