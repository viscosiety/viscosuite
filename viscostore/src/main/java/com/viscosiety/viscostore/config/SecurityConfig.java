package com.viscosiety.viscostore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * HTTP Basic auth for all FHIR endpoints.
 *
 * <p>Credentials are configured via environment variables:
 * <ul>
 *   <li>{@code VISCOSTORE_USERNAME} (default: {@code viscolink})</li>
 *   <li>{@code VISCOSTORE_PASSWORD} (required — no insecure default in production)</li>
 * </ul>
 * Set these in {@code application.yaml} via {@code spring.security.user.name/password},
 * or override them per environment.
 *
 * <p>Public paths (no credentials required):
 * <ul>
 *   <li>{@code /fhir/metadata} — FHIR capability statement</li>
 *   <li>{@code /actuator/health} — liveness/readiness probes</li>
 * </ul>
 */
@Profile("!test")
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    new AntPathRequestMatcher("/fhir/metadata"),
                    new AntPathRequestMatcher("/actuator/health"),
                    new AntPathRequestMatcher("/tester/**"))
                .permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults())
            // FHIR REST APIs are stateless — no session or CSRF needed
            .sessionManagement(session -> session
                .sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers
                .frameOptions(frameOptions -> frameOptions.sameOrigin()));

        return http.build();
    }
}
