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

package com.viscosiety.mllp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.frankframework.configuration.ConfigurationException;
import org.frankframework.core.IbisExceptionListener;
import org.frankframework.core.IMessageHandler;
import org.frankframework.core.IPushingListener;
import org.frankframework.core.ListenerException;
import org.frankframework.core.PipeLineResult;
import org.frankframework.core.PipeLineSession;
import org.frankframework.lifecycle.LifecycleException;
import org.frankframework.receivers.MessageWrapper;
import org.frankframework.receivers.RawMessageWrapper;
import org.frankframework.stream.Message;

/**
 * MLLP (Minimal Lower Layer Protocol) listener for Frank!Framework.
 *
 * <p>Opens a TCP server socket on the port defined by the bound MLLP resource and accepts
 * persistent connections from HL7v2 sending systems. Each inbound MLLP-framed message is
 * passed through the pipeline and the pipeline result is framed and written back as the ACK.</p>
 *
 * <p>HL7v2 ↔ XML conversion is handled by pipeline pipes, not here. Place
 * {@code Hl7v2ToXmlPipe} at the pipeline entry and {@code XmlToHl7v2Pipe} when building
 * the ACK. This allows per-port HL7v2 version configuration.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * <Receiver name="MllpReceiver">
 *   <MllpListener name="mllpListener" resourceName="inbound-2575" />
 * </Receiver>
 * }</pre>
 */
public class MllpListener extends MllpFacade implements IPushingListener<String> {

    private static final Logger log = LogManager.getLogger(MllpListener.class);

    static final byte START_BLOCK     = 0x0B;
    static final byte END_BLOCK       = 0x1C;
    static final byte CARRIAGE_RETURN = 0x0D;

    private IMessageHandler<String> handler;
    private IbisExceptionListener   exceptionListener;

    private ServerSocket     serverSocket;
    private volatile boolean running = false;
    private Thread           acceptThread;
    private ExecutorService  connectionPool;

    @Override
    public void setHandler(IMessageHandler<String> handler) {
        this.handler = handler;
    }

    @Override
    public void setExceptionListener(IbisExceptionListener listener) {
        this.exceptionListener = listener;
    }

    @Override
    public void configure() throws ConfigurationException {
        super.configure();
    }

    @Override
    public void start() {
        MllpConnectionFactory factory = getConnectionFactory();
        running = true;

        AtomicInteger counter = new AtomicInteger();
        connectionPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mllp-conn-" + factory.getPort() + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        try {
            serverSocket = new ServerSocket(factory.getPort(), factory.getBacklog());
        } catch (IOException e) {
            running = false;
            throw new LifecycleException(getLogPrefix() + "Failed to bind to port " + factory.getPort(), e);
        }

        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(factory.getSocketTimeout());
                    connectionPool.submit(() -> handleConnection(socket, factory.getCharset()));
                } catch (IOException e) {
                    if (running) {
                        log.warn("{} Accept error: {}", getLogPrefix(), e.getMessage());
                    }
                }
            }
        }, "mllp-accept-" + factory.getPort());
        acceptThread.setDaemon(true);
        acceptThread.start();
        log.info("{} Started on port {}", getLogPrefix(), factory.getPort());
    }

    @Override
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.warn("{} Error closing server socket: {}", getLogPrefix(), e.getMessage());
        }
        if (connectionPool != null) {
            connectionPool.shutdown();
            try {
                if (!connectionPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    connectionPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                connectionPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("{} Stopped", getLogPrefix());
    }

    // ---- Per-connection handler ----

    private void handleConnection(Socket socket, String charset) {
        String remote = socket.getRemoteSocketAddress().toString();
        log.debug("{} Connection accepted from {}", getLogPrefix(), remote);
        try (socket) {
            InputStream  in  = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            while (running && !socket.isClosed()) {
                byte[] rawBytes = readMllpMessage(in);
                if (rawBytes == null) break;

                String rawHl7     = new String(rawBytes, java.nio.charset.Charset.forName(charset));
                String messageId  = extractControlId(rawHl7);

                try (PipeLineSession session = new PipeLineSession()) {
                    MessageWrapper<String> wrapper = new MessageWrapper<>(new Message(rawHl7), messageId, null);
                    String ack = handler.processRequest(this, wrapper, session).asString();
                    writeMllpMessage(out, ack, charset);

                } catch (ListenerException e) {
                    log.error("{} Pipeline error for message {}: {}", getLogPrefix(), messageId, e.getMessage(), e);
                    if (exceptionListener != null) {
                        exceptionListener.exceptionThrown(this, e);
                    }
                    break; // close connection — pipeline could not produce a response

                } catch (IOException e) {
                    log.warn("{} IO error processing message: {}", getLogPrefix(), e.getMessage());
                    break;
                }
            }
        } catch (IOException e) {
            if (running) {
                log.debug("{} Connection from {} closed: {}", getLogPrefix(), remote, e.getMessage());
            }
        }
        log.debug("{} Connection from {} ended", getLogPrefix(), remote);
    }

    /** Read one MLLP-framed message. Returns null when the stream closes before START_BLOCK. */
    private byte[] readMllpMessage(InputStream in) throws IOException {
        int b;
        while (true) {
            b = in.read();
            if (b == -1) return null;
            if ((byte) b == START_BLOCK) break;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean gotEndBlock = false;
        while (true) {
            b = in.read();
            if (b == -1) return baos.size() > 0 ? baos.toByteArray() : null;
            if (gotEndBlock) {
                if ((byte) b == CARRIAGE_RETURN) break;
                baos.write(END_BLOCK);
                baos.write(b);
                gotEndBlock = false;
            } else if ((byte) b == END_BLOCK) {
                gotEndBlock = true;
            } else {
                baos.write(b);
            }
        }
        return baos.toByteArray();
    }

    private void writeMllpMessage(OutputStream out, String message, String charset) throws IOException {
        out.write(START_BLOCK);
        out.write(message.getBytes(java.nio.charset.Charset.forName(charset)));
        out.write(END_BLOCK);
        out.write(CARRIAGE_RETURN);
        out.flush();
    }

    // ---- IPushingListener ----

    @Override
    public RawMessageWrapper<String> wrapRawMessage(String rawMessage, PipeLineSession session) throws ListenerException {
        return new RawMessageWrapper<>(rawMessage, extractControlId(rawMessage), null);
    }

    @Override
    public Message extractMessage(RawMessageWrapper<String> wrapper, Map<String, Object> context) throws ListenerException {
        return new Message(wrapper.getRawMessage());
    }

    @Override
    public void afterMessageProcessed(PipeLineResult processResult, RawMessageWrapper<String> rawMessage, PipeLineSession pipeLineSession) throws ListenerException {
        // ACK is sent synchronously inside handleConnection(); nothing to do here.
    }

    private String extractControlId(String hl7) {
        String[] segs = hl7.split("\r");
        if (segs.length == 0) return UUID.randomUUID().toString();
        String[] fields = segs[0].split("\\|", -1);
        return fields.length > 9 ? fields[9] : UUID.randomUUID().toString();
    }

}
