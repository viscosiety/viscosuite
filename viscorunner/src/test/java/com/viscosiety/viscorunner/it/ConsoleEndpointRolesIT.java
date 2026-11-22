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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code ConsoleEndpointRoleFilter} in a real runtime: the Frank!Framework console login (YML users, one per role),
 * the webapp at {@code /runtime} and the image's own Host rewrite rules, so the old {@code /viscolink/...} path is
 * served in place.
 *
 * <p>An observer gets only the frontend's keys of {@code GET /iaf/api/environmentvariables} and an empty 403 on
 * {@code POST /iaf/api/jdbc/liquibase}, on both paths; IbisAdmin, IbisTester and IbisDataAdmin get the full answers;
 * {@code GET /iaf/api/jdbc/liquibase} (the changelog download) is unaffected. The POSTs carry the console's CSRF double-submit pair (cookie {@code XSRF-TOKEN} = header
 * {@code X-XSRF-TOKEN}), so a 403 cannot come from Spring Security's CSRF check.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleEndpointRolesIT {

    private static final Path REWRITE_RULES = Paths.get("conf/Catalina/localhost/rewrite.config").toAbsolutePath();
    private static final String CONFIGURATION = "tunnelapi";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHANGELOG = """
            <?xml version="1.0" encoding="UTF-8"?>
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.29.xsd">
              <changeSet id="console-endpoint-roles-it" author="it">
                <createTable tableName="CONSOLE_ROLES_IT">
                  <column name="ID" type="INT"/>
                </createTable>
              </changeSet>
            </databaseChangeLog>
            """;

    private final TunnelApiLauncher l = new TunnelApiLauncher("ConsoleEndpointRolesIT", false);
    private final String observerPassword = UUID.randomUUID().toString();
    private final String adminPassword = UUID.randomUUID().toString();
    private final String testerPassword = UUID.randomUUID().toString();
    private final String dataAdminPassword = UUID.randomUUID().toString();
    private final String csrf = UUID.randomUUID().toString();
    /** A system property only the full environment answer shows. */
    private final String marker = "marker-" + UUID.randomUUID();
    private Path users;

    @BeforeAll
    void start() throws Exception {
        users = Files.createTempFile("console-users-", ".yml");
        Files.writeString(users, "users:\n"
                + user("observer", observerPassword, "IbisObserver")
                + user("admin", adminPassword, "IbisAdmin")
                + user("tester", testerPassword, "IbisTester")
                + user("dataadmin", dataAdminPassword, "IbisDataAdmin"));
        l.start(List.of(
                "-Dviscolink.context.path=/runtime",
                "-Dconsole.endpoint.roles.it.marker=" + marker,
                "-Dviscolink.rewrite.rules=" + REWRITE_RULES,
                "-Dapplication.security.console.authentication.type=YML",
                "-Dapplication.security.console.authentication.file=" + users.toUri()));
        l.awaitReady(() -> get("/runtime/iaf/api/environmentvariables", "admin", adminPassword).statusCode(),
                "console API");
    }

    @AfterAll
    void stop() throws Exception {
        l.stop();
        if (users != null) {
            Files.deleteIfExists(users);
        }
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String user(String name, String password, String role) {
        return "  - username: " + name + "\n    password: " + password + "\n    roles:\n      - " + role + "\n";
    }

    private String passwordOf(String name) {
        return switch (name) {
            case "observer" -> observerPassword;
            case "admin" -> adminPassword;
            case "tester" -> testerPassword;
            case "dataadmin" -> dataAdminPassword;
            default -> throw new IllegalArgumentException(name);
        };
    }

    private HttpRequest.Builder request(String path, String user) {
        return TunnelApiLauncher.withLogin(HttpRequest.newBuilder(l.open(path)).timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json"), user, passwordOf(user));
    }

    private HttpResponse<String> get(String path, String user, String password) throws Exception {
        return l.send(l.http, TunnelApiLauncher.withLogin(HttpRequest.newBuilder(l.open(path))
                .timeout(Duration.ofSeconds(60)).header("Accept", "application/json").GET(), user, password).build());
    }

    private HttpResponse<String> get(String path, String user) throws Exception {
        return l.send(l.http, request(path, user).GET().build());
    }

    private HttpResponse<String> uploadChangelog(String path, String user) throws Exception {
        String boundary = "----it" + UUID.randomUUID().toString().replace("-", "");
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"configuration\"\r\n\r\n"
                + CONFIGURATION + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"changelog.xml\"\r\n"
                + "Content-Type: application/xml\r\n\r\n"
                + CHANGELOG + "\r\n"
                + "--" + boundary + "--\r\n";
        HttpRequest r = request(path, user)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", "XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return l.send(l.http, r);
    }

    private void assertRefused(HttpResponse<String> r, String what) {
        l.log(what + ": " + r.statusCode());
        assertEquals(403, r.statusCode(), what);
        assertEquals("", r.body(), what + ": a refusal carries no body");
    }

    @Test
    void anObserverGetsOnlyTheFrontendKeysOfTheEnvironment() throws Exception {
        for (String prefix : List.of("/runtime", "/viscolink")) {
            HttpResponse<String> r = get(prefix + "/iaf/api/environmentvariables", "observer");
            l.log("observer GET " + prefix + " environmentvariables: " + r.statusCode() + ", " + r.body().length() + " chars");
            assertEquals(200, r.statusCode(), "the console frontend reads this at every page load");
            JsonNode root = JSON.readTree(r.body());
            assertEquals(List.of("Application Constants"), names(root));
            assertEquals(List.of("Global"), names(root.get("Application Constants")), "no per-configuration maps");
            List<String> keys = names(root.get("Application Constants").get("Global"));
            assertTrue(keys.contains("application.version"), "the frontend's version check: " + keys);
            for (String key : keys) {
                assertTrue(key.startsWith("customViews.") || List.of("application.version", "application.server.type.custom",
                        "application.console.status.hide", "console.pollerInterval", "jdbc.datasource.default", "larva.enabled",
                        "loadDatabaseSchedules.active", "testtool.echo2.enabled", "Statistics.boundaries",
                        "Statistics.size.boundaries", "Statistics.percentiles", "Statistics.percentiles.publish",
                        "Statistics.histograms.publish").contains(key), "not a frontend key: " + key);
            }
            assertFalse(r.body().contains(marker), "the marker system property must not reach an observer");
            assertFalse(r.body().contains("console.authentication"), "no security settings");
            assertTrue(r.headers().firstValue("ETag").isEmpty(), "no hash of the full answer");
        }
    }

    @Test
    void anAllowedRoleGetsTheFullEnvironment() throws Exception {
        for (String user : List.of("admin", "tester", "dataadmin")) {
            for (String prefix : List.of("/runtime", "/viscolink")) {
                HttpResponse<String> r = get(prefix + "/iaf/api/environmentvariables", user);
                l.log(user + " GET " + prefix + " environmentvariables: " + r.statusCode() + ", " + r.body().length() + " chars");
                assertEquals(200, r.statusCode(), user + " on " + prefix);
                JsonNode root = JSON.readTree(r.body());
                assertTrue(root.has("System Properties"), user + ": the full answer");
                assertTrue(r.body().contains(marker), user + ": the marker system property is in the full answer");
            }
        }
    }

    @Test
    void anObserverIsRefusedTheLiquibaseUpload() throws Exception {
        for (String prefix : List.of("/runtime", "/viscolink")) {
            assertRefused(uploadChangelog(prefix + "/iaf/api/jdbc/liquibase", "observer"), "observer POST " + prefix + " liquibase");
        }
    }

    @Test
    void anAllowedRoleReachesTheLiquibaseUpload() throws Exception {
        for (String user : List.of("admin", "tester", "dataadmin")) {
            for (String prefix : List.of("/runtime", "/viscolink")) {
                HttpResponse<String> r = uploadChangelog(prefix + "/iaf/api/jdbc/liquibase", user);
                l.log(user + " POST " + prefix + " liquibase: " + r.statusCode() + " " + abbreviated(r.body()));
                assertNotEquals(403, r.statusCode(), user + " on " + prefix + ": " + abbreviated(r.body()));
                assertTrue(r.statusCode() != 401, user + " on " + prefix);
            }
        }
    }

    @Test
    void anObserverStillDownloadsTheChangelog() throws Exception {
        for (String prefix : List.of("/runtime", "/viscolink")) {
            // The download produces application/octet-stream; ask for anything, as the console frontend does.
            HttpResponse<String> r = l.send(l.http, TunnelApiLauncher.withLogin(HttpRequest.newBuilder(
                    l.open(prefix + "/iaf/api/jdbc/liquibase?configuration=" + CONFIGURATION))
                    .timeout(Duration.ofSeconds(60)).header("Accept", "*/*").GET(), "observer", observerPassword).build());
            l.log("observer GET " + prefix + " liquibase: " + r.statusCode() + " " + abbreviated(r.body()));
            assertNotEquals(403, r.statusCode(), prefix);
            assertNotEquals(401, r.statusCode(), prefix);
        }
    }

    @Test
    void anObserverStillReadsTheRestOfTheConsole() throws Exception {
        HttpResponse<String> r = get("/runtime/iaf/api/server/info", "observer");
        l.log("observer GET server/info: " + r.statusCode());
        assertEquals(200, r.statusCode());
    }

    @Test
    void withoutALoginTheConsoleAsksForOne() throws Exception {
        HttpResponse<String> r = l.send(l.http, HttpRequest.newBuilder(l.open("/runtime/iaf/api/environmentvariables"))
                .timeout(Duration.ofSeconds(60)).header("Accept", "application/json").GET().build());
        l.log("no login GET environmentvariables: " + r.statusCode());
        assertEquals(401, r.statusCode());
    }

    @Test
    void aWrongPasswordIsStillAskedForALogin() throws Exception {
        assertEquals(401, get("/runtime/iaf/api/environmentvariables", "admin", "wrong").statusCode());
    }

    private static String abbreviated(String body) {
        String one = body == null ? "" : body.replaceAll("\\s+", " ");
        return one.length() > 160 ? one.substring(0, 160) + "..." : one;
    }
}
