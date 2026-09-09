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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

class ApiSessionAuthListenerTest {

    private final ApiSessionAuthListener listener = new ApiSessionAuthListener();
    private HttpServletRequest request;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        request = mock(HttpServletRequest.class);
        session = mock(HttpSession.class);
        lenient().when(request.getContextPath()).thenReturn("/viscolink");
        lenient().when(request.getRequestURI()).thenReturn("/viscolink/api/cocktail-advice/Rotterdam");
        lenient().when(request.getScheme()).thenReturn("https");
        lenient().when(request.getServerName()).thenReturn("bo-demo.foundry.viscosiety.com");
        lenient().when(request.getServerPort()).thenReturn(443);
        lenient().when(request.getSession(false)).thenReturn(session);
        lenient().when(request.getHeader(anyString())).thenReturn(null);
        lenient().when(request.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
        lenient().when(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .thenReturn(new SecurityContextImpl(oidcLogin()));
    }

    private static Authentication oidcLogin() {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_IbisAdmin"));
        DefaultOAuth2User user = new DefaultOAuth2User(authorities, Map.of("preferred_username", "tom"), "preferred_username");
        return new OAuth2AuthenticationToken(user, authorities, "custom");
    }

    private void fire() {
        listener.requestInitialized(new ServletRequestEvent(mock(ServletContext.class), request));
    }

    @Test
    void sameOriginApiCallWithOidcSessionIsBridged() {
        fire();
        verify(request).setAttribute(any(String.class), any(SecurityContextImpl.class));
        verify(request).setAttribute(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME,
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
    }

    @Test
    void externalCallerWithAuthorizationHeaderIsLeftToTheChain() {
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwdw==");
        fire();
        verify(request, never()).setAttribute(anyString(), any());
    }

    @Test
    void nonApiPathIsIgnored() {
        when(request.getRequestURI()).thenReturn("/viscolink/iaf/api/server/info");
        fire();
        verify(request, never()).setAttribute(anyString(), any());
    }

    @Test
    void noSessionMeansNoBridge() {
        when(request.getSession(false)).thenReturn(null);
        fire();
        verify(request, never()).setAttribute(anyString(), any());
    }

    @Test
    void onlyAnOidcLoginIsBridged() {
        // A Basic/in-memory principal from another chain must not become API access.
        when(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .thenReturn(new SecurityContextImpl(new TestingAuthenticationToken("tom", "pw", "ROLE_IbisAdmin")));
        fire();
        verify(request, never()).setAttribute(anyString(), any());
    }

    @Test
    void crossSiteAndSiblingSubdomainRequestsAreRefused() {
        when(request.getHeader("Sec-Fetch-Site")).thenReturn("cross-site");
        fire();
        when(request.getHeader("Sec-Fetch-Site")).thenReturn("same-site");
        fire();
        verify(request, never()).setAttribute(anyString(), any());
    }

    @Test
    void userInitiatedNavigationIsSameOrigin() {
        when(request.getHeader("Sec-Fetch-Site")).thenReturn("none");
        assertTrue(ApiSessionAuthListener.isSameOrigin(request));
    }

    @Test
    void withoutFetchMetadataOriginOrRefererMustMatch() {
        when(request.getHeader("Sec-Fetch-Site")).thenReturn(null);
        assertFalse(ApiSessionAuthListener.isSameOrigin(request), "no evidence of origin at all");

        when(request.getHeader("Origin")).thenReturn("https://bo-demo.foundry.viscosiety.com");
        assertTrue(ApiSessionAuthListener.isSameOrigin(request));
        when(request.getHeader("Origin")).thenReturn("https://other-demo.foundry.viscosiety.com");
        assertFalse(ApiSessionAuthListener.isSameOrigin(request));

        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getHeader("Referer")).thenReturn("https://bo-demo.foundry.viscosiety.com/viscolink/webcontent/tenant/index.html");
        assertTrue(ApiSessionAuthListener.isSameOrigin(request));
        when(request.getHeader("Referer")).thenReturn("https://bo-demo.foundry.viscosiety.com:8443/x");
        assertFalse(ApiSessionAuthListener.isSameOrigin(request), "explicit non-default port is a different origin");
    }

    @Test
    void apiPathMatching() {
        assertTrue(ApiSessionAuthListener.isApiPath("/api"));
        assertTrue(ApiSessionAuthListener.isApiPath("/api/x"));
        assertFalse(ApiSessionAuthListener.isApiPath("/api-service/reload"));
        assertFalse(ApiSessionAuthListener.isApiPath("/iaf/api/server/info"));
    }
}
