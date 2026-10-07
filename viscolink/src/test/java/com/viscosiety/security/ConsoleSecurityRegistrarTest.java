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

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.context.WebApplicationContext;

import com.viscosiety.pack.CorePack;
import com.viscosiety.pack.HealthValuesPack;
import com.viscosiety.pack.PackDescriptor;
import com.viscosiety.pack.PackRegistryTestSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConsoleSecurityRegistrarTest {

    @AfterEach
    void resetPack() {
        PackRegistryTestSupport.reset();
    }

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
        // The registrar honours whatever the resolved pack declares: /fhir/ is the health pack's path.
        PackRegistryTestSupport.override(new HealthValuesPack());
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

    @Test
    void fhirIsNotFrankOwnedOnTheCore() {
        // /fhir/ is the health pack's path, not a core one: under the core pack it is a tool page again,
        // so it gets the console's tool-page chain.
        PackRegistryTestSupport.override(new CorePack());
        assertFalse(ConsoleSecurityRegistrar.isFrankOwnedPath("/fhir/x"));
    }

    @Test
    void corePrefixesStayFrankOwnedWithoutThePack() {
        PackRegistryTestSupport.override(new CorePack());
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/iaf/x"));
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/api/x"));
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/api-service/x"));
    }

    @Test
    void aPacksOwnPathIsFrankOwned() {
        PackDescriptor pack = mock(PackDescriptor.class);
        when(pack.frankOwnedPaths()).thenReturn(List.of("/vendor/", "/other/"));
        PackRegistryTestSupport.override(pack);

        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/vendor/x"));
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/other/y"));
        assertFalse(ConsoleSecurityRegistrar.isFrankOwnedPath("/fhir/x"));
        assertFalse(ConsoleSecurityRegistrar.isFrankOwnedPath("/tools/some-tool"));
    }

    @Test
    void aMalformedPackFailsTheRegistrarAtStart() {
        // Resolution (and with it the pack's path validation) must happen when the context starts, not
        // on the first tool-page request. The context has no ServletContext, so without the eager
        // resolve afterPropertiesSet would only log a warning and return.
        installMalformedPack();
        ConsoleSecurityRegistrar registrar = new ConsoleSecurityRegistrar();
        registrar.setApplicationContext(mock(ApplicationContext.class));

        IllegalStateException e = assertThrows(IllegalStateException.class, registrar::afterPropertiesSet);
        assertTrue(e.getMessage().contains("vendor-pack"), e.getMessage());
        assertTrue(e.getMessage().contains("vendor/"), e.getMessage());
    }

    @Test
    void aBadPackStillGetsTheToolSecurityFilterRegisteredBeforeTheRegistrarFails() throws Exception {
        // If the failed bean does not take the WAR down, the tool pages must not be left open: the filter
        // is registered first, and every request it then sees fails closed on the bad pack.
        installMalformedPack();
        ServletContext servletContext = mock(ServletContext.class);
        FilterRegistration.Dynamic registration = mock(FilterRegistration.Dynamic.class);
        when(servletContext.addFilter(eq("consoleToolSecurity"), any(Filter.class))).thenReturn(registration);
        ConsoleSecurityRegistrar registrar = registrarOn(servletContext, "IN_MEMORY");

        IllegalStateException e = assertThrows(IllegalStateException.class, registrar::afterPropertiesSet);
        assertTrue(e.getMessage().contains("vendor-pack"), e.getMessage());

        ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
        verify(servletContext).addFilter(eq("consoleToolSecurity"), filter.capture());
        verify(registration).addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/*");

        FilterChain chain = mock(FilterChain.class);
        assertThrows(IllegalStateException.class,
                () -> filter.getValue().doFilter(requestFor("/tools/some-tool"), mock(HttpServletResponse.class), chain),
                "a tool request must fail (a 500), not pass through unauthenticated");
        verifyNoInteractions(chain);
    }

    @Test
    void aBadPackFailsTheRegistrarWhenTheConsoleNeedsNoAuthenticationToo() {
        installMalformedPack();
        ServletContext servletContext = mock(ServletContext.class);
        ConsoleSecurityRegistrar registrar = registrarOn(servletContext, "NONE");

        assertThrows(IllegalStateException.class, registrar::afterPropertiesSet);
        verify(servletContext, never()).addFilter(any(String.class), any(Filter.class));
    }

    @Test
    void aBadPackFailsTheRegistrarOnAConfigurationReloadToo() {
        installMalformedPack();
        ServletContext servletContext = mock(ServletContext.class);
        when(servletContext.getFilterRegistration("consoleToolSecurity")).thenReturn(mock(FilterRegistration.class));
        ConsoleSecurityRegistrar registrar = registrarOn(servletContext, "IN_MEMORY");

        assertThrows(IllegalStateException.class, registrar::afterPropertiesSet);
        verify(servletContext, never()).addFilter(any(String.class), any(Filter.class));
    }

    @Test
    void aWellFormedPackRegistersTheFilterAndLetsTheRegistrarStart() {
        ServletContext servletContext = mock(ServletContext.class);
        FilterRegistration.Dynamic registration = mock(FilterRegistration.Dynamic.class);
        when(servletContext.addFilter(eq("consoleToolSecurity"), any(Filter.class))).thenReturn(registration);
        ConsoleSecurityRegistrar registrar = registrarOn(servletContext, "IN_MEMORY");

        assertDoesNotThrow(registrar::afterPropertiesSet);
        verify(servletContext).addFilter(eq("consoleToolSecurity"), any(Filter.class));
        verify(registration).addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), false, "/*");
    }

    @Test
    void aWellFormedPackLetsTheRegistrarStart() {
        ConsoleSecurityRegistrar registrar = new ConsoleSecurityRegistrar();
        registrar.setApplicationContext(mock(ApplicationContext.class));

        assertDoesNotThrow(registrar::afterPropertiesSet);
    }

    /** A pack whose Frank!Framework-owned path lacks the trailing slash: the registry refuses to resolve it. */
    private static void installMalformedPack() {
        PackDescriptor pack = mock(PackDescriptor.class);
        when(pack.id()).thenReturn("vendor-pack");
        when(pack.frankOwnedPaths()).thenReturn(List.of("vendor/"));
        PackRegistryTestSupport.overrideCandidates(List.of(pack));
    }

    /** A web context with a parent whose environment declares the console authentication type. */
    private static ConsoleSecurityRegistrar registrarOn(ServletContext servletContext, String consoleAuthType) {
        WebApplicationContext context = mock(WebApplicationContext.class);
        ApplicationContext parent = mock(ApplicationContext.class);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("application.security.console.authentication.type", consoleAuthType)));
        when(context.getServletContext()).thenReturn(servletContext);
        when(context.getParent()).thenReturn(parent);
        when(parent.getEnvironment()).thenReturn(environment);
        ConsoleSecurityRegistrar registrar = new ConsoleSecurityRegistrar();
        registrar.setApplicationContext(context);
        return registrar;
    }

    private static HttpServletRequest requestFor(String contextRelativePath) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContextPath()).thenReturn("/viscolink");
        when(request.getRequestURI()).thenReturn("/viscolink" + contextRelativePath);
        return request;
    }
}
