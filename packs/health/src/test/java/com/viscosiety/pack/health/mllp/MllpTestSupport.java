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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Test helpers: a listener bound to a free local port, and MLLP framing on the client side. */
final class MllpTestSupport {

    private MllpTestSupport() {
    }

    static String adt(String controlId) {
        return "MSH|^~\\&|HIS|HOSP|VL|VL|20261111120000||ADT^A01|" + (controlId == null ? "" : controlId)
                + "|P|2.5\r" + "PID|1||12345^^^HOSP||Doe^John\r";
    }

    /** An MSH segment that stops before MSH-10. */
    static String adtWithoutControlId() {
        return "MSH|^~\\&|HIS|HOSP\rPID|1||12345^^^HOSP||Doe^John\r";
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** A listener named {@code mllpListener} on a free port, not yet started. */
    static MllpListener listenerOnFreePort() throws IOException {
        int port = freePort();
        MllpConnectionFactory factory = mock(MllpConnectionFactory.class);
        when(factory.getPort()).thenReturn(port);
        when(factory.getHost()).thenReturn("127.0.0.1");
        when(factory.getBacklog()).thenReturn(10);
        when(factory.getSocketTimeout()).thenReturn(5_000);
        when(factory.getCharset()).thenReturn("UTF-8");
        MllpConnectionFactoryFactory factoryFactory = mock(MllpConnectionFactoryFactory.class);
        when(factoryFactory.getConnectionFactory("test-resource")).thenReturn(factory);

        MllpListener listener = new MllpListener();
        listener.setName("mllpListener");
        listener.setResourceName("test-resource");
        listener.setMllpConnectionFactoryFactory(factoryFactory);
        return listener;
    }

    static int portOf(MllpListener listener) {
        return listener.getConnectionFactory().getPort();
    }

    static void send(OutputStream out, String message) throws IOException {
        out.write(MllpListener.START_BLOCK);
        out.write(message.getBytes(StandardCharsets.UTF_8));
        out.write(MllpListener.END_BLOCK);
        out.write(MllpListener.CARRIAGE_RETURN);
        out.flush();
    }

    /**
     * Reads one frame and returns its raw bytes including start block, end block and carriage return,
     * or {@code null} when the server closed the connection before sending anything.
     */
    static byte[] readFrame(InputStream in) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        int previous = -1;
        int b;
        while ((b = in.read()) != -1) {
            frame.write(b);
            if (previous == MllpListener.END_BLOCK && b == MllpListener.CARRIAGE_RETURN) {
                return frame.toByteArray();
            }
            previous = b;
        }
        return frame.size() == 0 ? null : frame.toByteArray();
    }

    /** The payload of a frame read by {@link #readFrame}. */
    static String payload(byte[] frame) {
        return new String(frame, 1, frame.length - 3, StandardCharsets.UTF_8);
    }

    static Socket connect(MllpListener listener) throws IOException {
        Socket socket = new Socket("127.0.0.1", portOf(listener));
        socket.setSoTimeout(10_000);
        return socket;
    }
}
