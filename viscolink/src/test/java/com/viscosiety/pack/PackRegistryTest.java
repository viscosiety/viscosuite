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

package com.viscosiety.pack;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PackRegistryTest {

    @AfterEach
    void clearOverride() {
        PackRegistry.reset();
    }

    @Test
    void noCandidatesResolvesToTheCorePack() {
        assertInstanceOf(CorePack.class, PackRegistry.resolve(List.of()));
    }

    @Test
    void oneCandidateIsThatCandidate() {
        PackDescriptor only = new StubPack("public");
        assertSame(only, PackRegistry.resolve(List.of(only)));
    }

    @Test
    void twoCandidatesFailFastNamingBothIds() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> PackRegistry.resolve(List.of(new StubPack("health"), new StubPack("public"))));
        assertTrue(e.getMessage().contains("health"), e.getMessage());
        assertTrue(e.getMessage().contains("public"), e.getMessage());
    }

    @Test
    void getResolvesTheHealthPackFromTheServicesFile() {
        assertInstanceOf(HealthPack.class, PackRegistry.get());
    }

    @Test
    void getReturnsTheSameInstanceTwice() {
        assertSame(PackRegistry.get(), PackRegistry.get());
    }

    @Test
    void overrideReplacesTheCachedPackAndResetRestoresResolution() {
        PackDescriptor stub = new StubPack("public");
        PackRegistry.override(stub);
        assertSame(stub, PackRegistry.get());

        PackRegistry.reset();
        assertInstanceOf(HealthPack.class, PackRegistry.get());
    }

    /** A candidate that is neither of the shipped packs. */
    static final class StubPack implements PackDescriptor {
        private final String id;

        StubPack(String id) {
            this.id = id;
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public String version() { return "0"; }
        @Override public SubjectIdentifier subject() {
            return new SubjectIdentifier("s", "s", "S", "S", Optional.empty());
        }
        @Override public List<ConsoleView> consoleViews() { return List.of(); }
        @Override public List<String> frankOwnedPaths() { return List.of(); }
        @Override public Map<String, String> propertyDefaults() { return Map.of(); }
        @Override public List<String> deidentificationStrategyIds() { return List.of(); }
    }
}
