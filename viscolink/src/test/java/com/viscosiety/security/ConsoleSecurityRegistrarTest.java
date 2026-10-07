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

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.viscosiety.pack.CorePack;
import com.viscosiety.pack.PackDescriptor;
import com.viscosiety.pack.PackRegistryTestSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsoleSecurityRegistrarTest {

    @AfterEach
    void resetPack() {
        PackRegistryTestSupport.reset();
    }

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

    @Test
    void fhirPathIsNotFrankOwnedWithoutThePack() {
        // /fhir/ is the health pack's path, not a core one: under the core pack it is a tool page again.
        PackRegistryTestSupport.override(new CorePack());
        assertFalse(ConsoleSecurityRegistrar.isFrankOwnedPath("/fhir/x"));
    }

    @Test
    void corePrefixesStayFrankOwnedWithoutThePack() {
        PackRegistryTestSupport.override(new CorePack());
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/iaf/x"));
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/api/x"));
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/api-service/x"));
    }

    @Test
    void aPacksOwnPathIsFrankOwned() {
        PackDescriptor pack = mock(PackDescriptor.class);
        when(pack.frankOwnedPaths()).thenReturn(List.of("/vendor/", "/other/"));
        PackRegistryTestSupport.override(pack);

        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/vendor/x"));
        assertTrue(ConsoleSecurityRegistrar.isFrankOwnedPath("/other/y"));
        assertFalse(ConsoleSecurityRegistrar.isFrankOwnedPath("/fhir/x"));
        assertFalse(ConsoleSecurityRegistrar.isFrankOwnedPath("/tools/some-tool"));
    }
}
