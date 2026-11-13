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

package com.viscosiety.viscorunner.tunnel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;

/**
 * Server lifecycle listener that opens one HTTPS tunnel port per valid entry of the settings file, at
 * Server BEFORE_INIT so the port is bound with the rest of the Service. No settings file: nothing is
 * opened. Publishes the ports it opened as the system property {@link #PORTS_PROPERTY}. Uses only the
 * JDK and Tomcat (shared jar).
 */
public class TunnelPortListener implements LifecycleListener {

    public static final String PORTS_PROPERTY = "visco.tunnel.api.ports";

    private static final Logger LOG = Logger.getLogger(TunnelPortListener.class.getName());

    private final Path file;
    private final List<Connector> opened = new ArrayList<>();

    public TunnelPortListener() {
        this.file = Path.of(System.getProperty(TunnelPortSettings.FILE_PROPERTY, TunnelPortSettings.DEFAULT_FILE));
    }

    public List<Connector> opened() {
        return Collections.unmodifiableList(opened);
    }

    @Override
    public void lifecycleEvent(LifecycleEvent event) {
        if (!Lifecycle.BEFORE_INIT_EVENT.equals(event.getType()) || !(event.getLifecycle() instanceof Server server)) {
            return;
        }
        TunnelPortSettings.Result result = TunnelPortSettings.read(file);
        for (TunnelPortSettings.Refused refused : result.refused()) {
            LOG.log(Level.SEVERE, "tunnel-api: port {0} not opened: {1}", new Object[] {refused.port(), refused.reason()});
        }
        StringBuilder ports = new StringBuilder();
        Service[] services = server.findServices();
        if (!result.ports().isEmpty() && services.length == 0) {
            LOG.log(Level.SEVERE, "tunnel-api: ports are configured but the server has no Service; no port opened");
        }
        if (!result.ports().isEmpty() && services.length > 0) {
            for (TunnelPortSettings.Port port : result.ports()) {
                String portText = String.valueOf(port.port());
                String busy = busyReason(port.port());
                if (busy != null) {
                    LOG.log(Level.SEVERE, "tunnel-api: port {0} not opened: {1}", new Object[] {portText, busy});
                    continue;
                }
                try {
                    Connector connector = connector(port);
                    services[0].addConnector(connector);
                    opened.add(connector);
                    if (ports.length() > 0) {
                        ports.append(',');
                    }
                    ports.append(port.port());
                } catch (RuntimeException e) {
                    LOG.log(Level.SEVERE, "tunnel-api: port {0} not opened: the port could not be set up ({1})",
                            new Object[] {portText, e.getClass().getSimpleName()});
                }
            }
        }
        if (ports.length() > 0) {
            System.setProperty(PORTS_PROPERTY, ports.toString());
            LOG.log(Level.INFO, "tunnel-api: HTTPS on port(s) {0}", ports.toString().replace(",", ", "));
        } else {
            System.clearProperty(PORTS_PROPERTY);
        }
    }

    /** Null when the port can be bound now; else the refusal reason. */
    private static String busyReason(int port) {
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
            probe.bind(new java.net.InetSocketAddress(port));
            return null;
        } catch (java.net.BindException e) {
            return "the port is already in use";
        } catch (java.io.IOException | RuntimeException e) {
            return "the port cannot be bound";
        }
    }

    private static Connector connector(TunnelPortSettings.Port port) {
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port.port());
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("SSLEnabled", "true");
        SSLHostConfig host = new SSLHostConfig();
        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.UNDEFINED);
        if (port.keystoreFile() != null) {
            certificate.setCertificateKeystoreFile(port.keystoreFile());
            certificate.setCertificateKeystorePassword(port.keystorePassword());
            certificate.setCertificateKeystoreType(port.keystoreType());
        } else {
            certificate.setCertificateFile(port.certificateFile());
            certificate.setCertificateKeyFile(port.certificateKeyFile());
            if (port.certificateChainFile() != null) {
                certificate.setCertificateChainFile(port.certificateChainFile());
            }
        }
        host.addCertificate(certificate);
        connector.addSslHostConfig(host);
        return connector;
    }
}
