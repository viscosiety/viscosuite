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

import java.util.List;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

import org.frankframework.lifecycle.events.ConfigurationMessageEvent;
import org.frankframework.lifecycle.events.MessageEvent;
import org.frankframework.lifecycle.events.MessageEventLevel;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@EnableKubernetesMockClient(crud = true)
class LifecycleEventK8sPublisherIoTest {

	static KubernetesClient client; // injected by the mock extension

	private static MessageEvent<?> configEvent(String message, MessageEventLevel level) {
		MessageEvent<?> e = Mockito.mock(ConfigurationMessageEvent.class);
		Mockito.when(e.getMessage()).thenReturn(message);
		Mockito.when(e.getLevel()).thenReturn(level);
		return e;
	}

	private List<Event> eventsIn(String ns) {
		return client.v1().events().inNamespace(ns).list().getItems();
	}

	@Test
	void warnEventIsPostedToTheCluster() {
		LifecycleEventK8sPublisher publisher =
				new LifecycleEventK8sPublisher(client, "viscolink-xyz", "frank");

		publisher.onApplicationEvent(configEvent("Configuration [t] aborted starting; boom", MessageEventLevel.WARN));

		List<Event> events = eventsIn("frank");
		assertEquals(1, events.size());
		assertEquals("ConfigurationAborted", events.get(0).getReason());
		assertEquals("Warning", events.get(0).getType());
	}

	@Test
	void infoEventIsNotPosted() {
		LifecycleEventK8sPublisher publisher =
				new LifecycleEventK8sPublisher(client, "viscolink-xyz", "frank2");

		publisher.onApplicationEvent(configEvent("Configuration [t] started", MessageEventLevel.INFO));

		assertEquals(0, eventsIn("frank2").size());
	}

	@Test
	void nullClientNoOps() {
		LifecycleEventK8sPublisher publisher =
				new LifecycleEventK8sPublisher(null, "viscolink-xyz", "frank3");

		// must not throw
		publisher.onApplicationEvent(configEvent("Configuration [t] aborted starting; x", MessageEventLevel.WARN));
	}
}
