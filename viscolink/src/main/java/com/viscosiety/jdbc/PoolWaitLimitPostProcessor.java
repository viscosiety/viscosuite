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

package com.viscosiety.jdbc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import org.frankframework.jdbc.factory.PoolingDataSourceFactory;

/**
 * Gives the framework-built connection pools a wait limit: when bean {@code dataSourceFactory}
 * (declared by the framework's {@code springTOMCAT.xml}) is exactly {@link PoolingDataSourceFactory},
 * its class becomes {@link WaitingPoolingDataSourceFactory}. Any other class, such as the
 * Narayana factory (itself a subclass of PoolingDataSourceFactory, hence the name comparison),
 * is left alone. Runs before any bean is created: the module's Spring files are part of the same
 * context refresh as the framework's.
 */
public class PoolWaitLimitPostProcessor implements BeanFactoryPostProcessor {

    static final String DATA_SOURCE_FACTORY_BEAN = "dataSourceFactory";

    private static final Logger log = LogManager.getLogger(PoolWaitLimitPostProcessor.class);

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!beanFactory.containsBeanDefinition(DATA_SOURCE_FACTORY_BEAN)) {
            return;
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(DATA_SOURCE_FACTORY_BEAN);
        if (PoolingDataSourceFactory.class.getName().equals(definition.getBeanClassName())) {
            definition.setBeanClassName(WaitingPoolingDataSourceFactory.class.getName());
            log.info("connection pools get a wait limit ({})", WaitingPoolingDataSourceFactory.MAX_WAIT_PROPERTY);
        }
    }
}
