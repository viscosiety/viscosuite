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

package com.viscosiety.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** The core's own DeploymentSpecifics.properties (the core's test class path holds no pack, so there is one copy). */
class CoreDefaultsTest {

    private static Properties core() throws Exception {
        try (InputStream in = CoreDefaultsTest.class.getResourceAsStream("/DeploymentSpecifics.properties")) {
            Properties p = new Properties();
            p.load(in);
            return p;
        }
    }

    @Test
    void theCoreDefaultsNameNoHealthKey() throws Exception {
        Properties p = core();
        for (String key : p.stringPropertyNames()) {
            assertFalse(key.startsWith("mllp.") || key.startsWith("fhir.") || key.startsWith("viscostore.") || key.startsWith("mr.system"),
                    "health key in the core defaults: " + key);
        }
    }

    @Test
    void customViewsTakeThePacksNamesThroughTheIndirection() throws Exception {
        assertEquals("viscoLink,${pack.customViews.names:-}", core().getProperty("customViews.names"));
    }

    /**
     * The console's Browse JDBC Table page is denied outright. The Frank!Framework ships
     * {@code browseJdbcTable.permission.rules=* * allow}, so the key being ABSENT here is not a
     * neutral state: it hands any IbisDataAdmin, IbisAdmin or IbisTester arbitrary table reads on
     * every datasource. Blank is the deny-all, because {@code BrowseJdbcTable.readAllowed}
     * short-circuits on a blank value before parsing any rule.
     */
    @Test
    void browseJdbcTableIsDeniedOutright() throws Exception {
        Properties p = core();
        assertTrue(p.containsKey("browseJdbcTable.permission.rules"),
                "the key must be present: absent means the framework's '* * allow' default applies");
        assertEquals("", p.getProperty("browseJdbcTable.permission.rules"),
                "blank is the deny-all; any rule here would re-open the endpoint");
    }
}
