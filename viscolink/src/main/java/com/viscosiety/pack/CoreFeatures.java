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

import java.util.List;

/**
 * What this core build can do beyond the Frank!Framework, by stable name. Rendered as the
 * descriptor's {@code features}; clients (viscoFoundry, the ViscoForge console) use it to tell
 * which runner images carry a capability. A core list, not part of the {@link PackDescriptor} SPI:
 * a pack does not change it. Names are append-only; never rename one.
 */
public final class CoreFeatures {

    /** {@code lemma}: {@code com.viscosiety.pipes.LemmaPipe} is on the class path. */
    public static final List<String> ALL = List.of("lemma");

    private CoreFeatures() {
    }
}
