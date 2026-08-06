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

package com.viscosiety.viscorunner.k8s;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Server;
import org.apache.catalina.Service;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

/**
 * Tomcat {@link LifecycleListener}, registered on {@code <Server>} in {@code server.xml}, that emits a
 * Kubernetes core/v1 <b>Warning</b> Event on the pod when a web application context fails to start —
 * e.g. a WAR whose Spring context refresh aborts. It runs on Tomcat's shared classloader, so it
 * survives a single WAR's failure and can see every context — the class of failure the in-WAR
 * {@code LifecycleEventK8sPublisher} (viscolink) structurally cannot report.
 *
 * <p>On {@code BEFORE_START} it auto-detects in-cluster API access (fabric8 client + probe) and, when
 * present, installs a bounded root {@code java.util.logging} handler that captures Tomcat's own
 * {@code SEVERE} deploy-failure records (JULI logs the full cause chain there — the WAR's log4j2
 * {@code FATAL} is unreachable from this layer). On {@code AFTER_START} it walks the container tree
 * and, for each context in {@link LifecycleState#FAILED}, posts one Event carrying the captured cause.
 * Off-cluster (docker, LOC) it is a silent no-op.</p>
 */
public class ContextFailureEventPublisher implements LifecycleListener {

    private static final Logger log = Logger.getLogger(ContextFailureEventPublisher.class.getName());

    static final String REASON = "ContextStartFailed";
    private static final int TIMEOUT_MILLIS = 3_000;
    private static final String DEFAULT_NAMESPACE = "default";
    private static final DateTimeFormatter K8S_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private KubernetesClient client; // null => disabled (off-cluster)
    private String podName;
    private String namespace;
    private CauseCaptureHandler causeHandler;

    @Override
    public void lifecycleEvent(LifecycleEvent event) {
        if (!(event.getLifecycle() instanceof Server server)) {
            return;
        }
        try {
            switch (event.getType()) {
                case Lifecycle.BEFORE_START_EVENT -> onBeforeStart();
                case Lifecycle.AFTER_START_EVENT -> onAfterStart(server);
                case Lifecycle.AFTER_STOP_EVENT -> onAfterStop();
                default -> { /* ignore */ }
            }
        } catch (Exception e) {
            // Reporting a failure must never disturb Tomcat startup/shutdown.
            log.warning("ContextFailureEventPublisher: " + e);
        }
    }

    private void onBeforeStart() {
        this.podName = resolvePodName();
        this.client = buildInClusterClientOrNull();
        if (client == null) {
            log.info("ContextFailureEventPublisher: no in-cluster Kubernetes API access — disabled");
            return;
        }
        this.namespace = Optional.ofNullable(client.getNamespace()).orElse(DEFAULT_NAMESPACE);
        this.causeHandler = new CauseCaptureHandler();
        Logger.getLogger("").addHandler(causeHandler);
        log.info("ContextFailureEventPublisher: enabled for pod [" + podName + "] in namespace [" + namespace + "]");
    }

    private void onAfterStart(Server server) {
        if (client == null) {
            return;
        }
        try {
            for (Context ctx : failedContexts(server)) {
                String path = contextPath(ctx);
                String cause = causeHandler != null ? causeHandler.bestCauseFor(path) : null;
                emit(path, cause);
            }
        } finally {
            detachHandler();
        }
    }

    private void onAfterStop() {
        detachHandler();
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private void detachHandler() {
        if (causeHandler != null) {
            Logger.getLogger("").removeHandler(causeHandler);
            causeHandler = null;
        }
    }

    private void emit(String contextPath, String cause) {
        Event event = buildEvent(contextPath, cause, podName, namespace);
        try {
            client.v1().events().inNamespace(namespace).resource(event).create();
            log.info("ContextFailureEventPublisher: published ContextStartFailed Event for context [" + contextPath + "]");
        } catch (Exception e) {
            log.warning("ContextFailureEventPublisher: failed to publish Event for [" + contextPath + "]: " + e);
        }
    }

    // ── Pure helpers (unit-tested) ──────────────────────────────────────────────────────────────

    /** All contexts, across every Service/Host, currently in {@link LifecycleState#FAILED}. */
    static List<Context> failedContexts(Server server) {
        List<Context> failed = new ArrayList<>();
        for (Service service : server.findServices()) {
            Container engine = service.getContainer();
            if (!(engine instanceof Engine)) {
                continue;
            }
            for (Container host : engine.findChildren()) {
                for (Container child : host.findChildren()) {
                    if (child instanceof Context ctx && ctx.getState() == LifecycleState.FAILED) {
                        failed.add(ctx);
                    }
                }
            }
        }
        return failed;
    }

    static Event buildEvent(String contextPath, String cause, String podName, String namespace) {
        String message = "context [" + contextPath + "] failed to start"
                + ((cause != null && !cause.isBlank()) ? "; " + cause : "");
        String time = K8S_TIME.format(Instant.now());
        return new EventBuilder()
                .withNewMetadata()
                    .withGenerateName("viscorunner-ctx-")
                    .withNamespace(namespace)
                .endMetadata()
                .withType("Warning")
                .withReason(REASON)
                .withMessage(message)
                .withNewInvolvedObject()
                    .withKind("Pod")
                    .withName(podName)
                    .withNamespace(namespace)
                .endInvolvedObject()
                .withNewSource()
                    .withComponent("viscorunner")
                    .withHost(podName)
                .endSource()
                .withReportingComponent("viscorunner")
                .withReportingInstance(podName)
                .withFirstTimestamp(time)
                .withLastTimestamp(time)
                .withCount(1)
                .build();
    }

    private static String contextPath(Context ctx) {
        String path = ctx.getPath();
        return (path == null || path.isEmpty()) ? ctx.getName() : path;
    }

    static String resolvePodName() {
        String name = System.getenv("POD_NAME");
        if ((name == null || name.isBlank())) {
            name = System.getenv("HOSTNAME");
        }
        return (name == null || name.isBlank()) ? "unknown" : name;
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
            return null;
        }
    }

    /**
     * Bounded root JUL handler retaining the most recent {@code SEVERE} records that carry a
     * {@link Throwable}. Tomcat's {@code HostConfig} logs context-deploy failures at {@code SEVERE}
     * with the full cause chain on this (shared) classloader.
     */
    static final class CauseCaptureHandler extends Handler {
        private static final int CAPACITY = 32;
        private final Deque<LogRecord> records = new ArrayDeque<>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record == null || record.getLevel().intValue() < Level.SEVERE.intValue()
                    || record.getThrown() == null) {
                return;
            }
            records.addLast(record);
            while (records.size() > CAPACITY) {
                records.removeFirst();
            }
        }

        /**
         * Best cause message for a failed context: prefer a captured record whose message names the
         * context (its path or {@code StandardContext[<path>]}); otherwise the most recent SEVERE.
         */
        synchronized String bestCauseFor(String contextPath) {
            LogRecord match = null;
            for (LogRecord r : records) {
                String msg = r.getMessage() == null ? "" : r.getMessage();
                if (msg.contains("[" + contextPath + "]") || msg.contains(contextPath)) {
                    match = r; // keep scanning — last (most recent) match wins
                }
            }
            if (match == null && !records.isEmpty()) {
                match = records.peekLast();
            }
            return match == null ? null : rootCauseMessage(match.getThrown());
        }

        @Override public void flush() { /* no-op */ }
        @Override public void close() { records.clear(); }
    }

    /** Deepest cause's "SimpleClassName: message", to keep the Event message concise. */
    static String rootCauseMessage(Throwable t) {
        if (t == null) {
            return null;
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        String type = root.getClass().getSimpleName();
        return (msg == null || msg.isBlank()) ? type : type + ": " + msg;
    }
}
