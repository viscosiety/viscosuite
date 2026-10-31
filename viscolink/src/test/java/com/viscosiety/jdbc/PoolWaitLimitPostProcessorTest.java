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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;

class PoolWaitLimitPostProcessorTest {

    private static final String POOLING = "org.frankframework.jdbc.factory.PoolingDataSourceFactory";
    private static final String NARAYANA = "org.frankframework.jta.narayana.NarayanaDataSourceFactory";

    /** Loads the framework file and ours in the order the framework uses, then runs our post-processor. */
    private static DefaultListableBeanFactory load(String frameworkFile) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(factory).loadBeanDefinitions(
                "classpath:" + frameworkFile, "classpath:springPoolWaitLimit.xml");
        factory.getBean(PoolWaitLimitPostProcessor.class).postProcessBeanFactory(factory);
        return factory;
    }

    @Test
    void swapsTheTomcatFactory() {
        DefaultListableBeanFactory factory = load("springTOMCAT.xml");
        assertEquals(WaitingPoolingDataSourceFactory.class.getName(),
                factory.getBeanDefinition("dataSourceFactory").getBeanClassName());
    }

    @Test
    void leavesTheNarayanaFactoryAlone() {
        DefaultListableBeanFactory factory = load("springTOMCATNARAYANA.xml");
        assertEquals(NARAYANA, factory.getBeanDefinition("dataSourceFactory").getBeanClassName());
    }

    @Test
    void leavesAnyOtherClassAlone() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("dataSourceFactory", new RootBeanDefinition(SubclassOfPooling.class));
        new PoolWaitLimitPostProcessor().postProcessBeanFactory(factory);
        assertEquals(SubclassOfPooling.class.getName(),
                factory.getBeanDefinition("dataSourceFactory").getBeanClassName());
    }

    @Test
    void doesNothingWithoutTheBean() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        new PoolWaitLimitPostProcessor().postProcessBeanFactory(factory);
        assertFalse(factory.containsBeanDefinition("dataSourceFactory"));
    }

    @Test
    void theFrameworkStillDeclaresPoolingDataSourceFactory() {
        // Guards a framework bump: if springTOMCAT.xml stops naming this class, the swap silently stops.
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(factory).loadBeanDefinitions("classpath:springTOMCAT.xml");
        assertEquals(POOLING, factory.getBeanDefinition("dataSourceFactory").getBeanClassName());
    }

    static class SubclassOfPooling extends org.frankframework.jdbc.factory.PoolingDataSourceFactory {
    }
}
