package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * spring.mvc.servlet.path=/tester puts everything the DispatcherServlet serves under /tester: the
 * tester UI, but also the actuator and HAPI's subscription websocket. The tester UI is public (it asks
 * for the store's login itself); the actuator (except health) and the websocket must not inherit that.
 *
 * <p>Production security set-up, like {@link FmlCompileSecurityIT} (no {@code test} profile), with
 * every actuator endpoint web-exposed, as application.yaml's comment offers ("*" for all).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {Application.class, NicknameServiceConfig.class},
    properties = {
        "spring.datasource.url=jdbc:h2:mem:dbtesterpathsecurity",
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
        "management.endpoints.web.exposure.include=*",
        // Like the image's external Tomcat (conf/web.xml): without a default servlet, Tomcat answers
        // an unmapped path such as /actuator/health with 404 before any filter runs, so
        // TesterRedirectFilter would never see it.
        "server.servlet.register-default-servlet=true",
    }
)
class TesterPathSecurityIT {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void healthStaysPublic() throws Exception {
        assertEquals(200, get("/tester/actuator/health", false).statusCode());
        assertEquals(200, get("/tester/actuator/health/liveness", false).statusCode());
    }

    @Test
    void theRootHealthPathStillRedirectsToTheRealOne() throws Exception {
        HttpResponse<String> response = get("/actuator/health", false);
        assertEquals(302, response.statusCode());
        assertEquals("/tester/actuator/health", response.headers().firstValue("Location").orElse(""));
    }

    @Test
    void otherActuatorEndpointsNeedTheLogin() throws Exception {
        for (String path : new String[] {"/tester/actuator", "/tester/actuator/metrics",
                "/tester/actuator/prometheus", "/tester/actuator/info"}) {
            assertEquals(401, get(path, false).statusCode(), "anonymous " + path);
            assertEquals(200, get(path, true).statusCode(), "authenticated " + path);
        }
    }

    @Test
    void theTesterUiStaysPublic() throws Exception {
        assertEquals(200, get("/tester/", false).statusCode());
    }

    @Test
    void theSubscriptionWebsocketNeedsTheLogin() {
        CompletionException e = assertThrows(CompletionException.class, () -> openWebsocket(false));
        WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class, e.getCause());
        assertEquals(401, handshake.getResponse().statusCode());
    }

    @Test
    void theSubscriptionWebsocketOpensWithTheLogin() throws Exception {
        WebSocket socket = openWebsocket(true);
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
    }

    private WebSocket openWebsocket(boolean authenticated) {
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        if (authenticated) {
            builder.header("Authorization", CorsSecurityIT.basic(CorsSecurityIT.USERNAME, CorsSecurityIT.PASSWORD));
        }
        return builder.buildAsync(URI.create("ws://localhost:" + port + "/tester/websocket"),
            new WebSocket.Listener() { }).join();
    }

    private HttpResponse<String> get(String path, boolean authenticated) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET();
        if (authenticated) {
            request.header("Authorization", CorsSecurityIT.basic(CorsSecurityIT.USERNAME, CorsSecurityIT.PASSWORD));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
