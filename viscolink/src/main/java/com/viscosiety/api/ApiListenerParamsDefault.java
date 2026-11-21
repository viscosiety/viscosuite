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

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;

import org.frankframework.http.rest.ApiListener;

/**
 * Makes {@code allowAllParams} default to {@code false}: an ApiListener (or subclass, such as the
 * tunnel-only listener) that sets neither {@code allowAllParams} nor {@code allowedParameters} copies
 * no request parameter into the session. The Frank!Framework (10.3) copies all of them then.
 *
 * <p>Listeners are beans of their Adapter's own Spring context, a child of the Configuration's context,
 * a child of the application context this bean is registered in. So on any context that is not yet
 * refreshed (a Configuration, then each Adapter) this processor adds itself, and on a new listener it
 * sets the default. Spring creates the listener before the digester applies its XML attributes, so an
 * attribute in the configuration always wins.
 *
 * <p>Remove once the Frank!Framework defaults {@code allowAllParams} to false.
 */
public class ApiListenerParamsDefault implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName) {
        if (bean instanceof ApiListener listener) {
            if (listener.getAllowAllParams() == null) {
                listener.setAllowAllParams(false);
            }
        } else if (bean instanceof ConfigurableApplicationContext child && !child.isActive()) {
            child.addBeanFactoryPostProcessor(factory -> factory.addBeanPostProcessor(this));
        }
        return bean;
    }
}
