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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Event;

import org.frankframework.lifecycle.events.ApplicationMessageEvent;
import org.frankframework.lifecycle.events.ConfigurationMessageEvent;
import org.frankframework.lifecycle.events.MessageEvent;
import org.frankframework.lifecycle.events.MessageEventLevel;
import org.junit.jupiter.api.Test;

class LifecycleEventK8sPublisherMappingTest {

	private static MessageEvent<?> event(Class<? extends MessageEvent> type, String message, MessageEventLevel level) {
		MessageEvent<?> e = mock(type);
		// getMessage()/getLevel() are non-final Lombok getters on MessageEvent; getTimestamp() is
		// final on Spring's ApplicationEvent and must not be stubbed (a mock returns 0, unused here).
		when(e.getMessage()).thenReturn(message);
		when(e.getLevel()).thenReturn(level);
		return e;
	}

	@Test
	void warnConfigAbortMapsToAbortedReasonAndWarningType() {
		MessageEvent<?> e = event(ConfigurationMessageEvent.class,
				"Configuration [tenantA] aborted starting; boom", MessageEventLevel.WARN);

		Event k8s = LifecycleEventK8sPublisher.toEvent(e, "viscolink-abc", "frank");

		assertEquals("Warning", k8s.getType());
		assertEquals("ConfigurationAborted", k8s.getReason());
		assertEquals("Configuration [tenantA] aborted starting; boom", k8s.getMessage());
		assertEquals("Pod", k8s.getInvolvedObject().getKind());
		assertEquals("viscolink-abc", k8s.getInvolvedObject().getName());
		assertEquals("frank", k8s.getInvolvedObject().getNamespace());
		assertEquals("frank", k8s.getMetadata().getNamespace());
	}

	@Test
	void errorApplicationEventMapsToErrorReason() {
		MessageEvent<?> e = event(ApplicationMessageEvent.class,
				"Application [frank] an exception occurred while loading configuration [tenantB]",
				MessageEventLevel.ERROR);

		Event k8s = LifecycleEventK8sPublisher.toEvent(e, "viscolink-abc", "frank");

		assertEquals("Warning", k8s.getType());
		assertEquals("ConfigurationError", k8s.getReason());
	}

	@Test
	void nonAbortWarnMapsToWarningReason() {
		MessageEvent<?> e = event(ConfigurationMessageEvent.class,
				"Configuration [tenantC] name does not match XML name attribute", MessageEventLevel.WARN);

		assertEquals("ConfigurationWarning", LifecycleEventK8sPublisher.toEvent(e, "p", "n").getReason());
	}

	@Test
	void infoLevelIsNotApplicableAndMapsToNull() {
		MessageEvent<?> e = event(ConfigurationMessageEvent.class,
				"Configuration [tenantD] started", MessageEventLevel.INFO);

		assertNull(LifecycleEventK8sPublisher.toEvent(e, "p", "n"));
	}
}
