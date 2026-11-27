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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.frankframework.lifecycle.ServletManager;
import org.frankframework.lifecycle.servlets.ServletConfiguration;

import com.viscosiety.api.ApiGroups;
import com.viscosiety.api.ApiGroupsTestSupport;

class ApiGroupServletRegistrarTest {

    @BeforeEach
    void reset() {
        ApiGroupsTestSupport.reset();
    }

    @AfterEach
    void clear() {
        System.clearProperty(ApiGroups.PROPERTY);
        ApiGroupsTestSupport.reset();
    }

    @Test
    void registersOneServletPerGroup() throws Exception {
        System.setProperty(ApiGroups.PROPERTY, "webhooks,public");
        ServletManager manager = mock(ServletManager.class);
        ApiGroupServletRegistrar registrar = new ApiGroupServletRegistrar(manager, servlet -> {
            ServletConfiguration config = mock(ServletConfiguration.class);
            when(config.getName()).thenReturn(servlet.getName());
            return config;
        });
        registrar.afterPropertiesSet();
        ArgumentCaptor<ServletConfiguration> captor = ArgumentCaptor.forClass(ServletConfiguration.class);
        verify(manager, times(2)).register(captor.capture());
        assertEquals(List.of("api-webhooks", "api-public"), captor.getAllValues().stream().map(ServletConfiguration::getName).toList());
    }

    @Test
    void noPropertyRegistersNothing() throws Exception {
        ServletManager manager = mock(ServletManager.class);
        new ApiGroupServletRegistrar(manager, servlet -> mock(ServletConfiguration.class)).afterPropertiesSet();
        verify(manager, never()).register(any());
    }
}
