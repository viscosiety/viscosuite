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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationListener;

import org.frankframework.lifecycle.events.ApplicationMessageEvent;
import org.frankframework.lifecycle.events.ConfigurationMessageEvent;
import org.frankframework.lifecycle.events.MessageEvent;
import org.frankframework.lifecycle.events.MessageEventLevel;

/**
 * Publishes Frank!Framework configuration/application lifecycle warnings and errors as
 * Kubernetes core/v1 Warning Events on the pod object. The pure event-to-Event mapping is kept
 * separate from the fabric8 client I/O so it stays unit-testable without a cluster.
 *
 * <p>Registered as a bean in {@code IbisApplicationContext} (via
 * {@code ViscoLinkModule.getSpringConfigurationFiles()}), the tier that receives both
 * {@link ConfigurationMessageEvent} (propagated up from each Configuration child context) and
 * {@link ApplicationMessageEvent} (published by IbisContext at this tier). It auto-detects
 * in-cluster API access at construction and silently no-ops off-cluster (docker, LOC).
 */
public class LifecycleEventK8sPublisher
		implements ApplicationListener<MessageEvent<?>>, DisposableBean {

	static final String REASON_ABORTED = "ConfigurationAborted";
	static final String REASON_ERROR = "ConfigurationError";
	static final String REASON_WARNING = "ConfigurationWarning";

	private static final Logger log = LogManager.getLogger(LifecycleEventK8sPublisher.class);
	private static final int TIMEOUT_MILLIS = 3_000;
	private static final String DEFAULT_NAMESPACE = "default";

	private static final DateTimeFormatter K8S_TIME =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

	private final String podName;
	private final String namespace;
	private final KubernetesClient client; // null => disabled (off-cluster), all calls no-op

	/** Spring constructor: resolve pod identity and auto-detect in-cluster API access. */
	public LifecycleEventK8sPublisher() {
		this.podName = resolvePodName();
		this.client = buildInClusterClientOrNull();
		this.namespace = client == null ? DEFAULT_NAMESPACE
				: Optional.ofNullable(client.getNamespace()).orElse(DEFAULT_NAMESPACE);
		if (client == null) {
			log.info("Kubernetes lifecycle-event publishing disabled: no in-cluster API access");
		} else {
			log.info("Kubernetes lifecycle-event publishing enabled for pod [{}] in namespace [{}]",
					podName, namespace);
		}
	}

	/** Test/seam constructor. */
	LifecycleEventK8sPublisher(KubernetesClient client, String podName, String namespace) {
		this.client = client;
		this.podName = podName;
		this.namespace = namespace;
	}

	@Override
	public void onApplicationEvent(@NonNull MessageEvent<?> event) {
		if (client == null) {
			return;
		}
		Event k8sEvent = toEvent(event, podName, namespace);
		if (k8sEvent == null) {
			return;
		}
		try {
			client.v1().events().inNamespace(namespace).resource(k8sEvent).create();
		} catch (Exception e) {
			// Emitting an Event must never disturb the configuration lifecycle.
			log.warn("Failed to publish Kubernetes lifecycle Event [{}]: {}",
					k8sEvent.getReason(), e.toString());
		}
	}

	@Override
	public void destroy() {
		if (client != null) {
			client.close();
		}
	}

	static String resolvePodName() {
		String name = System.getenv("POD_NAME");
		if (StringUtils.isBlank(name)) {
			name = System.getenv("HOSTNAME");
		}
		return StringUtils.isBlank(name) ? "unknown" : name;
	}

	private static KubernetesClient buildInClusterClientOrNull() {
		try {
			KubernetesClient c = new KubernetesClientBuilder()
					.editOrNewConfig()
						.withConnectionTimeout(TIMEOUT_MILLIS)
						.withRequestTimeout(TIMEOUT_MILLIS)
						.withRequestRetryBackoffLimit(0)
					.endConfig()
					.build();
			c.getKubernetesVersion(); // probe: throws off-cluster
			return c;
		} catch (Exception e) {
			log.info("No in-cluster Kubernetes API access ({}); lifecycle-event publishing stays disabled",
					e.toString());
			return null;
		}
	}

	static boolean isApplicable(MessageEvent<?> event) {
		boolean rightType = event instanceof ConfigurationMessageEvent
				|| event instanceof ApplicationMessageEvent;
		if (!rightType) {
			return false;
		}
		MessageEventLevel level = event.getLevel();
		return level == MessageEventLevel.WARN || level == MessageEventLevel.ERROR;
	}

	static String reasonFor(MessageEvent<?> event) {
		String message = event.getMessage();
		if (message != null && message.contains("aborted starting")) {
			return REASON_ABORTED;
		}
		if (event.getLevel() == MessageEventLevel.ERROR) {
			return REASON_ERROR;
		}
		return REASON_WARNING;
	}

	static Event toEvent(MessageEvent<?> event, String podName, String namespace) {
		if (!isApplicable(event)) {
			return null;
		}
		String time = K8S_TIME.format(Instant.ofEpochMilli(event.getTimestamp()));
		return new EventBuilder()
				.withNewMetadata()
					.withGenerateName("viscolink-cfg-")
					.withNamespace(namespace)
				.endMetadata()
				.withType("Warning")
				.withReason(reasonFor(event))
				.withMessage(event.getMessage())
				.withNewInvolvedObject()
					.withKind("Pod")
					.withName(podName)
					.withNamespace(namespace)
				.endInvolvedObject()
				.withNewSource()
					.withComponent("viscolink")
					.withHost(podName)
				.endSource()
				.withReportingComponent("viscolink")
				.withReportingInstance(podName)
				.withFirstTimestamp(time)
				.withLastTimestamp(time)
				.withCount(1)
				.build();
	}
}
