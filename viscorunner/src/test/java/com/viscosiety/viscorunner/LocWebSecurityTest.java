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

package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The runner image runs on {@code dtap.stage=LOC}. On that stage Frank!Framework (10.3) switches web
 * security off unless {@code application.security.http.authentication} is set
 * ({@code SecuritySettings.setupDefaultSecuritySettings}): every servlet then gets authenticator
 * {@code NONE}, the anonymous user gets every role, and Larva's file save and scenario root let anyone
 * who reaches the port write files and run classes. LOC also sets {@code csrf.enabled=false}.
 *
 * <p>The image keeps the stage (Larva's local conveniences stay) and turns both back on. This test
 * pins that, and that the repository's compose files that run the runtime on LOC give it a login
 * whose password comes from {@code .env}, never from git.
 */
class LocWebSecurityTest {

    private static final Path PROPERTIES = Path.of("src/scripts/catalinaAdditional.properties");

    private static Map<String, String> properties() throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(PROPERTIES)) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            int eq = l.indexOf('=');
            if (eq > 0) {
                map.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
            }
        }
        return map;
    }

    /** Frank!Framework's rule for whether web security is on (SecuritySettings, 10.3). */
    private static boolean webSecurityEnabled(Map<String, String> p) {
        boolean loc = "LOC".equalsIgnoreCase(p.get("dtap.stage"));
        String auth = p.get("application.security.http.authentication");
        return auth != null && !auth.isEmpty() ? Boolean.parseBoolean(auth) : !loc;
    }

    @Test
    void imageRunsOnLocWithWebSecurityOn() throws IOException {
        Map<String, String> p = properties();
        assertEquals("LOC", p.get("dtap.stage"), "the image keeps stage LOC for Larva's local conveniences");
        assertEquals("true", p.get("application.security.http.authentication"));
        assertTrue(webSecurityEnabled(p), "web security must be on although the stage is LOC");
    }

    @Test
    void imageTurnsCsrfBackOn() throws IOException {
        assertEquals("true", properties().get("csrf.enabled"), "StageSpecifics_LOC sets csrf.enabled=false");
    }

    @Test
    void imageOpensNoServletAndDisablesNoSecurity() throws IOException {
        for (Map.Entry<String, String> e : properties().entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key.startsWith("servlet.") && key.endsWith(".authenticator")) {
                assertFalse("NONE".equalsIgnoreCase(value), key + " opens a servlet to anyone");
            }
            if (key.equals("application.security.http.authenticator")) {
                assertFalse("NONE".equalsIgnoreCase(value), "the default authenticator must not be NONE");
            }
        }
    }

    // ---- compose files -------------------------------------------------------------------------

    /** Compose files that run the runtime (viscolink webapp) with the stage set to LOC. */
    private static final List<String> RUNTIME_COMPOSE_FILES =
        List.of("docker-compose.yml", "docker-compose.viscolink.yml", "docker-compose.core.yml");

    private static final Pattern FROM_ENV = Pattern.compile("\\$\\{VISCO_ADMIN_PASSWORD:\\?[^}]+}");

    private static String compose(String name) throws IOException {
        return Files.readString(Path.of(name));
    }

    private static String value(String compose, String key) {
        Matcher m = Pattern.compile("(?m)^\\s+" + Pattern.quote(key) + ":\\s*(.+?)\\s*$").matcher(compose);
        return m.find() ? m.group(1).replaceAll("^\"|\"$", "") : null;
    }

    @Test
    void composeFilesGiveTheRuntimeALoginFromEnv() throws IOException {
        for (String name : RUNTIME_COMPOSE_FILES) {
            String c = compose(name);
            assertEquals("LOC", value(c, "dtap.stage"), name);
            assertEquals("local", value(c, "application.security.http.authenticator"), name);
            assertEquals("local", value(c, "application.security.http.authenticators"), name);
            assertEquals("IN_MEMORY", value(c, "application.security.http.authenticators.local.type"), name);
            assertEquals("IN_MEMORY", value(c, "application.security.console.authentication.type"), name);
            for (String key : List.of("application.security.http.authenticators.local.password",
                    "application.security.console.authentication.password")) {
                String v = value(c, key);
                assertTrue(v != null && FROM_ENV.matcher(v).matches(),
                    name + ": " + key + " must come from VISCO_ADMIN_PASSWORD in .env, not from git (" + v + ")");
            }
            assertFalse(c.contains("application.security.http.authentication: \"false\"")
                    || c.contains("application.security.http.authentication: false"),
                name + " switches web security off");
        }
    }
}
