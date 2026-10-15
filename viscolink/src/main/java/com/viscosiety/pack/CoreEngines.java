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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.lemmabase.lemma.Engine;

/**
 * The versions of the rule engines this core build carries, by the same stable names as
 * {@link CoreFeatures}. Rendered as the descriptor's {@code engines}; clients (viscoFoundry) compare
 * them with the engine they check and evaluate rules with, so a version difference is visible.
 *
 * <p>Each version is read from the loaded engine jar's own manifest ({@code Implementation-Version}),
 * never from a constant, so it cannot drift from the pom. An engine whose version cannot be read is
 * left out rather than guessed.
 */
public final class CoreEngines {

    private CoreEngines() {
    }

    /** {@code {"lemma": "0.10.0"}}, in a fixed order; empty when no version could be read. */
    public static Map<String, String> versions() {
        return versions(CoreEngines::implementationVersion);
    }

    /** Package-private for tests: the version lookup per engine class. */
    static Map<String, String> versions(Function<Class<?>, String> versionOf) {
        Map<String, String> result = new LinkedHashMap<>();
        put(result, "lemma", versionOf.apply(Engine.class));
        return result;
    }

    private static void put(Map<String, String> result, String engine, String version) {
        if (version != null && !version.isBlank()) {
            result.put(engine, version.strip());
        }
    }

    private static String implementationVersion(Class<?> type) {
        Package pkg = type.getPackage();
        return pkg == null ? null : pkg.getImplementationVersion();
    }
}
