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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.viscosiety.viscorunner.tunnel.TunnelPortSettings.Refused;
import com.viscosiety.viscorunner.tunnel.TunnelPortSettings.Result;

class TunnelPortSettingsTest {

    private static final String BAD_PORT = "not a usable port number (1024-65535, not 8005, 8009 or 8080)";

    @TempDir
    Path dir;

    private Path settings(String... lines) throws Exception {
        Path file = dir.resolve("connectors.properties");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private Path passwordFile(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private void assertRefused(Result result, String port, String reason) {
        assertEquals(List.of(new Refused(port, reason)), result.refused());
    }

    @Test
    void anAbsentFileGivesAnEmptyResult() {
        Result result = TunnelPortSettings.read(dir.resolve("nothing.properties"));
        assertTrue(result.ports().isEmpty());
        assertTrue(result.refused().isEmpty());
    }

    @Test
    void aPkcs12KeystoreWithAPasswordFile() throws Exception {
        Path keystore = TestCertificates.pkcs12(dir, "secret");
        Path password = passwordFile("pw", "secret\n");
        Result result = TunnelPortSettings.read(settings("ports=8443",
                "8443.keystoreFile=" + keystore, "8443.keystorePasswordFile=" + password));
        assertTrue(result.refused().isEmpty(), result.refused().toString());
        assertEquals(1, result.ports().size());
        TunnelPortSettings.Port port = result.ports().get(0);
        assertEquals(8443, port.port());
        assertEquals(keystore.toString(), port.keystoreFile());
        assertEquals("secret", port.keystorePassword());
        assertEquals("PKCS12", port.keystoreType());
        assertNull(port.certificateFile());
    }

    @Test
    void aPemPair() throws Exception {
        Path[] pem = TestCertificates.pemPair(dir);
        Result result = TunnelPortSettings.read(settings("ports=8443",
                "8443.certificateFile=" + pem[0], "8443.certificateKeyFile=" + pem[1]));
        assertTrue(result.refused().isEmpty(), result.refused().toString());
        TunnelPortSettings.Port port = result.ports().get(0);
        assertEquals(pem[0].toString(), port.certificateFile());
        assertEquals(pem[1].toString(), port.certificateKeyFile());
        assertNull(port.certificateChainFile());
        assertNull(port.keystoreFile());
        assertNull(port.keystorePassword());
        assertNull(port.keystoreType());
    }

    @Test
    void oneBadPortDoesNotStopTheOther() throws Exception {
        Path keystore = TestCertificates.pkcs12(dir, "secret");
        Path password = passwordFile("pw", "secret");
        Result result = TunnelPortSettings.read(settings("ports=8443,9443",
                "8443.keystoreFile=" + keystore, "8443.keystorePasswordFile=" + password,
                "9443.keystoreFile=" + dir.resolve("missing.p12"), "9443.keystorePasswordFile=" + password));
        assertEquals(1, result.ports().size());
        assertEquals(8443, result.ports().get(0).port());
        assertRefused(result, "9443", "the keystore file does not exist");
    }

    @Test
    void aWrongPassword() throws Exception {
        Path keystore = TestCertificates.pkcs12(dir, "secret");
        Path password = passwordFile("pw", "wrong-password");
        Result result = TunnelPortSettings.read(settings("ports=8443",
                "8443.keystoreFile=" + keystore, "8443.keystorePasswordFile=" + password));
        assertTrue(result.ports().isEmpty());
        assertRefused(result, "8443", "the keystore does not open with this password");
    }

    @Test
    void aKeystoreWithACertificateButNoPrivateKey() throws Exception {
        Path source = TestCertificates.pkcs12(dir, "secret");
        KeyStore full = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(source)) {
            full.load(in, "secret".toCharArray());
        }
        Certificate certificate = full.getCertificate(TestCertificates.ALIAS);
        KeyStore certOnly = KeyStore.getInstance("PKCS12");
        certOnly.load(null, null);
        certOnly.setCertificateEntry("only-cert", certificate);
        Path keystore = dir.resolve("cert-only.p12");
        try (var out = Files.newOutputStream(keystore)) {
            certOnly.store(out, "secret".toCharArray());
        }
        Path password = passwordFile("pw", "secret");
        Result result = TunnelPortSettings.read(settings("ports=8443",
                "8443.keystoreFile=" + keystore, "8443.keystorePasswordFile=" + password));
        assertTrue(result.ports().isEmpty());
        assertRefused(result, "8443", "the keystore holds no private key");
    }

    @Test
    void unusablePortNumbers() throws Exception {
        for (String bad : List.of("8080", "80", "70000", "abc", "8005", "8009", "1023")) {
            Result result = TunnelPortSettings.read(settings("ports=" + bad));
            assertTrue(result.ports().isEmpty(), bad);
            assertRefused(result, bad, BAD_PORT);
        }
    }

    @Test
    void aPortListedTwiceIsOpenedOnce() throws Exception {
        Path keystore = TestCertificates.pkcs12(dir, "secret");
        Path password = passwordFile("pw", "secret");
        Result result = TunnelPortSettings.read(settings("ports=8443,8443",
                "8443.keystoreFile=" + keystore, "8443.keystorePasswordFile=" + password));
        assertEquals(1, result.ports().size());
        assertRefused(result, "8443", "listed twice");
    }

    @Test
    void bothFormsForOnePort() throws Exception {
        Path keystore = TestCertificates.pkcs12(dir, "secret");
        Path password = passwordFile("pw", "secret");
        Path[] pem = TestCertificates.pemPair(dir);
        Result result = TunnelPortSettings.read(settings("ports=8443",
                "8443.keystoreFile=" + keystore, "8443.keystorePasswordFile=" + password,
                "8443.certificateFile=" + pem[0], "8443.certificateKeyFile=" + pem[1]));
        assertTrue(result.ports().isEmpty());
        assertRefused(result, "8443", "give a keystore or a certificate and key, not both");
    }

    @Test
    void neitherForm() throws Exception {
        Result result = TunnelPortSettings.read(settings("ports=8443"));
        assertRefused(result, "8443", "no keystore and no certificate given");
    }

    @Test
    void aPemPairWithoutItsKeyFile() throws Exception {
        Path[] pem = TestCertificates.pemPair(dir);
        Result result = TunnelPortSettings.read(settings("ports=8443",
                "8443.certificateFile=" + pem[0], "8443.certificateKeyFile=" + dir.resolve("missing.key")));
        assertRefused(result, "8443", "the key file does not exist");
    }

    @Test
    void aSettingsFileThatCannotBeRead() {
        Result result = TunnelPortSettings.read(dir); // a directory
        assertTrue(result.ports().isEmpty());
        assertRefused(result, "*", "the settings file cannot be read");
    }

    @Test
    void noReasonCarriesAPasswordOrAPath() throws Exception {
        Path keystore = TestCertificates.pkcs12(dir, "secret");
        Path password = passwordFile("pw", "topsecret-value");
        Result result = TunnelPortSettings.read(settings("ports=8443,9443",
                "8443.keystoreFile=" + keystore, "8443.keystorePasswordFile=" + password,
                "9443.keystoreFile=" + dir.resolve("missing.p12"), "9443.keystorePasswordFile=" + password));
        assertFalse(result.refused().isEmpty());
        for (Refused refused : result.refused()) {
            assertFalse(refused.reason().contains("topsecret-value"));
            assertFalse(refused.reason().contains(dir.toString()));
        }
    }

    @Test
    void thePortsToStringNeverShowsThePasswordOrAFileName() throws Exception {
        Path keystore = TestCertificates.pkcs12(dir, "hunter2");
        Path password = passwordFile("pw", "hunter2\n");
        Result result = TunnelPortSettings.read(settings("ports=8443",
                "8443.keystoreFile=" + keystore, "8443.keystorePasswordFile=" + password));
        assertEquals("Port[8443, keystore]", result.ports().get(0).toString());
        assertFalse(result.ports().toString().contains("hunter2"));
    }

    @Test
    void aPortWrittenTwiceWithALeadingZeroIsListedTwice() throws Exception {
        Path keystore = TestCertificates.pkcs12(dir, "secret");
        Path password = passwordFile("pw", "secret\n");
        Result result = TunnelPortSettings.read(settings("ports=8443,08443",
                "8443.keystoreFile=" + keystore, "8443.keystorePasswordFile=" + password,
                "08443.keystoreFile=" + keystore, "08443.keystorePasswordFile=" + password));
        assertEquals(1, result.ports().size());
        assertRefused(result, "08443", "listed twice");
    }
}
