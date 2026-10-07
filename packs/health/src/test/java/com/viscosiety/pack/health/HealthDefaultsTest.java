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

package com.viscosiety.pack.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

class HealthDefaultsTest {

    /**
     * The pack's test class path holds TWO files named DeploymentSpecifics.properties: the pack's own
     * and the core's. getResourceAsStream would return the pack's; enumerate both and tell them apart by
     * where they come from: the pack's copy lives under the location the pack's own classes were loaded
     * from (target/classes or the pack jar), the core's under whatever else the core resolves to
     * (viscolink-...-classes.jar, or viscolink/target/classes in a reactor-only run).
     */
    private static Properties load(boolean pack) throws Exception {
        String packLocation = HealthPack.class.getProtectionDomain().getCodeSource().getLocation().toString();
        Enumeration<URL> all = HealthDefaultsTest.class.getClassLoader().getResources("DeploymentSpecifics.properties");
        Properties found = null;
        while (all.hasMoreElements()) {
            URL url = all.nextElement();
            boolean isPack = url.toString().startsWith(packLocation) || url.toString().startsWith("jar:" + packLocation);
            if (isPack != pack) {
                continue;
            }
            assertNull(found, "more than one " + (pack ? "pack" : "core") + " copy: " + url);
            found = new Properties();
            try (InputStream in = url.openStream()) {
                found.load(in);
            }
        }
        assertTrue(found != null, "no " + (pack ? "pack" : "core") + " DeploymentSpecifics.properties on the class path");
        return found;
    }

    @Test
    void thePackDefaultsAreTodaysHealthKeysWithTodaysValues() throws Exception {
        Properties p = load(true);
        assertEquals(Set.of("mllp.inbound.sendingApplication", "mllp.inbound.sendingFacility",
                "fhir.target.version", "viscostore.fhir.base.url", "mr.system.base"), p.stringPropertyNames());
        assertEquals("viscolink", p.getProperty("mllp.inbound.sendingApplication"));
        assertEquals("viscosiety", p.getProperty("mllp.inbound.sendingFacility"));
        assertEquals("r4", p.getProperty("fhir.target.version"));
        assertEquals("http://localhost:8080/viscostore/fhir/", p.getProperty("viscostore.fhir.base.url"));
        assertEquals("https://ig.viscosiety.com/fhir/NamingSystem/", p.getProperty("mr.system.base"));
    }

    @Test
    void thePackAddsOnlyKeysTheCoreDoesNotDefine() throws Exception {
        Properties pack = load(true);
        Properties core = load(false);   // the core's, from viscolink:classes (provided)
        for (String key : pack.stringPropertyNames()) {
            assertNull(core.getProperty(key), "the core already defines " + key + "; a pack may only add keys");
        }
    }
}
