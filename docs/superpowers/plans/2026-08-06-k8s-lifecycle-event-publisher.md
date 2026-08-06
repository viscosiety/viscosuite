# K8s Lifecycle Event Publisher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In viscoLink, turn Frank!Framework WARN/ERROR `ConfigurationMessageEvent` and `ApplicationMessageEvent` into Kubernetes core/v1 Warning Events on the pod, replacing log-scraping detection.

**Architecture:** One Spring `ApplicationListener<MessageEvent<?>>` bean (`com.viscosiety.k8s.LifecycleEventK8sPublisher`) registered in `IbisApplicationContext` via `ViscoLinkModule.getSpringConfigurationFiles()`. Pure event→fabric8-`Event` mapping is separated from the fabric8 client I/O so the mapping is unit-testable without a cluster. The bean auto-detects in-cluster API access at construction and silently no-ops off-cluster.

**Tech Stack:** Java 21, Frank!Framework 10.2.0 (`frankframework-core`), fabric8 `kubernetes-client` 7.7.0 (already on the classpath via `frankframework-kubernetes`), Spring, JUnit 5.14.1, Mockito, fabric8 `kubernetes-server-mock`.

## Global Constraints

- **No Lombok** in `com.viscosiety.*` classes (annotation processor is not wired for external modules).
- Custom classes are plain Java beans registered by fully-qualified class name; they are **not** usable as F!F XML named elements.
- fabric8 `kubernetes-client` is version **7.7.0** (compile scope, already present — do not re-declare compile scope). F!F core is **10.2.0**.
- Build command is **always** `./mvnw install -pl viscolink,viscostore && ./mvnw package -pl viscorunner` from repo root. **Never** run `mvn package -pl viscorunner` alone.
- The bean must live in `IbisApplicationContext` (loaded via `ViscoLinkModule.getSpringConfigurationFiles()`), the tier that receives both event types — **not** `springCustom.xml` (which loads into a child context and would not receive the events).
- Emit for every matching event: no dedup, no throttling.
- Filter: `(event instanceof ConfigurationMessageEvent || event instanceof ApplicationMessageEvent) && (level == WARN || level == ERROR)`. INFO is skipped.
- Kubernetes core/v1 Event `type` is always `"Warning"`; severity is carried in `reason`.
- **No Kubernetes YAML is committed to this repo.** All manifests are written to the session scratchpad and exist purely for the live k3d test (`~/kubeconfig.k3d.yaml`), which runs in a dedicated, disposable namespace.

---

### Task 1: Pure event→Event mapping (`LifecycleEventK8sPublisher` core)

Build the class with only the pure, cluster-free logic: the applicability filter, the reason classifier, and the fabric8 `Event` builder. No fabric8 client, no listener wiring yet.

**Files:**
- Create: `viscolink/src/main/java/com/viscosiety/k8s/LifecycleEventK8sPublisher.java`
- Modify: `viscolink/pom.xml` (add `mockito-core` test dependency if absent)
- Test: `viscolink/src/test/java/com/viscosiety/k8s/LifecycleEventK8sPublisherMappingTest.java`

**Interfaces:**
- Produces (used by Task 2):
  - `static boolean isApplicable(MessageEvent<?> event)`
  - `static String reasonFor(MessageEvent<?> event)` → one of `"ConfigurationAborted"`, `"ConfigurationError"`, `"ConfigurationWarning"`
  - `io.fabric8.kubernetes.api.model.Event toEvent(MessageEvent<?> event, String podName, String namespace)` — returns `null` when `!isApplicable(event)`
  - Constants: `REASON_ABORTED`, `REASON_ERROR`, `REASON_WARNING` (package-visible `static final String`)

- [ ] **Step 1: Add mockito test dependency if absent**

Check `viscolink/pom.xml` for an existing `org.mockito:mockito-core` (or `mockito-junit-jupiter`) test dependency. If none exists, add (version is managed by the F!F BOM — omit `<version>`):

```xml
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write the failing mapping test**

```java
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
        when(e.getMessage()).thenReturn(message);
        when(e.getLevel()).thenReturn(level);
        when(e.getTimestamp()).thenReturn(1_700_000_000_000L);
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -pl viscolink -Dtest=LifecycleEventK8sPublisherMappingTest`
Expected: FAIL — `LifecycleEventK8sPublisher` does not exist / does not compile.

- [ ] **Step 4: Write the minimal class with the pure logic**

```java
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -pl viscolink -Dtest=LifecycleEventK8sPublisherMappingTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add viscolink/pom.xml \
        viscolink/src/main/java/com/viscosiety/k8s/LifecycleEventK8sPublisher.java \
        viscolink/src/test/java/com/viscosiety/k8s/LifecycleEventK8sPublisherMappingTest.java
git commit -m "feat(k8s): pure lifecycle-event to K8s Event mapping"
```

---

### Task 2: Listener + client lifecycle + POST

Make the class a Spring `ApplicationListener<MessageEvent<?>>` and `DisposableBean`. Add in-cluster client construction + probe (auto-detect), pod identity resolution, the POST, and no-op/error guards. Test the I/O with fabric8's in-memory mock server.

**Files:**
- Modify: `viscolink/src/main/java/com/viscosiety/k8s/LifecycleEventK8sPublisher.java`
- Modify: `viscolink/pom.xml` (add `io.fabric8:kubernetes-server-mock` test dependency)
- Test: `viscolink/src/test/java/com/viscosiety/k8s/LifecycleEventK8sPublisherIoTest.java`

**Interfaces:**
- Consumes (from Task 1): `isApplicable`, `reasonFor`, `toEvent`, reason constants.
- Produces (used by Task 3):
  - public no-arg constructor (Spring) — resolves pod identity, builds+probes the in-cluster client, sets `client=null` when off-cluster
  - package constructor `LifecycleEventK8sPublisher(KubernetesClient client, String podName, String namespace)` (test seam)
  - `void onApplicationEvent(MessageEvent<?> event)`
  - `void destroy()`

- [ ] **Step 1: Add fabric8 mock-server test dependency**

In `viscolink/pom.xml`, add (version managed by the F!F BOM if present; otherwise pin to `7.7.0` to match the client):

```xml
<dependency>
    <groupId>io.fabric8</groupId>
    <artifactId>kubernetes-server-mock</artifactId>
    <version>7.7.0</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write the failing I/O test**

```java
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
        Mockito.when(e.getTimestamp()).thenReturn(1_700_000_000_000L);
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -pl viscolink -Dtest=LifecycleEventK8sPublisherIoTest`
Expected: FAIL — the package constructor and `onApplicationEvent` do not exist.

- [ ] **Step 4: Add the listener, constructors, client lifecycle, and POST**

Add imports and members to `LifecycleEventK8sPublisher`, implement the interfaces, and add the following. Keep the Task 1 pure methods unchanged.

```java
// class declaration becomes:
// public class LifecycleEventK8sPublisher
//         implements ApplicationListener<MessageEvent<?>>, DisposableBean {

// new imports:
// import java.util.Optional;
// import io.fabric8.kubernetes.client.KubernetesClient;
// import io.fabric8.kubernetes.client.KubernetesClientBuilder;
// import org.apache.commons.lang3.StringUtils;
// import org.apache.logging.log4j.LogManager;
// import org.apache.logging.log4j.Logger;
// import org.jspecify.annotations.NonNull;
// import org.springframework.beans.factory.DisposableBean;
// import org.springframework.context.ApplicationListener;

private static final Logger log = LogManager.getLogger(LifecycleEventK8sPublisher.class);
private static final int TIMEOUT_MILLIS = 3_000;
private static final String DEFAULT_NAMESPACE = "default";

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
```

- [ ] **Step 5: Run the full viscolink test suite**

Run: `./mvnw test -pl viscolink -Dtest='LifecycleEventK8sPublisher*'`
Expected: PASS (both mapping and I/O tests).

- [ ] **Step 6: Commit**

```bash
git add viscolink/pom.xml \
        viscolink/src/main/java/com/viscosiety/k8s/LifecycleEventK8sPublisher.java \
        viscolink/src/test/java/com/viscosiety/k8s/LifecycleEventK8sPublisherIoTest.java
git commit -m "feat(k8s): publish lifecycle Events with auto-detected in-cluster client"
```

---

### Task 3: Spring registration in IbisApplicationContext

Register the bean so it actually receives the propagated events. Mirror the existing `springFhir.xml` / `springStubbedRun.xml` module-file pattern.

**Files:**
- Create: `viscolink/src/main/resources/springK8sEvents.xml`
- Modify: `viscolink/src/main/java/com/viscosiety/components/ViscoLinkModule.java` (add file to `getSpringConfigurationFiles()`)
- Test: `viscolink/src/test/java/com/viscosiety/components/ViscoLinkModuleTest.java`

**Interfaces:**
- Consumes: `com.viscosiety.k8s.LifecycleEventK8sPublisher` (public no-arg constructor from Task 2).
- Produces: a bean of that type in `IbisApplicationContext`.

- [ ] **Step 1: Write the failing registration test**

```java
package com.viscosiety.components;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViscoLinkModuleTest {

    @Test
    void registersK8sEventsSpringFile() {
        assertTrue(new ViscoLinkModule().getSpringConfigurationFiles().contains("springK8sEvents.xml"),
                "springK8sEvents.xml must be registered so the publisher lands in IbisApplicationContext");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl viscolink -Dtest=ViscoLinkModuleTest`
Expected: FAIL — list does not yet contain `springK8sEvents.xml`.

- [ ] **Step 3: Create the Spring file**

`viscolink/src/main/resources/springK8sEvents.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
    Registered via ViscoLinkModule.getSpringConfigurationFiles() so it loads into
    IbisApplicationContext — the tier that receives ConfigurationMessageEvent (propagated
    up from each Configuration child context) and ApplicationMessageEvent (published by
    IbisContext at this tier). The publisher turns WARN/ERROR lifecycle events into
    Kubernetes core/v1 Warning Events on the pod. It auto-detects in-cluster API access and
    no-ops off-cluster, so it is safe to register unconditionally.
-->
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="
           http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd
       ">

    <bean id="lifecycleEventK8sPublisher"
          class="com.viscosiety.k8s.LifecycleEventK8sPublisher"
          destroy-method="destroy"/>
</beans>
```

- [ ] **Step 4: Add the file to the module list**

In `viscolink/src/main/java/com/viscosiety/components/ViscoLinkModule.java`, change:

```java
return List.of("springMllp.xml", "springFhir.xml", "springStubbedRun.xml");
```

to:

```java
return List.of("springMllp.xml", "springFhir.xml", "springStubbedRun.xml", "springK8sEvents.xml");
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -pl viscolink -Dtest=ViscoLinkModuleTest`
Expected: PASS.

- [ ] **Step 6: Build the module to confirm the Spring XML parses and the bean class resolves**

Run: `./mvnw install -pl viscolink,viscostore`
Expected: BUILD SUCCESS (no Spring bean-definition or class-not-found errors).

- [ ] **Step 7: Commit**

```bash
git add viscolink/src/main/resources/springK8sEvents.xml \
        viscolink/src/main/java/com/viscosiety/components/ViscoLinkModule.java \
        viscolink/src/test/java/com/viscosiety/components/ViscoLinkModuleTest.java
git commit -m "feat(k8s): register lifecycle-event publisher in IbisApplicationContext"
```

---

### Task 4: Live k3d test setup — namespace + RBAC (scratchpad only, NOT committed)

Prepare the cluster-side pieces for the live test on the k3d cluster at `~/kubeconfig.k3d.yaml`.
**No Kubernetes YAML is committed to this repo** (user constraint) — all manifests live in the
session scratchpad and exist purely for this test.

Conventions for all k8s steps:
- `export KUBECONFIG=~/kubeconfig.k3d.yaml`
- Scratchpad manifest dir: `$SCRATCH/k8s/` where `$SCRATCH` is this session's scratchpad directory.
- Test namespace: `viscolink-events-test`.

- [ ] **Step 1: Create the test namespace**

```bash
export KUBECONFIG=~/kubeconfig.k3d.yaml
kubectl create namespace viscolink-events-test
```
Expected: `namespace/viscolink-events-test created`.

- [ ] **Step 2: Write the RBAC manifest to scratchpad (not the repo)**

Write `$SCRATCH/k8s/rbac.yaml`:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: viscolink
  namespace: viscolink-events-test
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: viscolink-event-writer
  namespace: viscolink-events-test
rules:
  - apiGroups: [""]
    resources: ["events"]
    verbs: ["create", "patch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: viscolink-event-writer
  namespace: viscolink-events-test
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: viscolink-event-writer
subjects:
  - kind: ServiceAccount
    name: viscolink
    namespace: viscolink-events-test
```

- [ ] **Step 3: Apply the RBAC**

```bash
kubectl apply -f "$SCRATCH/k8s/rbac.yaml"
```
Expected: serviceaccount, role, and rolebinding all created.

---

### Task 5: Build image, load into k3d, deploy, and verify a real Event

Deploy the freshly built viscorunner image into the k3d test namespace and prove the publisher
creates a real Kubernetes Event on a WARN/ERROR lifecycle event. All manifests scratchpad-only.

**Files:** none committed (scratchpad manifests only).

- [ ] **Step 1: Full reactor build**

Run: `./mvnw install -pl viscolink,viscostore && ./mvnw package -pl viscorunner`
Expected: BUILD SUCCESS for all three modules.

- [ ] **Step 2: Build and import the image into k3d**

Discover the k3d cluster name (`k3d cluster list`), then build the viscorunner image and import it:

```bash
docker build -t viscolink-events-test:local viscorunner
k3d image import viscolink-events-test:local -c <cluster-name>
```
Expected: image imported into all k3d nodes.

- [ ] **Step 3: Write the Deployment manifest to scratchpad**

Write `$SCRATCH/k8s/deploy.yaml`. Deploy viscolink with the `viscolink` ServiceAccount (bound to
the event-writer Role), the `POD_NAME` downward-API env, `imagePullPolicy: Never` (use the imported
image), and configuration that produces at least one WARN/ERROR lifecycle event at startup so the
publisher fires. The concrete way to guarantee a lifecycle warning/error (a deliberately invalid
mounted configuration, or an unreachable datasource that makes a configuration fail to load) is
chosen at execution time based on the image's default config behaviour; document the chosen trigger
inline in the manifest. Key fields:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: viscolink
  namespace: viscolink-events-test
spec:
  replicas: 1
  selector:
    matchLabels: { app: viscolink }
  template:
    metadata:
      labels: { app: viscolink }
    spec:
      serviceAccountName: viscolink
      containers:
        - name: viscolink
          image: viscolink-events-test:local
          imagePullPolicy: Never
          env:
            - name: POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            # + whatever env forces a config WARN/ERROR at startup (chosen at execution time)
          ports:
            - containerPort: 8080
```

- [ ] **Step 4: Apply and wait for the pod to start**

```bash
kubectl apply -f "$SCRATCH/k8s/deploy.yaml"
kubectl -n viscolink-events-test rollout status deploy/viscolink --timeout=180s || true
kubectl -n viscolink-events-test get pods
```
Expected: a pod that has started the F!F application (it need not be fully Ready — the lifecycle
event fires during configuration load).

- [ ] **Step 5: Confirm the publisher enabled itself in-cluster**

```bash
kubectl -n viscolink-events-test logs deploy/viscolink | grep -i "lifecycle-event publishing"
```
Expected: `... publishing enabled for pod [viscolink-...] in namespace [viscolink-events-test]`
(NOT the "disabled" line — that would mean in-cluster detection failed).

- [ ] **Step 6: Verify a real Kubernetes Event was created**

```bash
kubectl -n viscolink-events-test get events \
  --field-selector reportingComponent=viscolink -o wide
kubectl -n viscolink-events-test get events -o json \
  | jq -r '.items[] | select(.reportingComponent=="viscolink")
           | [.reason, .type, .involvedObject.kind, .involvedObject.name, .message] | @tsv'
```
Expected: at least one Event with `type=Warning`, `reason` in
{`ConfigurationAborted`,`ConfigurationWarning`,`ConfigurationError`}, `involvedObject.kind=Pod`,
and the pod's name. **This is the core success criterion.**

- [ ] **Step 7: Tear down the test namespace**

```bash
kubectl delete namespace viscolink-events-test
```
Expected: namespace (with the deployment, SA, RBAC, and Events) removed. Nothing was written to
the git repo.

---

## Self-Review

**Spec coverage:**
- Component `LifecycleEventK8sPublisher` as `ApplicationListener` — Task 1/2. ✓
- Filter `(Configuration||Application) && (WARN||ERROR)` — Task 1 `isApplicable` + tests. ✓
- Event mapping (type=Warning, reason scheme, message, involvedObject Pod, reportingComponent) — Task 1 `toEvent` + tests. ✓
- Auto-detect enablement, no-op off-cluster — Task 2 constructor + `buildInClusterClientOrNull` + null-client test; in-cluster enable confirmed live in Task 5 Step 5. ✓
- Pod identity (`POD_NAME`→`HOSTNAME`) — Task 2 `resolvePodName` + Task 5 deployment env. ✓
- core/v1 delivery, emit-every-time, wrapped errors, client closed on shutdown — Task 2 `onApplicationEvent`/`destroy`. ✓
- Parent-context registration (IbisApplicationContext via module files) — Task 3 + resolved wiring seam. ✓
- Testing via fabric8 mock server — Task 2; real-cluster Event verified in Task 5 Step 6. ✓
- RBAC (events:create) + POD_NAME — applied live to the k3d test namespace, scratchpad-only, NOT committed (user constraint) — Task 4 + Task 5. ✓
- Full reactor build discipline — Task 5 Step 1. ✓

**Placeholder scan:** No TBD/TODO. The single deliberately deferred detail is the Task 5 Step 3 startup-trigger for a WARN/ERROR lifecycle event, chosen at execution time from the image's actual default-config behaviour — flagged explicitly, not a hidden gap. No Kubernetes YAML is committed to the repo; all manifests are scratchpad-only.

**Type consistency:** `isApplicable`, `reasonFor`, `toEvent(MessageEvent<?>, String, String)`, reason constants, and the two constructors are named identically across Task 1→2→3. Test seam constructor signature `(KubernetesClient, String, String)` matches its use in the Task 2 I/O test.

## Out of scope (from spec)

- Upstream Frank!Framework PR (needs a new Spring-managed, fabric8-capable module + bean-registration seam the SPI credential factory cannot provide).
- `AdapterMessageEvent` Events; dedup/throttling; portal-side reader changes.
