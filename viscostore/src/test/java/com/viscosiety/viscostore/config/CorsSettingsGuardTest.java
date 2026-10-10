package com.viscosiety.viscostore.config;

import ca.uhn.fhir.jpa.starter.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsSettingsGuardTest {

    @Test
    void noCorsSectionIsFine() {
        assertDoesNotThrow(() -> CorsSettingsGuard.check(null));
    }

    @Test
    void wildcardWithoutCredentialsIsFine() {
        assertDoesNotThrow(() -> CorsSettingsGuard.check(cors(false, List.of("*"))));
    }

    @Test
    void credentialsWithExplicitOriginsAreFine() {
        assertDoesNotThrow(() -> CorsSettingsGuard.check(
            cors(true, List.of("https://app.example.org", "https://*.example.org"))));
    }

    @Test
    void credentialsWithTheWildcardOriginAreRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> CorsSettingsGuard.check(cors(true, List.of("https://app.example.org", " * "))));
        assertTrue(e.getMessage().contains("hapi.fhir.cors.allowed_origin"), e.getMessage());
    }

    @Test
    void credentialsWithoutAnyOriginAreRefused() {
        // AppProperties falls back to "*" when no origin is listed
        assertThrows(IllegalStateException.class, () -> CorsSettingsGuard.check(cors(true, null)));
    }

    private static AppProperties.Cors cors(boolean credentials, List<String> origins) {
        AppProperties.Cors cors = new AppProperties.Cors();
        cors.setAllow_Credentials(credentials);
        cors.setAllowed_origin(origins);
        return cors;
    }
}
