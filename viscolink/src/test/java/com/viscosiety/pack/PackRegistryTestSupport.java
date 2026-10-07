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

import java.util.Collection;

/**
 * Lets tests in other packages swap the registry's descriptor. {@link PackRegistry#override},
 * {@link PackRegistry#overrideCandidates} and {@link PackRegistry#reset} are package-private on
 * purpose (a production caller must not be able to replace the descriptor), so this class lives in
 * the same package, in the test tree only.
 */
public final class PackRegistryTestSupport {

    private PackRegistryTestSupport() {
    }

    public static void override(PackDescriptor pack) {
        PackRegistry.override(pack);
    }

    /** The next {@code PackRegistry.get()} resolves these as the class path's candidates, validation included. */
    public static void overrideCandidates(Collection<? extends PackDescriptor> candidates) {
        PackRegistry.overrideCandidates(candidates);
    }

    public static void reset() {
        PackRegistry.reset();
    }
}
