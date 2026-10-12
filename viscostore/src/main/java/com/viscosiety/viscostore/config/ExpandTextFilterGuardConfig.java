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

import ca.uhn.fhir.rest.server.RestfulServer;
import com.viscosiety.viscostore.operation.ExpandTextFilterGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ExpandTextFilterGuard} while Hibernate Search is off: the same switch that keeps
 * the starter's full-text search service out (StarterJpaConfig.fullTextSearchSvc), seen from the other
 * side. With Hibernate Search on, HAPI handles the text filter itself.
 */
@Configuration
@ConditionalOnProperty(
    name = "spring.jpa.properties.hibernate.search.enabled",
    havingValue = "false",
    matchIfMissing = true)
public class ExpandTextFilterGuardConfig {

    public ExpandTextFilterGuardConfig(RestfulServer restfulServer) {
        restfulServer.registerInterceptor(new ExpandTextFilterGuard());
    }
}
