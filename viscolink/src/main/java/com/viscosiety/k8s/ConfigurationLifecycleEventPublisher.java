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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import org.frankframework.lifecycle.events.ConfigurationMessageEvent;
import org.frankframework.lifecycle.events.MessageEventLevel;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

/**
 * Publishes a configuration's lifecycle (started, closed, failed to start) as Kubernetes
 * <em>Normal</em> Events on the pod, so a platform can tell when an instance serves. The Frank!Framework's own
 * {@code KubernetesEventPublisher} only publishes WARN/ERROR; this adds the INFO lifecycle and a
 * name-carrying failed-start event (upstream's {@code ConfigurationAborted} has no configuration
 * name). Silent no-op without in-cluster access (decided from the service-account token / API host
 * markers, not from a probe); any failure is logged and never disturbs the lifecycle. Disable with {@code viscolink.kubernetes.lifecycle-events.enabled=false}.
 */
public class ConfigurationLifecycleEventPublisher implements ApplicationListener<ConfigurationMessageEvent>, DisposableBean {

    private static final Logger LOG = LogManager.getLogger(ConfigurationLifecycleEventPublisher.class);

    static final String ENABLED_KEY = "viscolink.kubernetes.lifecycle-events.enabled";
    static final String REASON_STARTED = "ConfigurationStarted";
    static final String REASON_CLOSED = "ConfigurationClosed";
    static final String REASON_START_FAILED = "ConfigurationStartFailed";

    // A ConfigurationMessageEvent's message is "Configuration [<name>] [<version>] <text>" (the version part only when
    // the configuration has one), so the lifecycle text is matched at the END of the message, behind the closing bracket.
    private static final Pattern STARTUP = Pattern.compile("\\] (?:startup|configured) in (\\d+) ms$");
    private static final String CLOSED_SUFFIX = "] closed";
    private static final String ABORTED_MARKER = "] aborted starting";
    private static final int TIMEOUT_MILLIS = 3_000;
    private static final Path SERVICE_ACCOUNT_TOKEN = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
    private static final DateTimeFormatter K8S_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final KubernetesClient client; // null => disabled
    private final String namespace;
    private final String podName;

    public ConfigurationLifecycleEventPublisher(Environment environment) {
        this(enabled(environment) ? buildInClusterClientOrNull() : null, null, resolvePodName(environment));
    }

    ConfigurationLifecycleEventPublisher(KubernetesClient client, String namespace, String podName) {
        this.client = client;
        this.namespace = namespace != null ? namespace
                : client == null ? "default" : Optional.ofNullable(client.getNamespace()).orElse("default");
        this.podName = podName;
        if (client == null) {
            LOG.info("configuration lifecycle events disabled");
        } else {
            LOG.info("configuration lifecycle events enabled for pod [{}]", podName);
        }
    }

    private static boolean enabled(Environment environment) {
        return !"false".equalsIgnoreCase(environment.getProperty(ENABLED_KEY, "true"));
    }

    private static String resolvePodName(Environment environment) {
        String pod = environment.getProperty("POD_NAME");
        if (pod == null || pod.isBlank()) {
            pod = environment.getProperty("HOSTNAME");
        }
        return pod == null || pod.isBlank() ? "unknown" : pod;
    }

    private static KubernetesClient buildInClusterClientOrNull() {
        return createClient(System.getenv("KUBERNETES_SERVICE_HOST"), SERVICE_ACCOUNT_TOKEN,
                () -> new KubernetesClientBuilder().withConfig(clientConfig()).build());
    }

    /**
     * In a cluster = the pod has a service-account token or the API service host variable. Decided
     * from these markers, not from a probe: one failed probe at pod start must not switch publishing
     * off for the pod's whole life, and off-cluster no client is built (so a developer machine's
     * kubeconfig is never used).
     */
    static boolean inCluster(String serviceHost, Path tokenFile) {
        return (serviceHost != null && !serviceHost.isBlank()) || Files.isReadable(tokenFile);
    }

    /** The client, or null off-cluster or when it cannot be built. A failing probe keeps the client: each call is bounded and logged. */
    static KubernetesClient createClient(String serviceHost, Path tokenFile, Supplier<KubernetesClient> builder) {
        if (!inCluster(serviceHost, tokenFile)) {
            LOG.info("not running in a Kubernetes cluster; lifecycle events stay off");
            return null;
        }
        KubernetesClient client;
        try {
            client = builder.get();
        } catch (Exception e) {
            LOG.warn("cannot build a Kubernetes client; lifecycle events stay off: {}", e.toString());
            return null;
        }
        try {
            client.getKubernetesVersion();
        } catch (Exception e) {
            LOG.warn("Kubernetes API not reachable yet ({}); lifecycle events will be tried per event", e.toString());
        }
        return client;
    }

    /** Bounded and without retries: fabric8's default of 10 retries blocks ~20 s per call when the API is unreachable. */
    static Config clientConfig() {
        return new ConfigBuilder(Config.autoConfigure(null))
                .withConnectionTimeout(TIMEOUT_MILLIS)
                .withRequestTimeout(TIMEOUT_MILLIS)
                .withRequestRetryBackoffLimit(0)
                .build();
    }

    /** True when a client exists, i.e. events are being published. */
    boolean isPublishing() {
        return client != null;
    }

    @Override
    public void onApplicationEvent(@NonNull ConfigurationMessageEvent event) {
        if (client == null) {
            return;
        }
        String reason = null;
        try {
            Event kubernetesEvent = toEvent(event, podName, namespace);
            if (kubernetesEvent == null) {
                return;
            }
            reason = kubernetesEvent.getReason();
            client.v1().events().inNamespace(namespace).resource(kubernetesEvent).create();
        } catch (Exception e) {
            LOG.warn("could not publish Kubernetes event [{}]: {}", reason, e.toString());
        }
    }

    /** The Kubernetes event for a lifecycle message, or null for every other message. */
    static Event toEvent(ConfigurationMessageEvent event, String podName, String namespace) {
        String message = event.getMessage() == null ? "" : event.getMessage();
        String name = event.getSource().getName();
        MessageEventLevel level = event.getLevel();
        String reason;
        String text;
        Matcher startup = STARTUP.matcher(message);
        if (level == MessageEventLevel.INFO && startup.find()) {
            reason = REASON_STARTED;
            text = "Configuration [" + name + "] started in " + startup.group(1) + " ms";
        } else if (level == MessageEventLevel.INFO && message.endsWith(CLOSED_SUFFIX)) {
            reason = REASON_CLOSED;
            text = "Configuration [" + name + "] closed";
        } else if (level == MessageEventLevel.WARN && message.contains(ABORTED_MARKER)) {
            reason = REASON_START_FAILED;
            text = "Configuration [" + name + "] failed to start";
        } else {
            return null;
        }
        String time = K8S_TIME.format(event.getEventTime());
        return new EventBuilder()
                .withNewMetadata().withGenerateName("viscolink-").withNamespace(namespace).endMetadata()
                .withType("Normal")
                .withReason(reason)
                .withMessage(text)
                .withNewInvolvedObject().withKind("Pod").withName(podName).withNamespace(namespace).endInvolvedObject()
                .withNewSource().withComponent("viscolink").withHost(podName).endSource()
                .withReportingComponent("viscolink")
                .withReportingInstance(podName)
                .withFirstTimestamp(time)
                .withLastTimestamp(time)
                .withCount(1)
                .build();
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.close();
        }
    }
}
