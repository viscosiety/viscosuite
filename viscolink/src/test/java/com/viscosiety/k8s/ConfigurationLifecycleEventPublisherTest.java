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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

import org.frankframework.configuration.Configuration;
import org.frankframework.lifecycle.events.ConfigurationMessageEvent;
import org.frankframework.lifecycle.events.MessageEventLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventList;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.V1APIGroupDSL;

/**
 * Uses REAL {@link ConfigurationMessageEvent}s (only the {@link Configuration} is mocked): the
 * event prepends {@code Configuration [<name>][<version>]} to its text, so {@code getMessage()}
 * never returns the bare text the Frank!Framework passes in.
 */
class ConfigurationLifecycleEventPublisherTest {

	private static Configuration configuration(String name, String version) {
		Configuration configuration = mock(Configuration.class);
		when(configuration.getName()).thenReturn(name);
		when(configuration.getVersion()).thenReturn(version);
		return configuration;
	}

	private static ConfigurationMessageEvent event(String configName, String version, String text, MessageEventLevel level) {
		return new ConfigurationMessageEvent(configuration(configName, version), text, level);
	}

	@Test
	void theRealEventCarriesThePrefixInItsMessage() {
		// The fact the first cut missed: getMessage() is prefix + text, not the bare text.
		assertEquals("Configuration [tenant] startup in 12 ms", event("tenant", null, "startup in 12 ms", MessageEventLevel.INFO).getMessage());
		assertEquals("Configuration [tenant] [main] startup in 12 ms", event("tenant", "main", "startup in 12 ms", MessageEventLevel.INFO).getMessage());
	}

	@Test
	void startupBecomesConfigurationStarted() {
		for (String version : new String[] {null, "main", "1.0"}) {
			Event e = ConfigurationLifecycleEventPublisher.toEvent(event("tenant", version, "startup in 1234 ms", MessageEventLevel.INFO), "pod-1", "ns");
			assertNotNull(e, "version " + version);
			assertEquals("Normal", e.getType());
			assertEquals("ConfigurationStarted", e.getReason());
			assertEquals("Configuration [tenant] started in 1234 ms", e.getMessage());
			assertEquals("pod-1", e.getInvolvedObject().getName());
			assertEquals("Pod", e.getInvolvedObject().getKind());
			assertEquals("viscolink", e.getReportingComponent());
		}
	}

	@Test
	void configuredWithoutAutoStartupAlsoCountsAsStarted() {
		for (String version : new String[] {null, "main"}) {
			Event e = ConfigurationLifecycleEventPublisher.toEvent(event("Orders", version, "configured in 80 ms", MessageEventLevel.INFO), "pod-1", "ns");
			assertNotNull(e, "version " + version);
			assertEquals("ConfigurationStarted", e.getReason());
			assertEquals("Configuration [Orders] started in 80 ms", e.getMessage());
		}
	}

	@Test
	void closedBecomesConfigurationClosed() {
		for (String version : new String[] {null, "main"}) {
			Event e = ConfigurationLifecycleEventPublisher.toEvent(event("tenant", version, "closed", MessageEventLevel.INFO), "pod-1", "ns");
			assertNotNull(e, "version " + version);
			assertEquals("ConfigurationClosed", e.getReason());
			assertEquals("Configuration [tenant] closed", e.getMessage());
		}
	}

	@Test
	void abortedStartingBecomesStartFailedAsNormal() {
		for (String version : new String[] {null, "1.0"}) {
			Event e = ConfigurationLifecycleEventPublisher.toEvent(event("tenant", version, "aborted starting; missing alias", MessageEventLevel.WARN), "pod-1", "ns");
			assertNotNull(e, "version " + version);
			assertEquals("Normal", e.getType());
			assertEquals("ConfigurationStartFailed", e.getReason());
			assertEquals("Configuration [tenant] failed to start", e.getMessage());
		}
	}

	@Test
	void everythingElseIsIgnored() {
		assertNull(ConfigurationLifecycleEventPublisher.toEvent(event("tenant", null, "name [a] does not match XML name attribute [b]", MessageEventLevel.WARN), "p", "ns"));
		assertNull(ConfigurationLifecycleEventPublisher.toEvent(event("tenant", "main", "something else", MessageEventLevel.INFO), "p", "ns"));
		assertNull(ConfigurationLifecycleEventPublisher.toEvent(event("tenant", null, "startup in 5 ms", MessageEventLevel.ERROR), "p", "ns"));
		assertNull(ConfigurationLifecycleEventPublisher.toEvent(event("tenant", null, "closed", MessageEventLevel.WARN), "p", "ns"));
		assertNull(ConfigurationLifecycleEventPublisher.toEvent(event("tenant", null, "aborted starting; boom", MessageEventLevel.INFO), "p", "ns"));
	}

	@Test
	void aConfigurationNamedLikeAKeywordDoesNotFakeALifecycleMessage() {
		assertNull(ConfigurationLifecycleEventPublisher.toEvent(event("closed", null, "something else", MessageEventLevel.INFO), "p", "ns"));
		assertNull(ConfigurationLifecycleEventPublisher.toEvent(event("closed", "main", "something else", MessageEventLevel.INFO), "p", "ns"));
		assertNull(ConfigurationLifecycleEventPublisher.toEvent(event("aborted starting", null, "name [a] does not match XML name attribute [b]", MessageEventLevel.WARN), "p", "ns"));
		assertNull(ConfigurationLifecycleEventPublisher.toEvent(event("startup in 5 ms", null, "something else", MessageEventLevel.INFO), "p", "ns"));
		// and the name is still carried verbatim when the message really is a lifecycle one
		Event e = ConfigurationLifecycleEventPublisher.toEvent(event("closed", null, "closed", MessageEventLevel.INFO), "p", "ns");
		assertEquals("ConfigurationClosed", e.getReason());
		assertEquals("Configuration [closed] closed", e.getMessage());
	}

	/** A mocked client whose events().inNamespace("ns").resource(event) hands out {@code resource}. */
	@SuppressWarnings("unchecked")
	private static KubernetesClient clientCreatingWith(Resource<Event> resource, NonNamespaceOperation<Event, EventList, Resource<Event>>[] namespaced) {
		KubernetesClient client = mock(KubernetesClient.class);
		V1APIGroupDSL v1 = mock(V1APIGroupDSL.class);
		MixedOperation<Event, EventList, Resource<Event>> events = mock(MixedOperation.class);
		NonNamespaceOperation<Event, EventList, Resource<Event>> inNamespace = mock(NonNamespaceOperation.class);
		when(client.v1()).thenReturn(v1);
		when(v1.events()).thenReturn(events);
		when(events.inNamespace("ns")).thenReturn(inNamespace);
		when(inNamespace.resource(any(Event.class))).thenReturn(resource);
		namespaced[0] = inNamespace;
		return client;
	}

	@Test
	void disabledPublisherNeverCallsTheCluster() {
		ConfigurationLifecycleEventPublisher publisher = new ConfigurationLifecycleEventPublisher(null, "ns", "pod-1");
		publisher.onApplicationEvent(event("tenant", null, "startup in 1 ms", MessageEventLevel.INFO)); // no NPE
	}

	@Test
	@SuppressWarnings("unchecked")
	void aRealStartupEventReachesTheClusterAsOneCreate() {
		Resource<Event> resource = mock(Resource.class);
		NonNamespaceOperation<Event, EventList, Resource<Event>>[] namespaced = new NonNamespaceOperation[1];
		KubernetesClient client = clientCreatingWith(resource, namespaced);
		ConfigurationLifecycleEventPublisher publisher = new ConfigurationLifecycleEventPublisher(client, "ns", "pod-1");

		publisher.onApplicationEvent(event("tenant", "main", "startup in 1234 ms", MessageEventLevel.INFO));

		ArgumentCaptor<Event> created = ArgumentCaptor.forClass(Event.class);
		verify(namespaced[0]).resource(created.capture());
		verify(resource).create();
		assertEquals("ConfigurationStarted", created.getValue().getReason());
		assertEquals("Configuration [tenant] started in 1234 ms", created.getValue().getMessage());
		assertEquals("ns", created.getValue().getMetadata().getNamespace());
		assertEquals("pod-1", created.getValue().getInvolvedObject().getName());
	}

	@Test
	@SuppressWarnings("unchecked")
	void anIgnoredEventNeverCreatesAnything() {
		Resource<Event> resource = mock(Resource.class);
		ConfigurationLifecycleEventPublisher publisher = new ConfigurationLifecycleEventPublisher(
				clientCreatingWith(resource, new NonNamespaceOperation[1]), "ns", "pod-1");

		publisher.onApplicationEvent(event("tenant", "main", "something else", MessageEventLevel.INFO));

		verify(resource, never()).create();
	}

	@Test
	@SuppressWarnings("unchecked")
	void aFailingCreateNeverEscapesTheListener() {
		Resource<Event> resource = mock(Resource.class);
		when(resource.create()).thenThrow(new KubernetesClientException("boom"));
		ConfigurationLifecycleEventPublisher publisher = new ConfigurationLifecycleEventPublisher(
				clientCreatingWith(resource, new NonNamespaceOperation[1]), "ns", "pod-1");

		assertDoesNotThrow(() -> publisher.onApplicationEvent(event("tenant", null, "closed", MessageEventLevel.INFO)));
		verify(resource).create();
	}

	@Test
	void springFileCreatesTheBeanThroughTheEnvironmentConstructor() {
		try (GenericApplicationContext context = new GenericApplicationContext()) {
			context.getEnvironment().getPropertySources()
					.addFirst(new MapPropertySource("test", Map.of(ConfigurationLifecycleEventPublisher.ENABLED_KEY, "false")));
			new XmlBeanDefinitionReader(context).loadBeanDefinitions(new ClassPathResource("springLifecycleEvents.xml"));
			context.refresh();
			assertNotNull(context.getBean(ConfigurationLifecycleEventPublisher.class));
		}
	}

	@Test
	void theKillSwitchBuildsNoClient() {
		GenericApplicationContext context = new GenericApplicationContext();
		context.getEnvironment().getPropertySources()
				.addFirst(new MapPropertySource("test", Map.of(ConfigurationLifecycleEventPublisher.ENABLED_KEY, "false")));
		assertFalse(new ConfigurationLifecycleEventPublisher(context.getEnvironment()).isPublishing());
	}

	@Test
	void aMalformedEventNeverEscapesTheListener() {
		ConfigurationLifecycleEventPublisher publisher = new ConfigurationLifecycleEventPublisher(mock(KubernetesClient.class), "ns", "pod-1");
		ConfigurationMessageEvent noSource = mock(ConfigurationMessageEvent.class);
		when(noSource.getMessage()).thenReturn("startup in 1 ms");
		when(noSource.getLevel()).thenReturn(MessageEventLevel.INFO);
		assertDoesNotThrow(() -> publisher.onApplicationEvent(noSource));

		ConfigurationMessageEvent noTime = mock(ConfigurationMessageEvent.class);
		Configuration source = configuration("tenant", null);
		when(noTime.getSource()).thenReturn(source);
		when(noTime.getMessage()).thenReturn("Configuration [tenant] startup in 1 ms");
		when(noTime.getLevel()).thenReturn(MessageEventLevel.INFO);
		when(noTime.getEventTime()).thenReturn(null);
		assertDoesNotThrow(() -> publisher.onApplicationEvent(noTime));
	}

	// ---- in-cluster decision and client construction ----

	@Test
	void clusterMarkersAreTheServiceHostOrAReadableTokenFile(@TempDir Path dir) throws IOException {
		Path missing = dir.resolve("absent");
		Path token = Files.writeString(dir.resolve("token"), "t");
		assertTrue(ConfigurationLifecycleEventPublisher.inCluster("10.0.0.1", missing));
		assertTrue(ConfigurationLifecycleEventPublisher.inCluster(null, token));
		assertTrue(ConfigurationLifecycleEventPublisher.inCluster("", token));
		assertFalse(ConfigurationLifecycleEventPublisher.inCluster(null, missing));
		assertFalse(ConfigurationLifecycleEventPublisher.inCluster("  ", missing));
	}

	@Test
	void withoutClusterMarkersNoClientIsBuilt(@TempDir Path dir) {
		Supplier<KubernetesClient> neverCalled = () -> {
			throw new AssertionError("a client must not be built off-cluster");
		};
		assertNull(ConfigurationLifecycleEventPublisher.createClient(null, dir.resolve("absent"), neverCalled));
	}

	@Test
	void inClusterTheClientIsKeptEvenWhenTheProbeFails() {
		KubernetesClient client = mock(KubernetesClient.class);
		when(client.getKubernetesVersion()).thenThrow(new KubernetesClientException("API not ready yet"));
		assertSame(client, ConfigurationLifecycleEventPublisher.createClient("10.0.0.1", Path.of("/nonexistent"), () -> client));
		verify(client).getKubernetesVersion();
	}

	@Test
	void inClusterAClientThatCannotBeBuiltGivesNull() {
		Supplier<KubernetesClient> broken = () -> {
			throw new IllegalStateException("bad config");
		};
		assertNull(ConfigurationLifecycleEventPublisher.createClient("10.0.0.1", Path.of("/nonexistent"), broken));
	}

	@Test
	void theClientDoesNotRetryAndIsBounded() {
		Config config = ConfigurationLifecycleEventPublisher.clientConfig();
		assertEquals(0, config.getRequestRetryBackoffLimit(), "fabric8 retries 10 times by default: ~20 s per call when the API is unreachable");
		assertEquals(3_000, config.getRequestTimeout());
		assertEquals(3_000, config.getConnectionTimeout());
	}
}
