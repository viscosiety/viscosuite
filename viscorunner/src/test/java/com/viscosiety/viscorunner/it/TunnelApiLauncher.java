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


package com.viscosiety.viscorunner.it;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.viscosiety.viscorunner.tunnel.TestCertificates;

/**
 * Starts one {@link ViscolinkLauncher} process with the {@code tunnelapi} test configuration and gives the
 * HTTP helpers the tunnel-api ITs share. Every password is generated at run time and masked in printed output.
 * One instance per IT class (each class is one launcher run).
 */
final class TunnelApiLauncher {

    static final String PUBLIC_USER = "public-user";
    static final String SITE_USER = "site-user";

    private static final Path LAUNCHER_LIBS = Paths.get("target/launcher-libs").toAbsolutePath();
    private static final long READY_LIMIT_MS = 180_000;

    final String publicPassword = UUID.randomUUID().toString();
    final String sitePassword = UUID.randomUUID().toString();
    private final String keystorePassword = UUID.randomUUID().toString();
    private final boolean withTunnelSettings;
    private final String tag;
    private final List<String> output = Collections.synchronizedList(new ArrayList<>());
    private final List<Path> tempDirs = new ArrayList<>();
    private Process process;
    private Path childTmp;
    int publicPort;
    int tunnelPort;
    HttpClient https;
    HttpClient http;

    TunnelApiLauncher(String tag, boolean withTunnelSettings) {
        this.tag = tag;
        this.withTunnelSettings = withTunnelSettings;
    }

    private static String lib(String artifactId) {
        return LAUNCHER_LIBS.resolve(artifactId + ".jar").toString();
    }

    /** The arguments of a runtime with a public login and a site login; Off/NONE runs replace parts of it. */
    List<String> bothLogins(boolean tunnelConfidential) {
        List<String> args = new ArrayList<>(List.of(
                "-Dapplication.security.http.authentication=true",
                "-Dapplication.security.http.transportGuarantee=NONE"));
        if (tunnelConfidential) {
            args.add("-Dservlet.tunnelApi.transportGuarantee=CONFIDENTIAL");
        }
        args.addAll(List.of(
                "-Dapplication.security.http.authenticators=publicBasic,tunnelBasic",
                "-Dapplication.security.http.authenticators.publicBasic.type=IN_MEMORY",
                "-Dapplication.security.http.authenticators.publicBasic.username=" + PUBLIC_USER,
                "-Dapplication.security.http.authenticators.publicBasic.password=" + publicPassword,
                "-Dapplication.security.http.authenticators.tunnelBasic.type=IN_MEMORY",
                "-Dapplication.security.http.authenticators.tunnelBasic.username=" + SITE_USER,
                "-Dapplication.security.http.authenticators.tunnelBasic.password=" + sitePassword,
                "-Dservlet.ApiListenerServlet.authenticator=publicBasic",
                "-Dservlet.ApiListenerServlet.securityRoles=IbisTester",
                "-Dservlet.tunnelApi.authenticator=tunnelBasic",
                "-Dservlet.tunnelApi.securityRoles=IbisTester"));
        return args;
    }

    /** Starts the launcher with the given security arguments; returns once it reported its public port. */
    void start(List<String> securityArgs) throws Exception {
        Path viscolinkWar = Paths.get("target/viscolink.war").toAbsolutePath();
        Path configs = Paths.get("src/test/resources/tunnel-api").toAbsolutePath();
        Path testClasses = Paths.get("target/test-classes").toAbsolutePath();
        Path classes = Paths.get("target/classes").toAbsolutePath();
        Path healthOverlay = Paths.get("target/packs/health").toAbsolutePath();
        if (!Files.exists(viscolinkWar)) {
            throw new IllegalStateException("viscolink WAR not found at " + viscolinkWar
                    + " — run `mvn install -pl viscolink,packs/health && mvn package -pl viscorunner` first");
        }

        Path work = Files.createTempDirectory("tunnel-api-it-");
        tempDirs.add(work);
        Path keystore = TestCertificates.pkcs12(work, keystorePassword);
        https = TestCertificates.client(keystore, keystorePassword);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        List<String> tunnelArgs = new ArrayList<>();
        if (withTunnelSettings) {
            Path passwordFile = work.resolve("keystore.password");
            Files.writeString(passwordFile, keystorePassword);
            tunnelPort = findFreePort();
            Path settings = work.resolve("connectors.properties");
            Files.writeString(settings, "ports=" + tunnelPort + "\n"
                    + tunnelPort + ".keystoreFile=" + keystore + "\n"
                    + tunnelPort + ".keystorePasswordFile=" + passwordFile + "\n");
            tunnelArgs.add("-Dvisco.tunnel.api.connectors=" + settings);
        }

        Path h2Dir = Files.createTempDirectory("tunnel-api-it-h2-");
        tempDirs.add(h2Dir);
        String h2Url = "jdbc:h2:file:" + h2Dir.resolve("db") + ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";

        String javaExe = ProcessHandle.current().info().command()
                .orElse(Paths.get(System.getProperty("java.home"), "bin/java").toString());
        String classpath = String.join(System.getProperty("path.separator"),
                lib("tomcat-embed-core"), lib("tomcat-embed-websocket"), lib("tomcat-embed-el"),
                lib("tomcat-embed-jasper"), lib("tomcat-dbcp"), lib("jakarta.annotation-api"), lib("h2"),
                testClasses.toString(), classes.toString());

        // The child's own temp files (ViscolinkLauncher's work dir with the F!F log files) go under our work dir, so
        // stop() removes them and the filter check can read the F!F log.
        childTmp = Files.createDirectories(work.resolve("jvm-tmp"));
        List<String> command = new ArrayList<>(List.of(javaExe,
                "-Djava.io.tmpdir=" + childTmp,
                "-Dspring.main.allow-circular-references=true",
                "-Dviscolink.overlay.dir=" + healthOverlay));
        command.addAll(tunnelArgs);
        command.addAll(securityArgs);
        command.addAll(List.of("-cp", classpath, "com.viscosiety.viscorunner.it.ViscolinkLauncher",
                viscolinkWar.toString(), configs.toString(), "http://localhost:1/fhir/", h2Url, "unused", "tunnelapi"));
        process = new ProcessBuilder(command).redirectErrorStream(true).start();
        publicPort = readReadyPort();
        log("launcher ready on " + publicPort + (withTunnelSettings ? ", tunnel port " + tunnelPort : ", no tunnel port"));
    }

    /** Polls the status supplier until it answers 200 (3 minutes at most); prints the launcher output on failure. */
    void awaitReady(Callable<Integer> status, String what) throws Exception {
        long deadline = System.currentTimeMillis() + READY_LIMIT_MS;
        int last = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                last = status.call();
                if (last == 200) {
                    log(what + " ready");
                    return;
                }
            } catch (Exception e) {
                last = 0;
            }
            Thread.sleep(2000);
        }
        printOutput();
        throw new IllegalStateException(what + " not ready after 3 minutes (last status " + last + ")");
    }

    void stop() {
        if (process != null) {
            process.destroyForcibly();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (Path dir : tempDirs) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    URI tunnel(String path) {
        return URI.create("https://localhost:" + tunnelPort + path);
    }

    URI open(String path) {
        return URI.create("http://localhost:" + publicPort + path);
    }

    HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static HttpRequest get(URI uri, String user, String password) {
        return withLogin(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET(), user, password).build();
    }

    static HttpRequest options(URI uri, String user, String password) {
        return withLogin(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Origin", "https://elsewhere.example")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()), user, password).build();
    }

    static HttpRequest post(URI uri, String user, String password) {
        return withLogin(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("ping")), user, password).build();
    }

    static HttpRequest.Builder withLogin(HttpRequest.Builder builder, String user, String password) {
        if (user != null) {
            String token = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }
        return builder;
    }

    /** Sends the request line exactly as given (no client-side normalisation) to the public port; returns the status. */
    int rawPublicStatus(String method, String rawPath, String user, String password) throws Exception {
        String token = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        String request = method + " " + rawPath + " HTTP/1.1\r\n"
                + "Host: localhost:" + publicPort + "\r\n"
                + "Authorization: Basic " + token + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: 4\r\n"
                + "Connection: close\r\n\r\n"
                + "ping";
        try (Socket socket = new Socket("localhost", publicPort)) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = reader.readLine();
            if (statusLine == null) {
                throw new IllegalStateException("no answer for " + method + " " + rawPath);
            }
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    /**
     * Reads the launcher's output on its own thread, so a silent launcher cannot block past the limit; returns
     * the port of READY:&lt;port&gt; and keeps collecting output for a failure report.
     */
    private int readReadyPort() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        int[] port = new int[1];
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.add(line);
                    if (line.startsWith("READY:") && ready.getCount() > 0) {
                        port[0] = Integer.parseInt(line.substring("READY:".length()).trim());
                        ready.countDown();
                    }
                }
            } catch (Exception ignored) {
                // the process was stopped
            }
        }, "tunnel-api-it-output");
        reader.setDaemon(true);
        reader.start();
        if (!ready.await(READY_LIMIT_MS, TimeUnit.MILLISECONDS)) {
            printOutput();
            throw new IllegalStateException("launcher did not report READY within 3 minutes");
        }
        return port[0];
    }

    /**
     * Asserts that {@code TunnelOnlyApiFilterRegistrar} registered the public-side filter in this runtime: its INFO
     * line is in the Frank!Framework log ({@code viscolink.log}, which the child writes under its temp dir; the
     * registrar's INFO never reaches stdout) and its ERROR line ("could not be registered") is in neither.
     */
    void assertPublicSideFilterRegistered() throws Exception {
        List<String> lines = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(childTmp)) {
            for (Path log : walk.filter(p -> p.getFileName().toString().equals("viscolink.log")).toList()) {
                lines.addAll(Files.readAllLines(log, StandardCharsets.UTF_8));
            }
        }
        if (lines.isEmpty()) {
            throw new AssertionError("no viscolink.log found under " + childTmp);
        }
        synchronized (output) {
            lines.addAll(output);
        }
        boolean registered = lines.stream().anyMatch(line -> line.contains("tunnel-only endpoints answer 404 on the public servlet"));
        boolean failed = lines.stream().anyMatch(line -> line.contains("public-side filter could not be registered")
                || line.contains("tunnel-only endpoints are NOT hidden"));
        if (failed) {
            throw new AssertionError("the runtime logged that the public-side filter is not registered");
        }
        if (!registered) {
            throw new AssertionError("the runtime's log has no line saying the public-side filter was registered");
        }
    }

    /** The launcher's output, with the generated passwords masked. */
    void printOutput() {
        synchronized (output) {
            for (String line : output) {
                String masked = line;
                for (String secret : new String[] {publicPassword, sitePassword, keystorePassword}) {
                    masked = masked.replace(secret, "[password]");
                }
                System.out.println("[launcher] " + masked);
            }
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    void log(String message) {
        System.out.printf("[%s][%s] %s%n", LocalTime.now().toString().substring(0, 12), tag, message);
    }
}
