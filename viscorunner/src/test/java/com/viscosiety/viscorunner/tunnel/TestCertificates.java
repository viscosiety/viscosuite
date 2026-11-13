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
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Test helper: a self-signed certificate for localhost as a PKCS12 file, as a PEM pair, and a client that trusts it. */
public final class TestCertificates {

    public static final String ALIAS = "tunnel";

    private TestCertificates() {
    }

    /** Creates tunnel.p12 in dir (certificate and key, subject and SAN localhost) and returns it. */
    public static Path pkcs12(Path dir, String password) throws Exception {
        Path keystore = dir.resolve("tunnel.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", password, "-keypass", password)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        return keystore;
    }

    /** Writes a certificate and an unencrypted PKCS#8 key as PEM files; returns {certificate, key}. */
    public static Path[] pemPair(Path dir) throws Exception {
        String password = "pem-export";
        return pemPair(dir, pkcs12(Files.createTempDirectory(dir, "pem-source"), password), password);
    }

    /** Same, exporting the certificate and key of an existing keystore (so a client trusting it trusts the PEM pair too). */
    public static Path[] pemPair(Path dir, Path keystore, String password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password.toCharArray());
        }
        Certificate certificate = store.getCertificate(ALIAS);
        Key key = store.getKey(ALIAS, password.toCharArray());
        Base64.Encoder encoder = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        Path cert = dir.resolve("tunnel.crt");
        Path keyFile = dir.resolve("tunnel.key");
        Files.writeString(cert, "-----BEGIN CERTIFICATE-----\n" + encoder.encodeToString(certificate.getEncoded()) + "\n-----END CERTIFICATE-----\n");
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\n" + encoder.encodeToString(key.getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        return new Path[] {cert, keyFile};
    }

    /** An HTTP client that trusts the certificate in the given keystore and does not follow redirects. */
    public static HttpClient client(Path pkcs12, String password) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(pkcs12)) {
            trust.load(in, password.toCharArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ssl).followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
