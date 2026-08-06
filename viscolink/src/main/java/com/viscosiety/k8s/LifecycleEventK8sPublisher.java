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

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;

import org.frankframework.lifecycle.events.ApplicationMessageEvent;
import org.frankframework.lifecycle.events.ConfigurationMessageEvent;
import org.frankframework.lifecycle.events.MessageEvent;
import org.frankframework.lifecycle.events.MessageEventLevel;

/**
 * Publishes Frank!Framework configuration/application lifecycle warnings and errors as
 * Kubernetes core/v1 Warning Events on the pod object. Pure mapping lives here; the fabric8
 * client I/O and listener wiring are added on top (see the ApplicationListener implementation).
 */
public class LifecycleEventK8sPublisher {

	static final String REASON_ABORTED = "ConfigurationAborted";
	static final String REASON_ERROR = "ConfigurationError";
	static final String REASON_WARNING = "ConfigurationWarning";

	private static final DateTimeFormatter K8S_TIME =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

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
