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

package com.viscosiety.pack.health.mllp;

import static com.viscosiety.pack.health.mllp.MllpTestSupport.adt;
import static com.viscosiety.pack.health.mllp.MllpTestSupport.adtWithoutControlId;
import static com.viscosiety.pack.health.mllp.MllpTestSupport.connect;
import static com.viscosiety.pack.health.mllp.MllpTestSupport.listenerOnFreePort;
import static com.viscosiety.pack.health.mllp.MllpTestSupport.payload;
import static com.viscosiety.pack.health.mllp.MllpTestSupport.readFrame;
import static com.viscosiety.pack.health.mllp.MllpTestSupport.send;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.frankframework.core.IListener;
import org.frankframework.core.IMessageHandler;
import org.frankframework.core.IPushingListener;
import org.frankframework.core.ListenerException;
import org.frankframework.core.PipeLineSession;
import org.frankframework.receivers.MessageWrapper;
import org.frankframework.receivers.RawMessageWrapper;
import org.frankframework.stream.Message;

class MllpListenerTest {

    private MllpListener listener;

    @AfterEach
    void stopListener() {
        if (listener != null) {
            listener.stop();
        }
    }

    /** Records every wrapper the listener hands over and answers with what {@code reply} returns. */
    private static final class RecordingHandler implements IMessageHandler<String> {
        final List<MessageWrapper<String>> received = new CopyOnWriteArrayList<>();
        private final Function<String, Message> reply;

        RecordingHandler(Function<String, Message> reply) {
            this.reply = reply;
        }

        @Override
        public Message processRequest(IPushingListener<String> origin, MessageWrapper<String> messageWrapper, PipeLineSession session) throws ListenerException {
            received.add(messageWrapper);
            try {
                return reply.apply(messageWrapper.getMessage().asString());
            } catch (java.io.IOException e) {
                throw new ListenerException(e);
            }
        }

        @Override
        public void processRawMessage(IListener<String> origin, RawMessageWrapper<String> rawMessage, PipeLineSession session, boolean duplicatesAlreadyChecked) {
            throw new UnsupportedOperationException();
        }
    }

    private RecordingHandler start(Function<String, Message> reply) throws Exception {
        listener = listenerOnFreePort();
        RecordingHandler handler = new RecordingHandler(reply);
        listener.setHandler(handler);
        listener.configure();
        listener.start();
        return handler;
    }

    @Test
    void eachDeliveryGetsItsOwnTechnicalIdAndTheControlIdAsCorrelationId() throws Exception {
        RecordingHandler handler = start(request -> new Message("MSA|AA"));
        try (Socket socket = connect(listener)) {
            send(socket.getOutputStream(), adt("MSG00001"));
            assertEquals("MSA|AA", payload(readFrame(socket.getInputStream())));
            send(socket.getOutputStream(), adt("MSG00001"));
            assertEquals("MSA|AA", payload(readFrame(socket.getInputStream())));
        }

        assertEquals(2, handler.received.size());
        MessageWrapper<String> first = handler.received.get(0);
        MessageWrapper<String> second = handler.received.get(1);
        assertEquals("MSG00001", first.getCorrelationId());
        assertEquals("MSG00001", second.getCorrelationId());
        assertNotEquals(first.getId(), second.getId(), "a resend must not look like a redelivery of the same message");
        assertNotEquals("MSG00001", first.getId());
        UUID.fromString(first.getId());
        UUID.fromString(second.getId());
    }

    @Test
    void aMessageWithoutControlIdGetsATechnicalIdAndNoCorrelationId() throws Exception {
        RecordingHandler handler = start(request -> new Message("MSA|AA"));
        try (Socket socket = connect(listener)) {
            send(socket.getOutputStream(), adtWithoutControlId());
            assertEquals("MSA|AA", payload(readFrame(socket.getInputStream())));
            send(socket.getOutputStream(), adt(null));
            assertEquals("MSA|AA", payload(readFrame(socket.getInputStream())));
        }

        assertEquals(2, handler.received.size());
        for (MessageWrapper<String> wrapper : handler.received) {
            UUID.fromString(wrapper.getId());
            assertNull(wrapper.getCorrelationId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "empty"})
    void anEmptyPipelineResultClosesTheConnectionWithoutAReply(String kind) throws Exception {
        Message empty = "null".equals(kind) ? Message.nullMessage() : new Message("");
        RecordingHandler handler = start(request -> empty);
        try (Socket socket = connect(listener)) {
            send(socket.getOutputStream(), adt("MSG00002"));
            assertNull(readFrame(socket.getInputStream()), "no frame, the connection is closed");
        }
        assertEquals(1, handler.received.size());
    }

    @Test
    void wrapRawMessageUsesAFreshTechnicalIdAndTheControlIdAsCorrelationId() throws Exception {
        listener = new MllpListener();
        try (PipeLineSession session = new PipeLineSession()) {
            RawMessageWrapper<String> a = listener.wrapRawMessage(adt("MSG00003"), session);
            RawMessageWrapper<String> b = listener.wrapRawMessage(adt("MSG00003"), session);
            assertEquals("MSG00003", a.getCorrelationId());
            assertNotEquals(a.getId(), b.getId());
            UUID.fromString(a.getId());

            RawMessageWrapper<String> none = listener.wrapRawMessage(adtWithoutControlId(), session);
            assertNotNull(none.getId());
            assertNull(none.getCorrelationId());
        }
        listener = null;
    }

    @Test
    void aBlankControlIdCountsAsNone() throws Exception {
        listener = new MllpListener();
        try (PipeLineSession session = new PipeLineSession()) {
            RawMessageWrapper<String> blank = listener.wrapRawMessage(adt("  "), session);
            assertNull(blank.getCorrelationId());
            assertFalse(blank.getId().isBlank());
            assertTrue(blank.getId().length() > 10);
        }
        listener = null;
    }
}
