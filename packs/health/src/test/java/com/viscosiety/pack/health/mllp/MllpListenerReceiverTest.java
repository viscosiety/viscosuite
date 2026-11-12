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
import static com.viscosiety.pack.health.mllp.MllpTestSupport.connect;
import static com.viscosiety.pack.health.mllp.MllpTestSupport.listenerOnFreePort;
import static com.viscosiety.pack.health.mllp.MllpTestSupport.payload;
import static com.viscosiety.pack.health.mllp.MllpTestSupport.readFrame;
import static com.viscosiety.pack.health.mllp.MllpTestSupport.send;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import org.frankframework.core.Adapter;
import org.frankframework.core.FrankElement;
import org.frankframework.core.PipeLine.ExitState;
import org.frankframework.core.PipeLineResult;
import org.frankframework.core.PipeLineSession;
import org.frankframework.receivers.Receiver;
import org.frankframework.statistics.FrankMeterType;
import org.frankframework.statistics.MetricsInitializer;
import org.frankframework.stream.Message;
import org.frankframework.util.RunState;

/**
 * The 2026-11-11 incident at the level where it happened: an {@link MllpListener} on a real socket, pushing
 * into a real Frank!Framework {@link Receiver} that went through its own {@code configure()}. A sender that
 * sends the same MSH-10 twice must get two acknowledgements.
 *
 * <p>What this does NOT cover: the Receiver is not built by a Spring configuration and its {@code start()} is
 * not called (the run state is set to STARTED by hand, the listener is started directly). The pipeline is a
 * stub {@link Adapter} that answers an ACK, metrics go to an in-memory registry and the transaction manager has
 * no resource behind it. The Receiver's history check, process status cache, retry limit and the call into the
 * adapter are the real framework code.</p>
 */
class MllpListenerReceiverTest {

    private MllpListener listener;

    @AfterEach
    void stopListener() {
        if (listener != null) {
            listener.stop();
        }
    }

    /** Spring's own transaction state machine with no resource behind it. */
    private static final class NoResourceTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    private static MetricsInitializer inMemoryMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsInitializer metrics = mock(MetricsInitializer.class);
        when(metrics.createCounter(any(FrankElement.class), any(FrankMeterType.class)))
                .thenAnswer(invocation -> registry.counter("counter-" + UUID.randomUUID()));
        when(metrics.createThreadBasedDistributionSummary(any(), any(), anyInt()))
                .thenAnswer(invocation -> registry.summary("summary-" + UUID.randomUUID()));
        return metrics;
    }

    /** The MSA segment of an acknowledgement, or the whole payload when it has none. */
    private static String msaOf(String ack) {
        for (String segment : ack.split("\r")) {
            if (segment.startsWith("MSA|")) {
                return segment;
            }
        }
        return ack;
    }

    @Test
    void theSameControlIdSentTwiceIsProcessedAndAcknowledgedTwice() throws Exception {
        List<String> pipelineMessageIds = new CopyOnWriteArrayList<>();
        List<String> pipelineCorrelationIds = new CopyOnWriteArrayList<>();

        Adapter adapter = mock(Adapter.class);
        when(adapter.getName()).thenReturn("AdtIn");
        when(adapter.processMessageWithExceptions(any(), anyString(), any(Message.class), any(PipeLineSession.class)))
                .thenAnswer(invocation -> {
                    pipelineMessageIds.add(invocation.getArgument(1));
                    PipeLineSession session = invocation.getArgument(3);
                    pipelineCorrelationIds.add(session.getCorrelationId());
                    PipeLineResult result = new PipeLineResult();
                    result.setState(ExitState.SUCCESS);
                    result.setResult(new Message("MSH|^~\\&|VL|VL|HIS|HOSP|20261111120001||ACK^A01|ACK" + pipelineMessageIds.size()
                            + "|P|2.5\rMSA|AA|MSG00001\r"));
                    return result;
                });

        listener = listenerOnFreePort();
        MllpListener theListener = listener;
        Receiver<String> receiver = new Receiver<>() {
            {
                setName("AdtInReceiver");
                setApplicationContext(adapter);
                setListener(theListener);
                setTxManager(new NoResourceTransactionManager());
                setConfigurationMetrics(inMemoryMetrics());
                configure(); // sets itself as the listener's handler, and maxRetries to 0 (not an IRedeliveringListener)
                runState.setRunState(RunState.STARTED);
            }
        };
        assertEquals(0, receiver.getMaxRetries());
        listener.start();

        try (Socket socket = connect(listener)) {
            send(socket.getOutputStream(), adt("MSG00001"));
            byte[] first = readFrame(socket.getInputStream());
            assertNotNull(first, "first acknowledgement");
            send(socket.getOutputStream(), adt("MSG00001"));
            byte[] second = readFrame(socket.getInputStream());
            assertNotNull(second, "second acknowledgement");

            assertEquals("MSA|AA|MSG00001", msaOf(payload(first)));
            assertEquals("MSA|AA|MSG00001", msaOf(payload(second)), "the second message got no acknowledgement");
        }

        assertEquals(2, pipelineMessageIds.size(), "both messages reached the pipeline");
        assertNotEquals(pipelineMessageIds.get(0), pipelineMessageIds.get(1));
        assertEquals(List.of("MSG00001", "MSG00001"), pipelineCorrelationIds, "the control id is the correlation id");
        assertEquals(0.0, receiver.getMessagesRejected(), "nothing rejected as a redelivery");
    }
}
