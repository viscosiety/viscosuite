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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.core.StandardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TunnelPortListenerTest {

    @TempDir
    Path dir;

    private String oldFile;
    private String oldPorts;

    @BeforeEach
    void remember() {
        oldFile = System.getProperty(TunnelPortSettings.FILE_PROPERTY);
        oldPorts = System.getProperty(TunnelPortListener.PORTS_PROPERTY);
    }

    @AfterEach
    void restore() {
        restore(TunnelPortSettings.FILE_PROPERTY, oldFile);
        restore(TunnelPortListener.PORTS_PROPERTY, oldPorts);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static StandardServer server() {
        StandardServer server = new StandardServer();
        server.addService(new StandardService());
        return server;
    }

    private static void fire(TunnelPortListener listener, StandardServer server) {
        listener.lifecycleEvent(new LifecycleEvent(server, Lifecycle.BEFORE_INIT_EVENT, null));
    }

    @Test
    void oneGoodAndOneBadPort() throws Exception {
        Path keystore = TestCertificates.pkcs12(dir, "secret");
        Files.writeString(dir.resolve("pw"), "secret\n");
        Path file = dir.resolve("connectors.properties");
        Files.writeString(file, "ports=18443,18444\n"
                + "18443.keystoreFile=" + keystore + "\n18443.keystorePasswordFile=" + dir.resolve("pw") + "\n"
                + "18444.keystoreFile=" + dir.resolve("missing.p12") + "\n18444.keystorePasswordFile=" + dir.resolve("pw") + "\n");
        System.setProperty(TunnelPortSettings.FILE_PROPERTY, file.toString());
        StandardServer server = server();
        TunnelPortListener listener = new TunnelPortListener();
        fire(listener, server);
        assertEquals(1, listener.opened().size());
        assertEquals(1, server.findServices()[0].findConnectors().length);
        assertEquals(18443, server.findServices()[0].findConnectors()[0].getPort());
        assertEquals("18443", System.getProperty(TunnelPortListener.PORTS_PROPERTY));
    }

    @Test
    void withoutAFileNothingIsAddedAndThePropertyIsCleared() {
        System.setProperty(TunnelPortListener.PORTS_PROPERTY, "stale");
        System.setProperty(TunnelPortSettings.FILE_PROPERTY, dir.resolve("none.properties").toString());
        StandardServer server = server();
        TunnelPortListener listener = new TunnelPortListener();
        fire(listener, server);
        assertEquals(0, listener.opened().size());
        assertEquals(0, server.findServices()[0].findConnectors().length);
        assertNull(System.getProperty(TunnelPortListener.PORTS_PROPERTY));
    }

    @Test
    void aPortThatIsAlreadyInUseIsNotOpened() throws Exception {
        Path keystore = TestCertificates.pkcs12(dir, "secret");
        Files.writeString(dir.resolve("pw"), "secret\n");
        try (java.net.ServerSocket busy = new java.net.ServerSocket(0)) {
            int port = busy.getLocalPort();
            Path file = dir.resolve("connectors.properties");
            Files.writeString(file, "ports=" + port + "\n" + port + ".keystoreFile=" + keystore + "\n"
                    + port + ".keystorePasswordFile=" + dir.resolve("pw") + "\n");
            System.setProperty(TunnelPortSettings.FILE_PROPERTY, file.toString());
            StandardServer server = server();
            TunnelPortListener listener = new TunnelPortListener();
            fire(listener, server);
            assertEquals(0, listener.opened().size());
            assertEquals(0, server.findServices()[0].findConnectors().length);
            assertNull(System.getProperty(TunnelPortListener.PORTS_PROPERTY));
        }
    }

    @Test
    void portsWithoutAServiceOpenNothingAndDoNotFail() throws Exception {
        Path keystore = TestCertificates.pkcs12(dir, "secret");
        Files.writeString(dir.resolve("pw"), "secret\n");
        Path file = dir.resolve("connectors.properties");
        Files.writeString(file, "ports=18451\n18451.keystoreFile=" + keystore + "\n18451.keystorePasswordFile=" + dir.resolve("pw") + "\n");
        System.setProperty(TunnelPortSettings.FILE_PROPERTY, file.toString());
        TunnelPortListener listener = new TunnelPortListener();
        fire(listener, new StandardServer());
        assertEquals(0, listener.opened().size());
    }
}
