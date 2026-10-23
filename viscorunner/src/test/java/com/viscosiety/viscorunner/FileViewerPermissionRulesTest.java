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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * {@code FileViewer.permission.rules} decides which files the Frank!Console's file viewer
 * ({@code /iaf/api/file-viewer}) and log directory listing ({@code /iaf/api/logging}) may read, for
 * every console user with an Ibis role. Only the log directory may be readable: {@code /opt/frank}
 * holds the mounted secrets and keystores.
 *
 * <p>{@link #readAllowed} mirrors Frank!Framework's {@code FileUtils.readAllowed} (10.3): rules are
 * {@code <path> <role> <allow|deny>} separated by {@code |}, checked in order against the canonical
 * file name; the first rule whose path is a string prefix of it decides; no match = deny. The rule's
 * {@code ${name:-default}} placeholders are resolved to their default, as on a runner where the
 * credential factory settings are not set (the Kubernetes credential factory).
 */
class FileViewerPermissionRulesTest {

    private static final Path PROPERTIES = Path.of("src/scripts/catalinaAdditional.properties");

    private static String rules() throws IOException {
        String line = Files.readAllLines(PROPERTIES).stream()
            .filter(l -> l.startsWith("FileViewer.permission.rules="))
            .findFirst().orElse(null);
        assertNotNull(line, "catalinaAdditional.properties sets FileViewer.permission.rules");
        String value = line.substring("FileViewer.permission.rules=".length());
        Matcher m = Pattern.compile("\\$\\{[^:}]+:-([^}]*)}").matcher(value);
        return m.replaceAll(r -> Matcher.quoteReplacement(r.group(1)));
    }

    /** Frank!Framework's rule evaluation, with path normalisation standing in for canonicalisation. */
    private static boolean readAllowed(String rules, String fileName) {
        String file = Path.of(fileName).toAbsolutePath().normalize().toString();
        for (String rule : rules.split("\\|")) {
            String[] parts = rule.trim().split("\\s+");
            if (parts.length != 3) {
                continue;
            }
            String path = "*".equals(parts[0]) ? "*" : Path.of(parts[0]).toAbsolutePath().normalize().toString();
            if ("*".equals(path) || file.startsWith(path)) {
                // Every role matches: the console grants the file viewer to any Ibis role.
                if ("allow".equals(parts[2])) {
                    return true;
                }
                if ("deny".equals(parts[2])) {
                    return false;
                }
            }
        }
        return false;
    }

    @Test
    void theLogDirectoryIsReadable() throws IOException {
        String rules = rules();
        assertTrue(readAllowed(rules, "/usr/local/tomcat/logs"));
        assertTrue(readAllowed(rules, "/usr/local/tomcat/logs/viscolink.log"));
        assertTrue(readAllowed(rules, "/usr/local/tomcat/logs/catalina.2026-10-23.log"));
    }

    @Test
    void nothingUnderOptFrankIsReadable() throws IOException {
        String rules = rules();
        for (String path : new String[] {
            "/opt/frank",
            "/opt/frank/secrets",
            "/opt/frank/secrets/POSTGRES_PASSWORD",
            "/opt/frank/secrets/credentials.properties",
            "/opt/frank/keystores",
            "/opt/frank/keystores/tenant/keystore.p12",
            "/opt/frank/config/roleMapping.properties",
            "/opt/frank/configurations/tenant/Configuration.xml",
            "/opt/frank/git-repos/tenant/Configuration.xml",
            "/opt/frank/h2-data/viscolink.mv.db",
        }) {
            assertFalse(readAllowed(rules, path), path + " must not be readable through the file viewer");
        }
    }

    @Test
    void escapingTheLogDirectoryIsRefused() throws IOException {
        String rules = rules();
        assertFalse(readAllowed(rules, "/usr/local/tomcat/logs/../../../../opt/frank/secrets/POSTGRES_PASSWORD"));
        assertFalse(readAllowed(rules, "/usr/local/tomcat/conf/server.xml"));
        assertFalse(readAllowed(rules, "/etc/passwd"));
    }

    @Test
    void theSecretAndKeystoreDenyRulesComeBeforeTheOnlyAllowRule() throws IOException {
        // Canonicalisation resolves symlinks, so a link in the log directory that points at a secret is
        // checked as the secret's own path; it is refused only if a deny rule matches before the allow.
        String rules = rules();
        String[] parts = rules.split("\\|");
        int allows = 0;
        int lastDeny = -1;
        int allow = -1;
        for (int i = 0; i < parts.length; i++) {
            String[] rule = parts[i].trim().split("\\s+");
            assertEquals(3, rule.length, "rule '" + parts[i].trim() + "' has path, role and type");
            if ("allow".equals(rule[2])) {
                allows++;
                allow = i;
                assertEquals("/usr/local/tomcat/logs", rule[0], "the only allow rule is the log directory");
            } else {
                lastDeny = i;
            }
        }
        assertEquals(1, allows, "exactly one allow rule");
        assertTrue(lastDeny < allow, "every deny rule comes before the allow rule");
        assertTrue(rules.contains("/opt/frank/secrets * deny"));
        assertTrue(rules.contains("/opt/frank/keystores * deny"));
    }
}
