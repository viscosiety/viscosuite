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
import org.springframework.messaging.Message;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
        // Custom headers (addHeader) land under the "meta-" prefix -- see AbstractMessage.setMetaHeader --
        // distinct from the transport-layer "action"/"topic" headers build() also sets directly.
        assertEquals(Action.RELOAD.name(), message.getHeaders().get(BusMessageUtils.HEADER_PREFIX + "action"));
        assertEquals(BusMessageUtils.ALL_CONFIGS_KEY,
                message.getHeaders().get(BusMessageUtils.HEADER_PREFIX + BusMessageUtils.HEADER_CONFIGURATION_NAME_KEY));
        verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
    }

    @Test
    void bindsRequestAttributesAroundBusCallAndRestoresAfter() throws Exception {
        // Regression guard for the M2M ScopeNotActiveException: FrankApiService needs the
        // session-scoped clientSession bean, which only resolves when RequestContextHolder
        // is bound -- something no filter does for this ServletManager-registered servlet.
        authenticateAs(REQUIRED_ROLE);
        givenConsoleContextWithFrankApiService();
        RequestContextHolder.resetRequestAttributes();

        doAnswer(invocation -> {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            assertNotNull(attrs, "request attributes must be bound while the bus call runs");
            assertSame(request, attrs.getRequest(), "bound attributes must wrap the servlet's own request");
            return null;
        }).when(frankApiService).callAsyncGateway(any());

        servlet.doPut(request, response);

        assertNull(RequestContextHolder.getRequestAttributes(),
                "attributes must not leak onto the container thread after the call");
        verify(frankApiService).callAsyncGateway(any());
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

        // Required role now resolves to the fail-closed UNCONFIGURED_ROLE fallback; a caller
        // presenting some real, different role must still be rejected.
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
