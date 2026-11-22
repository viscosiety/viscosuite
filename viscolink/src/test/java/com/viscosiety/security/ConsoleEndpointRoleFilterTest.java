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

package com.viscosiety.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ConsoleEndpointRoleFilterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The console's environment answer in its 10.3 shape, with secrets in every part the frontend does not read. */
    private static final String FULL_ANSWER;

    static {
        try {
            FULL_ANSWER = JSON.writeValueAsString(Map.of(
                    "Application Constants", Map.of(
                            "Global", new java.util.TreeMap<>(Map.ofEntries(
                                    Map.entry("application.version", "1.3.0"),
                                    Map.entry("application.server.type.custom", "TOMCAT"),
                                    Map.entry("console.pollerInterval", "3000"),
                                    Map.entry("customViews.names", "Ladybug,Flow"),
                                    Map.entry("customViews.Ladybug.name", "Ladybug"),
                                    Map.entry("customViews.Ladybug.url", "/runtime/iaf/ladybug/"),
                                    Map.entry("customViews.Ladybug.target", "_blank"),
                                    Map.entry("customViews.Ladybug.secret", "nope"),
                                    Map.entry("larva.enabled", "true"),
                                    Map.entry("Statistics.boundaries", "100,1000"),
                                    Map.entry("jdbc.datasource.default", "jdbc/tenant"),
                                    Map.entry("application.security.console.authentication.clientSecret", "kc-client-secret"),
                                    Map.entry("VISCOSTORE_PASSWORD", "store-password"))),
                            "tenant", Map.of("application.version", "1.3.0", "db.password", "tenant-db-password")),
                    "System Properties", Map.of("javax.net.ssl.trustStorePassword", "changeit"),
                    "Environment Variables", Map.of("KEYCLOAK_CLIENT_SECRET", "env-secret")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final ConsoleEndpointRoleFilter filter = new ConsoleEndpointRoleFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "user", null, AuthorityUtils.createAuthorityList(authorities)));
    }

    /** A request as Tomcat hands it to a filter mapped to /iaf/api/* (the console's DispatcherServlet). */
    private static MockHttpServletRequest request(String method, String pathInfo) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/runtime/iaf/api" + pathInfo);
        request.setContextPath("/runtime");
        request.setServletPath("/iaf/api");
        request.setPathInfo(pathInfo);
        return request;
    }

    /** What the console behind the filter saw and answered. */
    private static final class Console implements FilterChain {
        private final String body;
        private final int status;
        boolean reached;
        String ifNoneMatchSeen;

        Console(String body, int status) {
            this.body = body;
            this.status = status;
        }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) throws java.io.IOException {
            reached = true;
            ifNoneMatchSeen = ((HttpServletRequest) req).getHeader("If-None-Match");
            HttpServletResponse http = (HttpServletResponse) res;
            http.setStatus(status);
            http.setContentType("application/json;charset=UTF-8");
            http.setHeader("ETag", "W/\"full-answer-hash\"");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            http.setContentLength(bytes.length);
            http.getOutputStream().write(bytes);
            http.flushBuffer();
        }
    }

    private static MockHttpServletResponse environment(Console console) throws Exception {
        MockHttpServletRequest request = request("GET", "/environmentvariables");
        request.addHeader("If-None-Match", "W/\"full-answer-hash\"");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ConsoleEndpointRoleFilter().doFilter(request, response, console);
        return response;
    }

    private static List<String> globalKeys(MockHttpServletResponse response) throws Exception {
        JsonNode root = JSON.readTree(response.getContentAsString());
        List<String> top = new ArrayList<>();
        root.fieldNames().forEachRemaining(top::add);
        assertEquals(List.of("Application Constants"), top, "only the application constants");
        List<String> configs = new ArrayList<>();
        root.get("Application Constants").fieldNames().forEachRemaining(configs::add);
        assertEquals(List.of("Global"), configs, "only Global, no per-configuration map");
        List<String> keys = new ArrayList<>();
        root.get("Application Constants").get("Global").fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** Runs the filter on a passing console; answers the status, or -1 when the console answered unchanged. */
    private int run(MockHttpServletRequest request) throws Exception {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> passed.set(true);
        filter.doFilter(request, response, chain);
        if (passed.get()) {
            return -1;
        }
        assertEquals("", response.getContentAsString(), "a refusal has no body");
        return response.getStatus();
    }

    // -- environmentvariables --------------------------------------------------------------------------

    @Test
    void anObserverGetsOnlyTheFrontendKeys() throws Exception {
        signIn("ROLE_IbisObserver");
        Console console = new Console(FULL_ANSWER, 200);
        MockHttpServletResponse response = environment(console);

        assertTrue(console.reached, "the request goes on to the console: the frontend needs an answer");
        assertEquals(200, response.getStatus());
        assertEquals(List.of("Statistics.boundaries", "application.server.type.custom", "application.version",
                        "console.pollerInterval", "customViews.Ladybug.name", "customViews.Ladybug.target",
                        "customViews.Ladybug.url", "customViews.names", "jdbc.datasource.default", "larva.enabled"),
                globalKeys(response).stream().sorted().toList());
        String body = response.getContentAsString();
        for (String secret : List.of("kc-client-secret", "store-password", "tenant-db-password", "changeit", "env-secret",
                "nope", "System Properties", "Environment Variables")) {
            assertFalse(body.contains(secret), "no " + secret + " in " + body);
        }
        assertEquals("1.3.0", JSON.readTree(body).at("/Application Constants/Global/application.version").asText());
    }

    @Test
    void anObserversAnswerCannotBeRevalidatedAgainstTheFullOne() throws Exception {
        signIn("ROLE_IbisObserver");
        Console console = new Console(FULL_ANSWER, 200);
        MockHttpServletResponse response = environment(console);
        assertNull(console.ifNoneMatchSeen, "the console must answer in full, never a 304 for a cached full copy");
        assertNull(response.getHeader("ETag"), "no hash of the full answer");
        assertEquals(response.getContentAsByteArray().length, response.getContentLength());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "", "[]", "{\"Application Constants\":\"x\"}", "{\"Global\":{\"application.version\":\"1\"}}",
            "{\"Application Constants\":{\"Global\":{\"application.version\":{\"nested\":\"secret\"}}}}"})
    void anUnexpectedAnswerBecomesAnEmptyGlobal(String body) throws Exception {
        signIn("ROLE_IbisObserver");
        MockHttpServletResponse response = environment(new Console(body, 200));
        assertEquals(ConsoleEndpointRoleFilter.EMPTY_ANSWER, response.getContentAsString());
    }

    @Test
    void aTooLargeAnswerBecomesAnEmptyGlobal() throws Exception {
        signIn("ROLE_IbisObserver");
        String huge = "{\"Application Constants\":{\"Global\":{\"application.version\":\"1\",\"pad\":\""
                + "x".repeat(ConsoleEndpointRoleFilter.MAX_BUFFERED_BYTES) + "\"}}}";
        MockHttpServletResponse response = environment(new Console(huge, 200));
        assertEquals(ConsoleEndpointRoleFilter.EMPTY_ANSWER, response.getContentAsString());
    }

    @Test
    void anErrorKeepsItsStatusButNotItsBody() throws Exception {
        signIn("ROLE_IbisObserver");
        MockHttpServletResponse response = environment(new Console("{\"error\":\"boom at /opt/frank/secret\"}", 500));
        assertEquals(500, response.getStatus());
        assertEquals(ConsoleEndpointRoleFilter.EMPTY_ANSWER, response.getContentAsString());
    }

    @Test
    void aSendErrorFromTheConsoleIsNotPassedOn() throws Exception {
        signIn("ROLE_IbisObserver");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/environmentvariables"), response,
                (req, res) -> ((HttpServletResponse) res).sendError(503, "detail"));
        assertEquals(503, response.getStatus());
        assertEquals(ConsoleEndpointRoleFilter.EMPTY_ANSWER, response.getContentAsString());
    }

    @Test
    void aWriterAnswerIsNarrowedToo() throws Exception {
        signIn("ROLE_IbisObserver");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/environmentvariables"), response, (req, res) -> {
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write(FULL_ANSWER);
        });
        assertTrue(globalKeys(response).contains("application.version"));
        assertFalse(response.getContentAsString().contains("env-secret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_IbisAdmin", "ROLE_IbisTester", "ROLE_IbisDataAdmin", "IbisAdmin"})
    void anAllowedRoleGetsTheFullAnswerUnchanged(String authority) throws Exception {
        signIn("ROLE_IbisObserver", authority);
        Console console = new Console(FULL_ANSWER, 200);
        MockHttpServletResponse response = environment(console);
        assertEquals(FULL_ANSWER, response.getContentAsString());
        assertEquals("W/\"full-answer-hash\"", response.getHeader("ETag"));
        assertEquals("W/\"full-answer-hash\"", console.ifNoneMatchSeen);
    }

    @Test
    void theServiceAccountsRolesGetTheFullAnswer() throws Exception {
        // The ViscoFoundry portal's bearer service account: raw realm roles mapped to ROLE_<role>.
        signIn("ROLE_IbisObserver", "ROLE_IbisAdmin", "ROLE_IbisTester");
        assertEquals(FULL_ANSWER, environment(new Console(FULL_ANSWER, 200)).getContentAsString());
    }

    @Test
    void noAuthenticationGetsTheNarrowedAnswer() throws Exception {
        assertFalse(environment(new Console(FULL_ANSWER, 200)).getContentAsString().contains("env-secret"));
    }

    @Test
    void aWebServiceRoleGetsTheNarrowedAnswer() throws Exception {
        signIn("ROLE_IbisWebService");
        assertFalse(environment(new Console(FULL_ANSWER, 200)).getContentAsString().contains("env-secret"));
    }

    @Test
    void theOpenConsoleOfTheLocStageGetsTheFullAnswer() throws Exception {
        // application.security.console.authentication.type=NONE: anonymous, with every role.
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_IbisObserver", "ROLE_IbisAdmin")));
        assertEquals(FULL_ANSWER, environment(new Console(FULL_ANSWER, 200)).getContentAsString());
    }

    @Test
    void aContainerRoleIsAccepted() throws Exception {
        MockHttpServletRequest request = request("GET", "/environmentvariables");
        request.addUserRole("IbisTester");
        assertEquals(-1, run(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/environmentvariables/", "/EnvironmentVariables", "//environmentvariables", "/environmentvariables//"})
    void otherSpellingsOfTheEnvironmentVariablesAreNarrowedToo(String pathInfo) throws Exception {
        signIn("ROLE_IbisObserver");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("GET", pathInfo), response, new Console(FULL_ANSWER, 200));
        assertFalse(response.getContentAsString().contains("env-secret"), pathInfo);
    }

    @Test
    void aRequestWhoseServletPathHoldsEverythingIsNarrowedToo() throws Exception {
        signIn("ROLE_IbisObserver");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/runtime/iaf/api/environmentvariables");
        request.setContextPath("/runtime");
        request.setServletPath("/iaf/api/environmentvariables");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new Console(FULL_ANSWER, 200));
        assertFalse(response.getContentAsString().contains("env-secret"));
    }

    @Test
    void theFrontendKeysAreMatchedExactly() {
        assertTrue(ConsoleEndpointRoleFilter.isFrontendKey("customViews.Flow.url"));
        assertFalse(ConsoleEndpointRoleFilter.isFrontendKey("customViews.Flow.url.password"));
        assertFalse(ConsoleEndpointRoleFilter.isFrontendKey("customViews.a.b.url"));
        assertFalse(ConsoleEndpointRoleFilter.isFrontendKey("application.version.x"));
        assertFalse(ConsoleEndpointRoleFilter.isFrontendKey("APPLICATION.VERSION"));
    }

    // -- liquibase ------------------------------------------------------------------------------------------

    @Test
    void anObserverIsRefusedTheLiquibaseUpload() throws Exception {
        signIn("ROLE_IbisObserver");
        assertEquals(403, run(request("POST", "/jdbc/liquibase")));
    }

    @Test
    void noAuthenticationIsRefusedTheLiquibaseUpload() throws Exception {
        assertEquals(403, run(request("POST", "/jdbc/liquibase")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE", "post"})
    void otherWritingMethodsOnLiquibaseAreRefused(String method) throws Exception {
        signIn("ROLE_IbisObserver");
        assertEquals(403, run(request(method, "/jdbc/liquibase/")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_IbisAdmin", "ROLE_IbisTester", "ROLE_IbisDataAdmin"})
    void anAllowedRoleReachesTheLiquibaseUpload(String authority) throws Exception {
        signIn("ROLE_IbisObserver", authority);
        assertEquals(-1, run(request("POST", "/jdbc/liquibase")));
    }

    @Test
    void anObserverStillDownloadsTheChangelog() throws Exception {
        signIn("ROLE_IbisObserver");
        assertEquals(-1, run(request("GET", "/jdbc/liquibase")));
        assertEquals(-1, run(request("HEAD", "/jdbc/liquibase")));
    }

    @Test
    void anObserverReachesOtherEndpointsUnchanged() throws Exception {
        signIn("ROLE_IbisObserver");
        assertEquals(-1, run(request("GET", "/adapters")));
        assertEquals(-1, run(request("GET", "/server/warnings")));
        assertEquals(-1, run(request("GET", "/environmentvariablesx")));
    }

    // -- wiring ---------------------------------------------------------------------------------------------

    @Test
    void webXmlMapsTheFilterToTheConsoleApi() throws Exception {
        String webXml = Files.readString(Path.of("src/main/webapp/WEB-INF/web.xml"));
        assertTrue(webXml.contains("<filter-class>com.viscosiety.security.ConsoleEndpointRoleFilter</filter-class>"));
        // The last mention of the name is the one inside its <filter-mapping>.
        String mapping = webXml.substring(webXml.lastIndexOf("<filter-name>consoleEndpointRoles</filter-name>"));
        mapping = mapping.substring(0, mapping.indexOf("</filter-mapping>"));
        assertTrue(mapping.contains("<url-pattern>/iaf/api/*</url-pattern>"), mapping);
        for (String dispatcher : List.of("REQUEST", "FORWARD")) {
            assertTrue(mapping.contains("<dispatcher>" + dispatcher + "</dispatcher>"), dispatcher);
        }
        assertFalse(ConsoleEndpointRoleFilter.ALLOWED_ROLES.contains("IbisObserver"));
    }
}
