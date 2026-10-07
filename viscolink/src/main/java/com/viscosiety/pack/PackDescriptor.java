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
 * What a vertical pack tells the market-neutral core about itself. Discovered through
 * {@link java.util.ServiceLoader}; the core accepts zero or one (see {@link PackRegistry}).
 *
 * <p>Nothing secret belongs here: the descriptor is rendered as JSON for authenticated
 * clients (see {@link PackJson}).</p>
 *
 * <p>A pack's property defaults are not part of the descriptor: they are the
 * {@code DeploymentSpecifics.properties} of its jar (add-only keys).</p>
 */
public interface PackDescriptor {

    /** Short stable identifier, e.g. {@code "health"}. */
    String id();

    /** Human-readable name, e.g. {@code "Healthcare"}. */
    String displayName();

    /** The version of the jar that carries the pack. */
    String version();

    /** The identifier of the person or thing a message is about. */
    SubjectIdentifier subject();

    /**
     * Extra Frank!Console views the pack contributes. Informational: the mirror of the pack's
     * {@code customViews.<name>.*} properties, which the pack's own {@code DeploymentSpecifics.properties}
     * declares (see the design's section 10).
     */
    List<ConsoleView> consoleViews();

    /**
     * Path prefixes handed to the Frank!Framework's own security chain (e.g. {@code "/fhir/"}). Each
     * must start and end with {@code /} and not consist only of slashes, and is matched with
     * {@code startsWith} against the context-relative request path; the registry rejects anything
     * else when it resolves the pack.
     */
    List<String> frankOwnedPaths();

    /** Names only; the implementations live in viscoForge. */
    List<String> deidentificationStrategyIds();
}
