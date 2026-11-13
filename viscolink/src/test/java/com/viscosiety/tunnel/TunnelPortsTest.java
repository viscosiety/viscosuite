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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TunnelPortsTest {

    private String saved;

    @BeforeEach
    void save() {
        saved = System.getProperty(TunnelPorts.PORTS_PROPERTY);
    }

    @AfterEach
    void restore() {
        if (saved == null) {
            System.clearProperty(TunnelPorts.PORTS_PROPERTY);
        } else {
            System.setProperty(TunnelPorts.PORTS_PROPERTY, saved);
        }
    }

    @Test
    void noPropertyMeansNoTunnelPort() {
        System.clearProperty(TunnelPorts.PORTS_PROPERTY);
        assertFalse(TunnelPorts.isTunnelPort(8443));
    }

    @Test
    void listedPortsAreTunnelPorts() {
        System.setProperty(TunnelPorts.PORTS_PROPERTY, "8443,9443");
        assertTrue(TunnelPorts.isTunnelPort(8443));
        assertTrue(TunnelPorts.isTunnelPort(9443));
        assertFalse(TunnelPorts.isTunnelPort(8080));
    }

    @Test
    void badPartsAreIgnored() {
        System.setProperty(TunnelPorts.PORTS_PROPERTY, "8443, x,,");
        assertTrue(TunnelPorts.isTunnelPort(8443));
        assertFalse(TunnelPorts.isTunnelPort(0));
        assertTrue(TunnelPorts.ports().size() == 1);
    }
}
