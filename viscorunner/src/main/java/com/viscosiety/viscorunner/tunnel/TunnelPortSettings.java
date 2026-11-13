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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Reads the settings file that says which tunnel ports to open and with which certificate. Uses only the
 * JDK: this class ends up in the shared jar on Tomcat's common class loader. A bad entry is reported in
 * {@link Result#refused()} with a fixed reason (never a password or file content) and skipped; this
 * class never throws.
 */
public final class TunnelPortSettings {

    public static final String FILE_PROPERTY = "visco.tunnel.api.connectors";
    public static final String DEFAULT_FILE = "/opt/frank/tunnel-api/connectors.properties";

    static final String BAD_PORT = "not a usable port number (1024-65535, not 8005, 8009 or 8080)";

    /** One usable tunnel port. Exactly one of the two forms is filled. */
    public record Port(int port, String keystoreFile, String keystorePassword, String keystoreType,
                       String certificateFile, String certificateKeyFile, String certificateChainFile) {
        /** Never prints the keystore password or a file name: the port number and which form it uses. */
        @Override
        public String toString() {
            return "Port[" + port + ", " + (keystoreFile != null ? "keystore" : "certificate") + "]";
        }
    }

    /** A port that was asked for and cannot be opened; reason is safe to log. */
    public record Refused(String port, String reason) {
    }

    public record Result(List<Port> ports, List<Refused> refused) {
    }

    private TunnelPortSettings() {
    }

    /** Absent file: empty result. Never throws. */
    public static Result read(Path file) {
        List<Port> ports = new ArrayList<>();
        List<Refused> refused = new ArrayList<>();
        try {
            if (file == null || !Files.exists(file)) {
                return new Result(ports, refused);
            }
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            }
            String list = properties.getProperty("ports", "");
            Set<String> seen = new HashSet<>();
            for (String raw : list.split(",")) {
                String token = raw.trim();
                if (token.isEmpty()) {
                    continue;
                }
                try {
                    String reason = readPort(token, properties, seen, ports);
                    if (reason != null) {
                        refused.add(new Refused(token, reason));
                    }
                } catch (RuntimeException e) {
                    refused.add(new Refused(token, "the entry cannot be read"));
                }
            }
        } catch (IOException | RuntimeException e) {
            return new Result(Collections.emptyList(), List.of(new Refused("*", "the settings file cannot be read")));
        }
        return new Result(ports, refused);
    }

    /** Returns the refusal reason, or null after adding the port to {@code ports}. */
    private static String readPort(String token, Properties p, Set<String> seen, List<Port> ports) {
        int number;
        try {
            number = Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return BAD_PORT;
        }
        if (number < 1024 || number > 65535 || number == 8005 || number == 8009 || number == 8080) {
            return BAD_PORT;
        }
        if (!seen.add(String.valueOf(number))) {
            return "listed twice";
        }
        String keystoreFile = value(p, token, "keystoreFile");
        String certificateFile = value(p, token, "certificateFile");
        String certificateKeyFile = value(p, token, "certificateKeyFile");
        String chainFile = value(p, token, "certificateChainFile");
        boolean keystoreForm = keystoreFile != null || value(p, token, "keystorePasswordFile") != null;
        boolean pemForm = certificateFile != null || certificateKeyFile != null;
        if (keystoreForm && pemForm) {
            return "give a keystore or a certificate and key, not both";
        }
        if (!keystoreForm && !pemForm) {
            return "no keystore and no certificate given";
        }
        if (keystoreForm) {
            return readKeystorePort(number, keystoreFile, value(p, token, "keystorePasswordFile"),
                    value(p, token, "keystoreType"), ports);
        }
        if (certificateFile == null || !readable(certificateFile)) {
            return "the certificate file does not exist";
        }
        if (certificateKeyFile == null || !readable(certificateKeyFile)) {
            return "the key file does not exist";
        }
        if (chainFile != null && !readable(chainFile)) {
            return "the certificate chain file does not exist";
        }
        ports.add(new Port(number, null, null, null, certificateFile, certificateKeyFile, chainFile));
        return null;
    }

    private static String readKeystorePort(int number, String keystoreFile, String passwordFile, String type, List<Port> ports) {
        if (keystoreFile == null || !readable(keystoreFile)) {
            return "the keystore file does not exist";
        }
        if (passwordFile == null || !readable(passwordFile)) {
            return "the password file does not exist";
        }
        String keystoreType = type == null ? "PKCS12" : type.toUpperCase(java.util.Locale.ROOT);
        if (!keystoreType.equals("PKCS12") && !keystoreType.equals("JKS")) {
            return "the keystore type must be PKCS12 or JKS";
        }
        String password;
        try {
            password = Files.readString(Path.of(passwordFile), StandardCharsets.UTF_8).replaceFirst("(\\r?\\n)+$", "");
        } catch (IOException e) {
            return "the password file does not exist";
        }
        KeyStore store;
        try {
            store = KeyStore.getInstance(keystoreType);
            try (InputStream in = Files.newInputStream(Path.of(keystoreFile))) {
                store.load(in, password.toCharArray());
            }
            Enumeration<String> aliases = store.aliases();
            boolean hasKey = false;
            while (aliases.hasMoreElements()) {
                if (store.isKeyEntry(aliases.nextElement())) {
                    hasKey = true;
                    break;
                }
            }
            if (!hasKey) {
                return "the keystore holds no private key";
            }
        } catch (IOException e) {
            return "the keystore does not open with this password";
        } catch (java.security.GeneralSecurityException e) {
            return "the keystore cannot be read";
        }
        ports.add(new Port(number, keystoreFile, password, keystoreType, null, null, null));
        return null;
    }

    private static String value(Properties p, String port, String key) {
        String v = p.getProperty(port + "." + key);
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        return v.trim();
    }

    private static boolean readable(String file) {
        Path path = Path.of(file);
        return Files.isRegularFile(path) && Files.isReadable(path);
    }
}
