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

package com.viscosiety.hl7util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * Minimal MLLP client.
 *
 * <p>Connects to a remote MLLP host, sends an HL7v2 message framed with the
 * standard MLLP start/end blocks, and reads the framed ACK response.</p>
 *
 * <p>MLLP framing:</p>
 * <pre>
 *   [0x0B] &lt;HL7v2 pipe-delimited message&gt; [0x1C][0x0D]
 * </pre>
 */
public class MllpClient implements Closeable {

    private static final int START_BLOCK     = 0x0B;
    private static final int END_BLOCK       = 0x1C;
    private static final int CARRIAGE_RETURN = 0x0D;

    private final String  host;
    private final int     port;
    private final int     connectTimeout;
    private final int     socketTimeout;
    private final Charset charset;

    private Socket socket;

    public MllpClient(String host, int port, int connectTimeout, int socketTimeout, Charset charset) {
        this.host           = host;
        this.port           = port;
        this.connectTimeout = connectTimeout;
        this.socketTimeout  = socketTimeout;
        this.charset        = charset;
    }

    /** Opens a TCP connection to the configured host and port. */
    public void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeout);
        socket.setSoTimeout(socketTimeout);
    }

    /**
     * Sends an HL7v2 message (pipe-delimited, CR-separated segments) and returns
     * the raw ACK string without MLLP framing bytes.
     */
    public String send(String message) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(START_BLOCK);
        out.write(message.getBytes(charset));
        out.write(END_BLOCK);
        out.write(CARRIAGE_RETURN);
        out.flush();
        return readResponse(socket.getInputStream());
    }

    private String readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean started = false;
        int prev = -1, b;
        while ((b = in.read()) != -1) {
            if (!started) {
                if (b == START_BLOCK) started = true;
                continue;
            }
            if (prev == END_BLOCK && b == CARRIAGE_RETURN) {
                // prev END_BLOCK byte was already written to buf — strip it
                byte[] data = buf.toByteArray();
                return new String(data, 0, data.length - 1, charset);
            }
            buf.write(b);
            prev = b;
        }
        throw new IOException("Connection closed before MLLP END_BLOCK was received");
    }

    @Override
    public void close() {
        if (socket != null && !socket.isClosed()) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
