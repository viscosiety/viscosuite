package com.viscosiety.mllp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;

import org.frankframework.configuration.ConfigurationException;
import org.frankframework.core.ISenderWithParameters;
import org.frankframework.core.PipeLineSession;
import org.frankframework.core.SenderException;
import org.frankframework.core.SenderResult;
import org.frankframework.core.TimeoutException;
import org.frankframework.lifecycle.LifecycleException;
import org.frankframework.parameters.IParameter;
import org.frankframework.parameters.ParameterList;
import org.frankframework.stream.Message;

/**
 * MLLP sender for Frank!Framework.
 *
 * <p>Maintains a persistent TCP connection to a remote MLLP endpoint defined by the bound
 * resource. On each {@link #sendMessage} call the message is written as an MLLP-framed
 * payload and the MLLP-framed ACK is read back and returned.</p>
 *
 * <p>HL7v2 ↔ XML conversion is not performed here; use {@code XmlToHl7v2Pipe} before this
 * sender and {@code Hl7v2ToXmlPipe} after if you want to work in XML throughout the pipeline.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * <SenderPipe name="sendMllp">
 *   <MllpSender name="mllpSender" resourceName="outbound-ris" />
 *   <Forward name="success" path="Exit"/>
 *   <Forward name="exception" path="Error"/>
 * </SenderPipe>
 * }</pre>
 */
public class MllpSender extends MllpFacade implements ISenderWithParameters {

    private static final Logger log = LogManager.getLogger(MllpSender.class);

    private static final byte START_BLOCK     = MllpListener.START_BLOCK;
    private static final byte END_BLOCK       = MllpListener.END_BLOCK;
    private static final byte CARRIAGE_RETURN = MllpListener.CARRIAGE_RETURN;

    private final @NonNull ParameterList paramList = new ParameterList();

    private Socket socket;

    @Override
    public void configure() throws ConfigurationException {
        super.configure();
        paramList.configure();
    }

    @Override
    public void start() {
        // Socket is opened lazily on first sendMessage to keep start() fast.
    }

    @Override
    public void stop() {
        closeSocket();
    }

    @Override
    public void addParameter(IParameter p) {
        paramList.add(p);
    }

    @Override
    public @NonNull ParameterList getParameterList() {
        return paramList;
    }

    @Override
    public boolean isSynchronous() {
        return true;
    }

    @Override
    public @NonNull SenderResult sendMessage(@NonNull Message message, @NonNull PipeLineSession session)
            throws SenderException, TimeoutException {

        MllpConnectionFactory factory = getConnectionFactory();
        String charset = factory.getCharset();

        try {
            ensureConnected(factory);

            String payload = message.asString();
            OutputStream out = socket.getOutputStream();
            out.write(START_BLOCK);
            out.write(payload.getBytes(Charset.forName(charset)));
            out.write(END_BLOCK);
            out.write(CARRIAGE_RETURN);
            out.flush();

            String ack = readMllpMessage(socket.getInputStream(), charset);
            return new SenderResult(new Message(ack));

        } catch (java.net.SocketTimeoutException e) {
            closeSocket();
            throw new TimeoutException(getLogPrefix() + "Read timeout waiting for MLLP ACK", e);
        } catch (IOException e) {
            closeSocket();
            throw new SenderException(getLogPrefix() + "MLLP send failed: " + e.getMessage(), e);
        }
    }

    private synchronized void ensureConnected(MllpConnectionFactory factory) throws IOException {
        if (socket != null && !socket.isClosed() && socket.isConnected()) return;
        closeSocket();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(factory.getHost(), factory.getPort()), factory.getConnectTimeout());
        s.setSoTimeout(factory.getSocketTimeout());
        socket = s;
        log.debug("{} Connected to {}:{}", getLogPrefix(), factory.getHost(), factory.getPort());
    }

    private void closeSocket() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
    }

    private String readMllpMessage(InputStream in, String charset) throws IOException {
        int b;
        while (true) {
            b = in.read();
            if (b == -1) throw new IOException("Stream closed waiting for MLLP start block");
            if ((byte) b == START_BLOCK) break;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean gotEndBlock = false;
        while (true) {
            b = in.read();
            if (b == -1) throw new IOException("Stream closed inside MLLP frame");
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
        return baos.toString(Charset.forName(charset));
    }
}
