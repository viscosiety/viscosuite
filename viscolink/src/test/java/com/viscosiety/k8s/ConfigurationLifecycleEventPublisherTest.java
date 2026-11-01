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
package com.viscosiety.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.frankframework.configuration.Configuration;
import org.frankframework.lifecycle.events.ConfigurationMessageEvent;
import org.frankframework.lifecycle.events.MessageEventLevel;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;

import io.fabric8.kubernetes.api.model.Event;

class ConfigurationLifecycleEventPublisherTest {

	private static ConfigurationMessageEvent event(String configName, String message, MessageEventLevel level) {
		Configuration configuration = mock(Configuration.class);
		when(configuration.getName()).thenReturn(configName);
		ConfigurationMessageEvent event = mock(ConfigurationMessageEvent.class);
		when(event.getSource()).thenReturn(configuration);
		when(event.getMessage()).thenReturn(message);
		when(event.getLevel()).thenReturn(level);
		when(event.getEventTime()).thenReturn(Instant.parse("2026-11-01T10:00:00Z"));
		return event;
	}

	@Test
	void startupBecomesConfigurationStarted() {
		Event e = ConfigurationLifecycleEventPublisher.toEvent(event("tenant", "startup in 1234 ms", MessageEventLevel.INFO), "pod-1", "ns");
		assertEquals("Normal", e.getType());
		assertEquals("ConfigurationStarted", e.getReason());
		assertEquals("Configuration [tenant] started in 1234 ms", e.getMessage());
		assertEquals("pod-1", e.getInvolvedObject().getName());
		assertEquals("Pod", e.getInvolvedObject().getKind());
		assertEquals("viscolink", e.getReportingComponent());
	}

	@Test
	void configuredWithoutAutoStartupAlsoCountsAsStarted() {
		Event e = ConfigurationLifecycleEventPublisher.toEvent(event("Orders", "configured in 80 ms", MessageEventLevel.INFO), "pod-1", "ns");
		assertEquals("ConfigurationStarted", e.getReason());
		assertEquals("Configuration [Orders] started in 80 ms", e.getMessage());
	}

	@Test
	void closedBecomesConfigurationClosed() {
		Event e = ConfigurationLifecycleEventPublisher.toEvent(event("tenant", "closed", MessageEventLevel.INFO), "pod-1", "ns");
		assertEquals("ConfigurationClosed", e.getReason());
		assertEquals("Configuration [tenant] closed", e.getMessage());
	}

	@Test
	void abortedStartingBecomesStartFailedAsNormal() {
		Event e = ConfigurationLifecycleEventPublisher.toEvent(event("tenant", "aborted starting; missing alias", MessageEventLevel.WARN), "pod-1", "ns");
		assertEquals("Normal", e.getType());
		assertEquals("ConfigurationStartFailed", e.getReason());
		assertEquals("Configuration [tenant] failed to start", e.getMessage());
	}

	@Test
	void everythingElseIsIgnored() {
		assertNull(ConfigurationLifecycleEventPublisher.toEvent(event("tenant", "name [a] does not match XML name attribute [b]", MessageEventLevel.WARN), "p", "ns"));
		assertNull(ConfigurationLifecycleEventPublisher.toEvent(event("tenant", "something else", MessageEventLevel.INFO), "p", "ns"));
		assertNull(ConfigurationLifecycleEventPublisher.toEvent(event("tenant", "startup in 5 ms", MessageEventLevel.ERROR), "p", "ns"));
	}

	@Test
	void disabledPublisherNeverCallsTheCluster() {
		ConfigurationLifecycleEventPublisher publisher = new ConfigurationLifecycleEventPublisher(null, "ns", "pod-1");
		publisher.onApplicationEvent(event("tenant", "startup in 1 ms", MessageEventLevel.INFO)); // no NPE
	}

	@Test
	void springFileCreatesTheBeanThroughTheEnvironmentConstructor() {
		try (GenericApplicationContext context = new GenericApplicationContext()) {
			context.getEnvironment().getSystemProperties().put(ConfigurationLifecycleEventPublisher.ENABLED_KEY, "false");
			new XmlBeanDefinitionReader(context).loadBeanDefinitions(new ClassPathResource("springLifecycleEvents.xml"));
			context.refresh();
			assertNotNull(context.getBean(ConfigurationLifecycleEventPublisher.class));
		}
	}
}
