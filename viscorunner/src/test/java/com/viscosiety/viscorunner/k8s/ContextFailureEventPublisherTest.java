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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import io.fabric8.kubernetes.api.model.Event;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.junit.jupiter.api.Test;

class ContextFailureEventPublisherTest {

    @Test
    void failedContextsReturnsOnlyFailedOnes() {
        Server server = mock(Server.class);
        Service service = mock(Service.class);
        Engine engine = mock(Engine.class);
        Container host = mock(Container.class);
        Context failed = mock(Context.class);
        Context started = mock(Context.class);

        when(server.findServices()).thenReturn(new Service[] { service });
        when(service.getContainer()).thenReturn(engine);
        when(engine.findChildren()).thenReturn(new Container[] { host });
        when(host.findChildren()).thenReturn(new Container[] { failed, started });
        when(failed.getState()).thenReturn(LifecycleState.FAILED);
        when(started.getState()).thenReturn(LifecycleState.STARTED);

        List<Context> result = ContextFailureEventPublisher.failedContexts(server);

        assertEquals(1, result.size());
        assertSame(failed, result.get(0));
    }

    @Test
    void buildEventShapesAWarningContextStartFailedEvent() {
        Event e = ContextFailureEventPublisher.buildEvent(
                "/viscolink", "NoSuchElementException: alias [x] not found", "viscorunner-abc", "frank");

        assertEquals("Warning", e.getType());
        assertEquals("ContextStartFailed", e.getReason());
        assertEquals("viscorunner", e.getReportingComponent());
        assertEquals("Pod", e.getInvolvedObject().getKind());
        assertEquals("viscorunner-abc", e.getInvolvedObject().getName());
        assertEquals("frank", e.getInvolvedObject().getNamespace());
        assertTrue(e.getMessage().contains("/viscolink"), e.getMessage());
        assertTrue(e.getMessage().contains("alias [x] not found"), e.getMessage());
    }

    @Test
    void buildEventOmitsCauseWhenBlank() {
        Event e = ContextFailureEventPublisher.buildEvent("/viscostore", null, "p", "n");
        assertEquals("context [/viscostore] failed to start", e.getMessage());
    }

    @Test
    void rootCauseMessageUnwrapsToDeepestCause() {
        Throwable t = new RuntimeException("outer",
                new IllegalStateException("mid",
                        new NoSuchElementException("alias [x] not found")));
        assertEquals("NoSuchElementException: alias [x] not found",
                ContextFailureEventPublisher.rootCauseMessage(t));
    }

    @Test
    void causeHandlerMatchesRecordNamingTheContextAndIgnoresNonSevere() {
        ContextFailureEventPublisher.CauseCaptureHandler h =
                new ContextFailureEventPublisher.CauseCaptureHandler();

        // Non-SEVERE with a throwable — ignored.
        LogRecord info = new LogRecord(Level.INFO, "unrelated [/viscolink]");
        info.setThrown(new RuntimeException("noise"));
        h.publish(info);

        // SEVERE naming the failing context — captured.
        LogRecord severe = new LogRecord(Level.SEVERE, "Failed to start component [StandardContext[/viscolink]]");
        severe.setThrown(new RuntimeException("boom", new NoSuchElementException("alias [x] not found")));
        h.publish(severe);

        assertEquals("NoSuchElementException: alias [x] not found", h.bestCauseFor("/viscolink"));
    }

    @Test
    void causeHandlerFallsBackToMostRecentWhenNoPathMatch() {
        ContextFailureEventPublisher.CauseCaptureHandler h =
                new ContextFailureEventPublisher.CauseCaptureHandler();
        LogRecord severe = new LogRecord(Level.SEVERE, "some unrelated deploy failure");
        severe.setThrown(new IllegalStateException("unable to configure Spring Security"));
        h.publish(severe);

        assertEquals("IllegalStateException: unable to configure Spring Security",
                h.bestCauseFor("/nomatch"));
    }
}
