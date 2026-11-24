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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What ViscoFoundry needs from a runtime to run an assistant's draft: a configuration loaded from git
 * by {@code GitClassLoader}, switched to a draft branch and back by {@code ConfigRefServlet}, and
 * reloaded onto a new commit of main by the console's full reload (Apply). Runs the real runtime (the viscolink WAR in its own JVM,
 * {@link TunnelApiLauncher}) over a local git repository, set up the way the portal renders an instance
 * ({@code configurations.<name>.classLoaderType=com.viscosiety.classloaders.GitClassLoader}, {@code repoUrl},
 * {@code repoSubdir}). The unit tests of the class loader and the servlet mock the framework; this test
 * catches a Frank!Framework change to how configuration class loaders are created and reloaded, so a
 * ViscoSuite build on which drafts do not load is never published.
 *
 * <p>The configuration answers one API call, {@code GET /api/draftit/which}, with a fixed text: {@code main}
 * on main, {@code draft} on the draft branch, {@code main-2} after a second commit on main. Each step is
 * observed through that answer, not through the servlet's own report.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DraftRefSwitchIT {

    private static final String CONFIGURATION = "draftit";
    private static final String SUBDIR = "ff-configurations/" + CONFIGURATION;
    private static final String DRAFT_BRANCH = "assistant/draftit-1";
    private static final String USER = "foundry-it";
    private static final long SETTLE_LIMIT_MS = 120_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String password = UUID.randomUUID().toString();
    private final List<Path> tempDirs = new ArrayList<>();
    private TunnelApiLauncher l;
    private Path work;
    private Path origin;

    @BeforeAll
    void start() throws Exception {
        work = Files.createTempDirectory("draft-ref-switch-it-");
        tempDirs.add(work);
        origin = work.resolve("origin.git");
        createOrigin();

        // No DirectoryClassLoader configuration is used: the folder only satisfies the launcher's argument.
        Path unusedConfigs = Files.createDirectories(work.resolve("unused-configs"));
        l = new TunnelApiLauncher("DraftRefSwitchIT", false, unusedConfigs.toString(), CONFIGURATION);
        String prefix = "-Dconfigurations." + CONFIGURATION + ".";
        l.start(List.of(
                prefix + "classLoaderType=com.viscosiety.classloaders.GitClassLoader",
                prefix + "repoUrl=" + origin.toUri(),
                prefix + "repoSubdir=" + SUBDIR,
                prefix + "localPath=" + work.resolve("clone"),
                "-Dapplication.security.http.authentication=true",
                "-Dapplication.security.http.transportGuarantee=NONE",
                "-Dapplication.security.http.authenticators=foundryIt",
                "-Dapplication.security.http.authenticators.foundryIt.type=IN_MEMORY",
                "-Dapplication.security.http.authenticators.foundryIt.username=" + USER,
                "-Dapplication.security.http.authenticators.foundryIt.password=" + password,
                "-Dservlet.ApiListenerServlet.authenticator=foundryIt",
                "-Dservlet.ApiListenerServlet.securityRoles=IbisTester",
                "-Dservlet.configRef.authenticator=foundryIt",
                "-Dservlet.configRef.securityRoles=IbisAdmin",
                "-Dapplication.security.console.authentication.type=IN_MEMORY",
                "-Dapplication.security.console.authentication.username=" + USER,
                "-Dapplication.security.console.authentication.password=" + password));
        l.awaitReady(() -> which().statusCode(), "the git-backed configuration");
    }

    @AfterAll
    void stop() {
        if (l != null) {
            l.stop();
        }
        for (Path dir : tempDirs) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    @Test
    @Order(1)
    void theConfigurationIsLoadedFromGitOnMain() throws Exception {
        l.assertLogContains("GitClassLoader ready");
        assertEquals("main", answer());
        JsonNode state = refState();
        assertEquals("master", state.path("ref").asText(), "ref reported by the servlet: " + state);
    }

    @Test
    @Order(2)
    void switchingToTheDraftLoadsTheDraft() throws Exception {
        HttpResponse<String> put = putRef(DRAFT_BRANCH);
        l.log("PUT ref " + DRAFT_BRANCH + ": " + put.statusCode() + " " + put.body().trim());
        assertEquals(202, put.statusCode(), "PUT ref " + DRAFT_BRANCH + ": " + put.body());
        awaitAnswer("draft");
        assertEquals(DRAFT_BRANCH, refState().path("ref").asText());
    }

    @Test
    @Order(3)
    void switchingBackLoadsMainAgain() throws Exception {
        HttpResponse<String> put = putRef("master");
        l.log("PUT ref master: " + put.statusCode() + " " + put.body().trim());
        assertEquals(202, put.statusCode(), "PUT ref master: " + put.body());
        awaitAnswer("main");
        assertEquals("master", refState().path("ref").asText());
    }

    @Test
    @Order(4)
    void aReloadLoadsANewCommitOfMain() throws Exception {
        commit("master", "main-2", "second commit on main");
        // What Apply and the config-push tick do after a merge: the console's full reload, with no ref
        // switch. Only the new class loader's own pull can bring the new commit in (a ref switch fetches by
        // itself, so it would hide a class loader that no longer pulls).
        HttpRequest reload = withLogin(HttpRequest.newBuilder(l.open("/viscolink/iaf/api/configurations"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"action\":\"fullreload\"}"))).build();
        HttpResponse<String> r = l.send(l.http, reload);
        l.log("console fullreload: " + r.statusCode() + " " + r.body().trim());
        assertTrue(r.statusCode() >= 200 && r.statusCode() < 300, "console fullreload: " + r.statusCode() + " " + r.body());
        awaitAnswer("main-2");
    }

    // ── the git repository ──────────────────────────────────────────────────────────────────────────────

    /** A bare repository with the configuration on master ({@code main}) and a draft branch ({@code draft}). */
    private void createOrigin() throws Exception {
        Git.init().setBare(true).setInitialBranch("master").setDirectory(origin.toFile()).call().close();
        Path seed = work.resolve("seed");
        try (Git git = Git.cloneRepository().setURI(origin.toUri().toString()).setDirectory(seed.toFile()).call()) {
            writeConfiguration(seed, "main");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("main").setAuthor("it", "it@example.invalid").setCommitter("it", "it@example.invalid").call();
            git.push().add("master").call();

            git.checkout().setCreateBranch(true).setName(DRAFT_BRANCH).call();
            writeConfiguration(seed, "draft");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("draft").setAuthor("it", "it@example.invalid").setCommitter("it", "it@example.invalid").call();
            git.push().add(DRAFT_BRANCH).call();
        }
        tempDirs.add(seed);
    }

    private void commit(String branch, String reply, String message) throws Exception {
        Path seed = work.resolve("seed");
        try (Git git = Git.open(seed.toFile())) {
            git.checkout().setName(branch).call();
            writeConfiguration(seed, reply);
            git.add().addFilepattern(".").call();
            git.commit().setMessage(message).setAuthor("it", "it@example.invalid").setCommitter("it", "it@example.invalid").call();
            git.push().add(branch).call();
        }
    }

    private static void writeConfiguration(Path root, String reply) throws Exception {
        Path dir = Files.createDirectories(root.resolve(SUBDIR));
        Files.writeString(dir.resolve("Configuration.xml"), """
                <Configuration name="%s">
                    <Adapter name="Which">
                        <Receiver name="WhichReceiver">
                            <ApiListener name="which" uriPattern="draftit/which" method="GET" allowAllParams="false"/>
                        </Receiver>
                        <Pipeline>
                            <Exits>
                                <Exit name="Exit" state="SUCCESS" code="200"/>
                            </Exits>
                            <FixedResultPipe name="answer" returnString="%s"/>
                        </Pipeline>
                    </Adapter>
                </Configuration>
                """.formatted(CONFIGURATION, reply), StandardCharsets.UTF_8);
    }

    // ── the runtime ─────────────────────────────────────────────────────────────────────────────────────

    private HttpResponse<String> which() throws Exception {
        return l.send(l.http, TunnelApiLauncher.get(l.open("/viscolink/api/draftit/which"), USER, password));
    }

    private String answer() throws Exception {
        HttpResponse<String> r = which();
        assertEquals(200, r.statusCode(), "GET /api/draftit/which: " + r.body());
        return r.body().trim();
    }

    /** Polls the API until it answers {@code expected} and the servlet reports no reload in progress. */
    private void awaitAnswer(String expected) throws Exception {
        long deadline = System.currentTimeMillis() + SETTLE_LIMIT_MS;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> r = which();
                last = r.statusCode() + " " + r.body().trim();
                if (r.statusCode() == 200 && expected.equals(r.body().trim()) && !refState().path("reloading").asBoolean(true)) {
                    l.log("answers " + expected);
                    return;
                }
            } catch (Exception e) {
                last = e.getClass().getSimpleName();
            }
            Thread.sleep(1000);
        }
        l.printOutput();
        throw new AssertionError("the runtime did not answer [" + expected + "] within 2 minutes (last: " + last + ")");
    }

    private JsonNode refState() throws Exception {
        HttpResponse<String> r = l.send(l.http, withLogin(HttpRequest.newBuilder(
                l.open("/viscolink/api-service/configurations/ref?configuration=" + CONFIGURATION))
                .timeout(Duration.ofSeconds(30)).GET()).build());
        assertEquals(200, r.statusCode(), "GET ref: " + r.body());
        return JSON.readTree(r.body());
    }

    private HttpResponse<String> putRef(String ref) throws Exception {
        String body = JSON.writeValueAsString(java.util.Map.of("configuration", CONFIGURATION, "ref", ref));
        URI uri = l.open("/viscolink/api-service/configurations/ref");
        HttpRequest request = withLogin(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))).build();
        HttpResponse<String> r = l.send(l.http, request);
        assertTrue(r.statusCode() != 401 && r.statusCode() != 403, "the ref servlet refused the login: " + r.statusCode());
        return r;
    }

    private HttpRequest.Builder withLogin(HttpRequest.Builder builder) {
        String token = Base64.getEncoder().encodeToString((USER + ":" + password).getBytes(StandardCharsets.UTF_8));
        return builder.header("Authorization", "Basic " + token);
    }
}
