package com.viscosiety.viscorunner.spike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;

/**
 * SPIKE (2026-11-12, tunnel API): a Server LifecycleListener that adds one TLS connector per tunnel
 * port at Server BEFORE_INIT, so the connector is initialised (port bound) and started with the rest
 * of the Service. No setting = no connector. Not production code: the real listener would read its
 * setting from a file or an environment variable; here it gets the list in its constructor.
 */
public class TunnelConnectorSpikeListener implements LifecycleListener {

    /** One tunnel port: either a PKCS12 keystore, or a PEM certificate + key pair. */
    public record TunnelPort(int port, String keystoreFile, String keystorePassword,
                             String pemCertificateFile, String pemKeyFile) {
        public static TunnelPort pkcs12(int port, String file, String password) {
            return new TunnelPort(port, file, password, null, null);
        }

        public static TunnelPort pem(int port, String certificateFile, String keyFile) {
            return new TunnelPort(port, null, null, certificateFile, keyFile);
        }
    }

    private final List<TunnelPort> ports;
    private final List<Connector> added = new ArrayList<>();

    public TunnelConnectorSpikeListener(List<TunnelPort> ports) {
        this.ports = ports == null ? List.of() : List.copyOf(ports);
    }

    public List<Connector> addedConnectors() {
        return Collections.unmodifiableList(added);
    }

    @Override
    public void lifecycleEvent(LifecycleEvent event) {
        if (!Lifecycle.BEFORE_INIT_EVENT.equals(event.getType()) || !(event.getLifecycle() instanceof Server server)) {
            return;
        }
        if (ports.isEmpty()) {
            return; // the setting is absent: nothing listens on any tunnel port
        }
        Service service = server.findServices()[0];
        for (TunnelPort port : ports) {
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
                certificate.setCertificateKeystoreType("PKCS12");
            } else {
                certificate.setCertificateFile(port.pemCertificateFile());
                certificate.setCertificateKeyFile(port.pemKeyFile());
            }
            host.addCertificate(certificate);
            connector.addSslHostConfig(host);
            service.addConnector(connector);
            added.add(connector);
        }
    }
}
