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


package com.viscosiety.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import org.frankframework.core.Adapter;
import org.frankframework.http.rest.ApiListener;

import com.viscosiety.tunnel.TunnelApiListener;

class ApiListenerParamsDefaultTest {

    /** A Configuration context and, inside it, an Adapter context, both refreshed the way the framework does. */
    private static GenericApplicationContext adapterContext() {
        GenericApplicationContext application = new GenericApplicationContext();
        application.registerBean(ApiListenerParamsDefault.class);
        application.refresh();
        GenericApplicationContext configuration = application.getAutowireCapableBeanFactory()
                .createBean(GenericApplicationContext.class);
        configuration.refresh();
        GenericApplicationContext adapter = configuration.getAutowireCapableBeanFactory()
                .createBean(GenericApplicationContext.class);
        adapter.refresh();
        return adapter;
    }

    /** How the digester creates a listener: through Spring, before any XML attribute is set. */
    private static <T> T create(Class<T> type) {
        return adapterContext().getAutowireCapableBeanFactory().createBean(type);
    }

    @Test
    void aListenerThatSaysNothingCopiesNoParameters() {
        ApiListener listener = create(ApiListener.class);
        assertEquals(Boolean.FALSE, listener.getAllowAllParams());
        assertFalse(listener.isParameterAllowed("station"));
    }

    @Test
    void allowAllParamsTrueSetAfterCreationStillWins() {
        ApiListener listener = create(ApiListener.class);
        listener.setAllowAllParams(true); // allowAllParams="true" in the configuration
        assertTrue(listener.isParameterAllowed("station"));
    }

    @Test
    void anAllowedParametersListStillWorks() {
        ApiListener listener = create(ApiListener.class);
        listener.setAllowedParameters("station"); // allowedParameters="station" in the configuration
        assertTrue(listener.isParameterAllowed("station"));
        assertFalse(listener.isParameterAllowed("other"));
    }

    @Test
    void theTunnelOnlyListenerGetsTheDefaultToo() {
        assertEquals(Boolean.FALSE, create(TunnelApiListener.class).getAllowAllParams());
    }

    @Test
    void otherBeansAreLeftAlone() {
        Object bean = new Object();
        assertSame(bean, new ApiListenerParamsDefault().postProcessBeforeInitialization(bean, "x"));
    }

    @Test
    void theFrameworkStillLeavesAllowAllParamsUnsetOnANewListener() {
        // Guards a framework bump: the default is only set on a listener whose allowAllParams is still null.
        assertNull(new ApiListener().getAllowAllParams());
    }

    @Test
    void theFrameworkStillMakesEachAdapterItsOwnContext() {
        // Guards a framework bump: listeners are reached by passing this processor into child contexts.
        assertTrue(ConfigurableApplicationContext.class.isAssignableFrom(Adapter.class));
    }
}
