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

import ca.uhn.fhir.jpa.starter.AppProperties;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when {@code hapi.fhir.cors} allows credentials for every origin.
 *
 * <p>CORS is off unless application.yaml (or the environment) sets {@code hapi.fhir.cors}. With
 * {@code allow_Credentials: true} and the origin {@code "*"}, the starter's CorsInterceptor answers
 * any web site's credentialed request with that site's origin plus
 * {@code Access-Control-Allow-Credentials: true}, so a page on any site can read and write the store
 * through a browser that holds a ViscoStore login. Credentials need explicit origins; an origin
 * pattern such as {@code https://*.example.org} is a deliberate choice and is allowed.
 *
 * <p>Lives here rather than in the starter's StarterJpaConfig so the starter's files stay merge-clean
 * for the next HAPI bump.
 */
@Component
public class CorsSettingsGuard {

    public CorsSettingsGuard(AppProperties appProperties) {
        check(appProperties.getCors());
    }

    static void check(AppProperties.Cors cors) {
        if (cors == null || !Boolean.TRUE.equals(cors.getAllow_Credentials())) {
            return;
        }
        // getAllowed_origin() falls back to "*" when no origin is listed
        boolean anyOrigin = cors.getAllowed_origin().stream()
            .anyMatch(origin -> origin != null && origin.trim().equals("*"));
        if (anyOrigin) {
            throw new IllegalStateException(
                "hapi.fhir.cors.allow_Credentials is true while hapi.fhir.cors.allowed_origin allows every "
                    + "origin (\"*\", also the default when no origin is listed): list the origins that may "
                    + "call ViscoStore with credentials.");
        }
    }
}
