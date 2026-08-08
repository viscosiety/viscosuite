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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleSecurityRegistrarTest {

    @Test
    void iafPathIsFrankOwned() {
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/iaf/gui/"));
    }

    @Test
    void apiPathIsFrankOwned() {
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/api/whatever"));
    }

    @Test
    void fhirPathIsFrankOwned() {
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/fhir/r4/facade"));
    }

    @Test
    void apiServicePathIsFrankOwned() {
        // The new BEARER_ONLY reload endpoint secures itself; the console's own session-based
        // tool-page filter must never also try to gate it.
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/api-service/configurations"));
    }

    @Test
    void toolPageIsNotFrankOwned() {
        assertFalse(ConsoleSecurityRegistrar.isFrankOwnedPath("/tools/some-tool"));
    }

    @Test
    void rootPathIsNotFrankOwned() {
        assertFalse(ConsoleSecurityRegistrar.isFrankOwnedPath("/"));
    }
}
