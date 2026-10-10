package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import org.apache.catalina.Context;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POST /fhir/StructureMap/$compile needs the same HTTP Basic login as the rest of the FHIR API.
 *
 * <p>{@code FmlCompileFilter} answers the request itself (HAPI's servlet never sees it), so the only
 * thing that protects it is Spring Security's filter chain running first. It used to be registered
 * at {@code Ordered.HIGHEST_PRECEDENCE}, ahead of that chain: an anonymous caller could create or
 * overwrite any StructureMap, including the maps the codification interceptor applies.
 *
 * <p>Unlike the other ITs this one does NOT activate the {@code test} profile: that profile switches
 * {@code SecurityConfig} off and excludes Spring Boot's security auto-configuration, so it cannot see
 * this. The security set-up here is the production one from application.yaml.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {Application.class, NicknameServiceConfig.class},
    properties = {
        "spring.datasource.url=jdbc:h2:mem:dbfmlcompilesecurity",
        "hapi.fhir.fhir_version=R4",
        "hapi.fhir.custom-bean-packages=com.viscosiety.viscostore",
        "hapi.fhir.cr.enabled=false",
        "hapi.fhir.mdm_enabled=false",
        "hapi.fhir.enable_repository_validating_interceptor=false",
        "hapi.fhir.advanced_lucene_indexing=false",
        "hapi.fhir.search_index_full_text_enabled=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
        // The same placeholders a deployment sets (application.yaml -> spring.security.user.*)
        "VISCOSTORE_USERNAME=" + FmlCompileSecurityIT.USERNAME,
        "VISCOSTORE_PASSWORD=" + FmlCompileSecurityIT.PASSWORD,
    }
)
class FmlCompileSecurityIT {

    static final String USERNAME = "viscolink";
    static final String PASSWORD = "fml-compile-security-it";

    /**
     * Filters that may run before Spring Security. Each one is Spring's own request plumbing
     * (encoding, form parsing, request context, observation metrics), or only redirects
     * (TesterRedirectFilter answers with a Location header, no data, and the redirected request
     * passes the security chain like any other). A filter that answers a request with data or
     * changes state must run AFTER springSecurityFilterChain: add it here only after checking that
     * it can never do either.
     */
    private static final Set<String> ALLOWED_BEFORE_SECURITY = Set.of(
        "characterEncodingFilter",
        "formContentFilter",
        "requestContextFilter",
        "webMvcObservationFilter",
        "testerRedirectFilter");

    private static final String SECURITY_FILTER = "springSecurityFilterChain";

    @LocalServerPort
    private int port;

    @Autowired
    private ServletWebServerApplicationContext applicationContext;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void anonymousCompileIsRejectedAndStoresNothing() throws Exception {
        String url = "https://example.org/StructureMap/fml-compile-security-anonymous";

        HttpResponse<String> response = compile(url, null);

        assertEquals(401, response.statusCode(), response.body());
        assertEquals(0, countStructureMaps(url));
    }

    @Test
    void wrongPasswordIsRejectedAndStoresNothing() throws Exception {
        String url = "https://example.org/StructureMap/fml-compile-security-wrong-password";

        HttpResponse<String> response = compile(url, basic(USERNAME, "not-the-password"));

        assertEquals(401, response.statusCode(), response.body());
        assertEquals(0, countStructureMaps(url));
    }

    @Test
    void authenticatedCompileCreatesThenUpdates() throws Exception {
        String url = "https://example.org/StructureMap/fml-compile-security-authenticated";

        HttpResponse<String> created = compile(url, basic(USERNAME, PASSWORD));
        HttpResponse<String> updated = compile(url, basic(USERNAME, PASSWORD));

        assertEquals(201, created.statusCode(), created.body());
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(1, countStructureMaps(url));
    }

    @Test
    void anonymousUpdateOfAnExistingMapIsRejected() throws Exception {
        String url = "https://example.org/StructureMap/fml-compile-security-overwrite";
        assertEquals(201, compile(url, basic(USERNAME, PASSWORD)).statusCode());

        HttpResponse<String> response = compile(url, null);

        assertEquals(401, response.statusCode(), response.body());
        assertEquals(1, countStructureMaps(url));
    }

    @Test
    void onlyAllowListedFiltersRunBeforeTheSecurityChain() {
        List<String> order = filterOrder();
        int security = order.indexOf(SECURITY_FILTER);
        assertTrue(security >= 0, "springSecurityFilterChain is not registered: " + order);
        assertTrue(order.indexOf("fmlCompileFilter") > security,
            "fmlCompileFilter must run after springSecurityFilterChain: " + order);

        List<String> before = order.subList(0, security).stream()
            .filter(name -> !ALLOWED_BEFORE_SECURITY.contains(name))
            .toList();
        assertEquals(List.of(), before,
            "Filters mapped ahead of springSecurityFilterChain run for unauthenticated requests; "
                + "check them and extend ALLOWED_BEFORE_SECURITY only if they cannot leak data or change state. "
                + "Full order: " + order);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HttpResponse<String> compile(String canonicalUrl, String authorization) throws Exception {
        String fml = """
            map "%s" = "FmlCompileSecurityIT"

            uses "http://hl7.org/fhir/StructureDefinition/Patient" alias Src as source
            uses "http://hl7.org/fhir/StructureDefinition/Patient" alias Tgt as target

            group Main(source src : Src, target tgt : Tgt) {
              src.gender as v -> tgt.gender = v "gender";
            }
            """.formatted(canonicalUrl);
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(fhirBase() + "StructureMap/$compile"))
            .header("Content-Type", "text/fhir-mapping")
            .header("Accept", "application/fhir+json")
            .POST(HttpRequest.BodyPublishers.ofString(fml));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int countStructureMaps(String canonicalUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(fhirBase() + "StructureMap?_summary=count&url="
                + URLEncoder.encode(canonicalUrl, StandardCharsets.UTF_8)))
            .header("Authorization", basic(USERNAME, PASSWORD))
            .header("Accept", "application/fhir+json")
            // HAPI reuses an identical search's result for a while; never count from a stale one
            .header("Cache-Control", "no-cache")
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String body = response.body().replaceAll("\\s", "");
        int start = body.indexOf("\"total\":");
        assertTrue(start >= 0, "no total in search answer: " + response.body());
        int end = start + "\"total\":".length();
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return Integer.parseInt(body.substring(start + "\"total\":".length(), end));
    }

    private List<String> filterOrder() {
        TomcatWebServer server = (TomcatWebServer) applicationContext.getWebServer();
        Context context = (Context) server.getTomcat().getHost().findChildren()[0];
        return Arrays.stream(context.findFilterMaps()).map(FilterMap::getFilterName).distinct().toList();
    }

    private String fhirBase() {
        return "http://localhost:" + port + "/fhir/";
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
