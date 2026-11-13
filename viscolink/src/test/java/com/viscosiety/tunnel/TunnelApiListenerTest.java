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

package com.viscosiety.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.DispatcherType;

import org.frankframework.core.IMessageHandler;
import org.frankframework.core.ListenerException;
import org.frankframework.core.PipeLineSession;
import org.frankframework.stream.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The listener's own check: only a request that arrived on a tunnel port (or a FORWARD) reaches the pipeline. */
class TunnelApiListenerTest {

    private String saved;
    private IMessageHandler<Message> handler;
    private TunnelApiListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        saved = System.getProperty(TunnelPorts.PORTS_PROPERTY);
        System.setProperty(TunnelPorts.PORTS_PROPERTY, "8443");
        handler = mock(IMessageHandler.class);
        when(handler.processRequest(any(), any(), any())).thenReturn(new Message("done"));
        listener = new TunnelApiListener();
        listener.setName("feed");
        listener.setHandler(handler);
    }

    @AfterEach
    void restore() {
        if (saved == null) {
            System.clearProperty(TunnelPorts.PORTS_PROPERTY);
        } else {
            System.setProperty(TunnelPorts.PORTS_PROPERTY, saved);
        }
    }

    private static PipeLineSession sessionWith(int localPort, DispatcherType type) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/feed");
        request.setLocalPort(localPort);
        request.setDispatcherType(type);
        PipeLineSession session = new PipeLineSession();
        session.put(PipeLineSession.HTTP_REQUEST_KEY, request);
        return session;
    }

    @Test
    void aRequestOnATunnelPortIsProcessed() throws Exception {
        try (PipeLineSession session = sessionWith(8443, DispatcherType.REQUEST)) {
            Message result = listener.processRequest(new Message("ping"), session);
            assertEquals("done", result.asString());
        }
        verify(handler).processRequest(any(), any(), any());
    }

    @Test
    void aRequestOnThePublicPortIsRefusedAndNoPipelineRuns() throws Exception {
        try (PipeLineSession session = sessionWith(8080, DispatcherType.REQUEST)) {
            ListenerException e = assertThrows(ListenerException.class,
                    () -> listener.processRequest(new Message("ping"), session));
            assertEquals(TunnelApiListener.REFUSAL, e.getMessage());
        }
        verify(handler, never()).processRequest(any(), any(), any());
    }

    @Test
    void aForwardOnThePublicPortIsProcessed() throws Exception {
        try (PipeLineSession session = sessionWith(8080, DispatcherType.FORWARD)) {
            assertSame(TunnelApiListener.class, listener.getClass());
            assertEquals("done", listener.processRequest(new Message("ping"), session).asString());
        }
        verify(handler).processRequest(any(), any(), any());
    }

    @Test
    void anIncludeOrErrorDispatchOnThePublicPortIsRefused() throws Exception {
        for (DispatcherType type : new DispatcherType[] {DispatcherType.INCLUDE, DispatcherType.ERROR, DispatcherType.ASYNC}) {
            try (PipeLineSession session = sessionWith(8080, type)) {
                assertThrows(ListenerException.class, () -> listener.processRequest(new Message("ping"), session));
            }
        }
        verify(handler, never()).processRequest(any(), any(), any());
    }

    @Test
    void aSessionWithoutAServletRequestIsRefused() throws Exception {
        try (PipeLineSession session = new PipeLineSession()) {
            assertThrows(ListenerException.class, () -> listener.processRequest(new Message("ping"), session));
        }
        verify(handler, never()).processRequest(any(), any(), any());
    }

    @Test
    void noTunnelPortsMeansEveryDirectRequestIsRefused() throws Exception {
        System.clearProperty(TunnelPorts.PORTS_PROPERTY);
        try (PipeLineSession session = sessionWith(8443, DispatcherType.REQUEST)) {
            assertThrows(ListenerException.class, () -> listener.processRequest(new Message("ping"), session));
        }
        verify(handler, never()).processRequest(any(), any(), any());
    }
}
