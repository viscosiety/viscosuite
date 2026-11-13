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

package com.viscosiety.tunnel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumSet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;

import org.junit.jupiter.api.Test;
import org.springframework.web.context.WebApplicationContext;

class TunnelOnlyApiFilterRegistrarTest {

    private static TunnelOnlyApiFilterRegistrar registrarFor(ServletContext servletContext) {
        WebApplicationContext context = mock(WebApplicationContext.class);
        when(context.getServletContext()).thenReturn(servletContext);
        TunnelOnlyApiFilterRegistrar registrar = new TunnelOnlyApiFilterRegistrar();
        registrar.setApplicationContext(context);
        return registrar;
    }

    @Test
    void theFilterIsMappedToTheApiServletForRequestDispatchOnly() {
        ServletContext servletContext = mock(ServletContext.class);
        FilterRegistration.Dynamic registration = mock(FilterRegistration.Dynamic.class);
        when(servletContext.addFilter(eq("tunnelOnlyApi"), any(Filter.class))).thenReturn(registration);

        registrarFor(servletContext).afterPropertiesSet();

        verify(registration).addMappingForServletNames(EnumSet.of(DispatcherType.REQUEST), false, "ApiListenerServlet");
    }

    @Test
    void aReloadKeepsTheFilterOfTheFirstBoot() {
        ServletContext servletContext = mock(ServletContext.class);
        when(servletContext.getFilterRegistration("tunnelOnlyApi")).thenReturn(mock(FilterRegistration.class));

        registrarFor(servletContext).afterPropertiesSet();

        verify(servletContext, never()).addFilter(anyString(), any(Filter.class));
    }

    @Test
    void aFailedRegistrationDoesNotFailTheContextRefresh() {
        ServletContext servletContext = mock(ServletContext.class);
        when(servletContext.addFilter(anyString(), any(Filter.class))).thenThrow(new IllegalStateException("initialised"));

        assertDoesNotThrow(() -> registrarFor(servletContext).afterPropertiesSet());
    }
}
