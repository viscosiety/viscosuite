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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Resolves the one {@link PackDescriptor} of this JVM: none on the class path means
 * {@link CorePack}, one means that one, two or more is a configuration error that fails fast
 * at first use (which is context start).
 */
public final class PackRegistry {

    private static volatile PackDescriptor cached;

    private PackRegistry() {
    }

    /** The resolution rule over any candidates; kept free of {@link ServiceLoader} so it can be tested directly. */
    static PackDescriptor resolve(Collection<? extends PackDescriptor> candidates) {
        if (candidates.isEmpty()) {
            return new CorePack();
        }
        if (candidates.size() == 1) {
            return candidates.iterator().next();
        }
        List<String> ids = new ArrayList<>();
        for (PackDescriptor candidate : candidates) {
            ids.add(candidate.id());
        }
        throw new IllegalStateException("At most one vertical pack may be on the class path, but found "
                + ids.size() + ": " + String.join(", ", ids));
    }

    /** The descriptor every consumer reads; resolved once per JVM and cached. */
    public static PackDescriptor get() {
        PackDescriptor pack = cached;
        if (pack == null) {
            synchronized (PackRegistry.class) {
                pack = cached;
                if (pack == null) {
                    pack = resolve(discover());
                    cached = pack;
                }
            }
        }
        return pack;
    }

    private static List<PackDescriptor> discover() {
        // This class's own loader, not the thread context loader: a pack jar sits beside viscolink
        // (webapp or a parent loader), and the context loader of the calling thread may be neither.
        List<PackDescriptor> found = new ArrayList<>();
        ServiceLoader.load(PackDescriptor.class, PackRegistry.class.getClassLoader()).forEach(found::add);
        return found;
    }

    /** Tests only: replace the cached descriptor until {@link #reset()}. */
    static void override(PackDescriptor pack) {
        synchronized (PackRegistry.class) {
            cached = pack;
        }
    }

    /** Tests only: forget the cached or overridden descriptor so the next {@link #get()} resolves again. */
    static void reset() {
        synchronized (PackRegistry.class) {
            cached = null;
        }
    }
}
