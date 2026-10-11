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
 *   <li>{@code /tester/actuator/health} (and its liveness/readiness groups) — probes; the
 *       TesterRedirectFilter sends {@code /actuator/health} there</li>
 *   <li>{@code /tester/**} — the tester UI, which asks for the store's login itself</li>
 * </ul>
 *
 * <p>{@code spring.mvc.servlet.path=/tester} puts everything the DispatcherServlet serves under
 * {@code /tester}: the tester UI, but also the actuator and HAPI's subscription websocket
 * ({@code /tester/websocket}). Those two must not inherit the tester UI's permitAll, so they are
 * matched first.
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
                    new AntPathRequestMatcher("/tester/actuator/health"),
                    new AntPathRequestMatcher("/tester/actuator/health/**"))
                .permitAll()
                // First match wins: these sit under /tester but are not the tester UI
                .requestMatchers(
                    new AntPathRequestMatcher("/tester/actuator/**"),
                    new AntPathRequestMatcher("/tester/websocket/**"))
                .authenticated()
                .requestMatchers(new AntPathRequestMatcher("/tester/**"))
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
