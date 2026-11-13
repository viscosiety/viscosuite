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

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.frankframework.core.ListenerException;
import org.frankframework.core.PipeLineSession;
import org.frankframework.http.rest.ApiListener;
import org.frankframework.stream.Message;

/**
 * An {@link ApiListener} that answers on an HTTPS tunnel port only: a system at a customer's site reaches it
 * through the account's private tunnel, and the public address answers 404 for it. Same attributes as
 * ApiListener. Use it as {@code <Listener className="com.viscosiety.tunnel.TunnelApiListener" .../>}.
 * The same uriPattern and method cannot be declared on an ApiListener as well.
 *
 * <p>This listener is the boundary: it refuses, before any pipeline runs, a request that did not arrive on a tunnel
 * port ({@code getLocalPort()}, which comes from the socket), unless it is a FORWARD dispatch (the platform's own
 * gateway servlet forwards to {@code /api}). The refusal happens after the servlet's login, content-type, ETag and
 * body-reading steps and before any pipeline runs. A session without a servlet request is refused too. The public-side
 * filter ({@link TunnelOnlyApiFilter}) only makes the answer a clean 404 and keeps the OpenAPI document clean.
 */
public class TunnelApiListener extends ApiListener {

    static final String REFUSAL = "This endpoint is not available here.";
    private static final Logger LOG = LogManager.getLogger(TunnelApiListener.class);

    @Override
    public Message processRequest(Message rawMessage, PipeLineSession session) throws ListenerException {
        if (!arrivedOnATunnelPort(session)) {
            LOG.warn("tunnel-api: refused a request for tunnel-only listener [{}] that did not arrive on a tunnel port", getName());
            throw new ListenerException(REFUSAL);
        }
        return super.processRequest(rawMessage, session);
    }

    static boolean arrivedOnATunnelPort(PipeLineSession session) {
        Object value = session == null ? null : session.get(PipeLineSession.HTTP_REQUEST_KEY);
        if (!(value instanceof HttpServletRequest request)) {
            return false;
        }
        return request.getDispatcherType() == DispatcherType.FORWARD || TunnelPorts.isTunnelPort(request.getLocalPort());
    }
}
