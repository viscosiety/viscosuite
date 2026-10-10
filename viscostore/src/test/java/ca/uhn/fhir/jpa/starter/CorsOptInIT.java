package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import com.viscosiety.viscostore.config.CorsSettingsGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documented way to let a browser app on another origin call the store: list that origin in
 * hapi.fhir.cors.allowed_origin. Only that origin gets CORS headers, and no credentials header
 * unless allow_Credentials is set as well.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {Application.class, NicknameServiceConfig.class},
    properties = {
        "spring.datasource.url=jdbc:h2:mem:dbcorsoptin",
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
        "hapi.fhir.cors.allowed_origin[0]=" + CorsOptInIT.APP_ORIGIN,
    }
)
class CorsOptInIT {

    static final String APP_ORIGIN = "https://app.example.org";

    @LocalServerPort
    private int port;

    @Autowired(required = false)
    private CorsSettingsGuard guard;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void theStartupGuardIsRegistered() {
        assertNotNull(guard, "CorsSettingsGuard is not a bean: com.viscosiety.viscostore is not scanned?");
    }

    @Test
    void theListedOriginGetsCorsHeadersWithoutCredentials() throws Exception {
        HttpResponse<String> response = read(APP_ORIGIN);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(Optional.of(APP_ORIGIN), response.headers().firstValue("Access-Control-Allow-Origin"));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Credentials").isEmpty(),
            "Access-Control-Allow-Credentials: " + response.headers().firstValue("Access-Control-Allow-Credentials"));
    }

    @Test
    void anotherOriginGetsNoCorsHeaders() throws Exception {
        HttpResponse<String> response = read(CorsSecurityIT.FOREIGN_ORIGIN);

        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
            "Access-Control-Allow-Origin: " + response.headers().firstValue("Access-Control-Allow-Origin"));
    }

    private HttpResponse<String> read(String origin) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/fhir/Patient?_count=1"))
                .header("Origin", origin)
                .header("Authorization", CorsSecurityIT.basic(CorsSecurityIT.USERNAME, CorsSecurityIT.PASSWORD))
                .header("Accept", "application/fhir+json")
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
