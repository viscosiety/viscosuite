package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS is off unless hapi.fhir.cors lists origins. With the old default (any origin, credentials
 * allowed) a web page on any site could read the store through a browser that held a ViscoStore
 * login (the Basic credentials a browser caches after the Swagger UI prompt, for example).
 *
 * <p>Production security set-up, like {@link FmlCompileSecurityIT} (no {@code test} profile).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {Application.class, NicknameServiceConfig.class},
    properties = {
        "spring.datasource.url=jdbc:h2:mem:dbcorssecurity",
        "hapi.fhir.fhir_version=R4",
        "hapi.fhir.custom-bean-packages=com.viscosiety.viscostore",
        "hapi.fhir.cr.enabled=false",
        "hapi.fhir.mdm_enabled=false",
        "hapi.fhir.enable_repository_validating_interceptor=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
        "VISCOSTORE_USERNAME=" + CorsSecurityIT.USERNAME,
        "VISCOSTORE_PASSWORD=" + CorsSecurityIT.PASSWORD,
    }
)
class CorsSecurityIT {

    static final String USERNAME = "viscolink";
    static final String PASSWORD = "cors-security-it";
    static final String FOREIGN_ORIGIN = "https://evil.example";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void aCredentialedReadFromAnotherOriginGetsNoCorsHeaders() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "Patient?_count=1"))
                .header("Origin", FOREIGN_ORIGIN)
                .header("Authorization", basic(USERNAME, PASSWORD))
                .header("Accept", "application/fhir+json")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
            "Access-Control-Allow-Origin: " + response.headers().firstValue("Access-Control-Allow-Origin"));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Credentials").isEmpty(),
            "Access-Control-Allow-Credentials: " + response.headers().firstValue("Access-Control-Allow-Credentials"));
    }

    @Test
    void aPreflightFromAnotherOriginIsNotAllowed() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "Patient"))
                .header("Origin", FOREIGN_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());

        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
            "preflight answered " + response.statusCode() + " with Access-Control-Allow-Origin: "
                + response.headers().firstValue("Access-Control-Allow-Origin"));
    }

    private String base() {
        return "http://localhost:" + port + "/fhir/";
    }

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
