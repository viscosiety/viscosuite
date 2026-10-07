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
import java.util.Optional;

import com.viscosiety.components.ViscoLinkModule;

/**
 * The descriptor that applies when no pack is on the class path, so every consumer of
 * {@link PackRegistry#get()} always has a descriptor to read.
 */
public final class CorePack implements PackDescriptor {

    private static final SubjectIdentifier SUBJECT =
            new SubjectIdentifier("subjectId", "subjectId", "SubjectId", "Subject", Optional.empty());

    @Override
    public String id() {
        return "core";
    }

    @Override
    public String displayName() {
        return "Core";
    }

    @Override
    public String version() {
        return ViscoLinkModule.IMPLEMENTATION_VERSION;
    }

    @Override
    public SubjectIdentifier subject() {
        return SUBJECT;
    }

    @Override
    public List<ConsoleView> consoleViews() {
        return List.of();
    }

    @Override
    public List<String> frankOwnedPaths() {
        return List.of();
    }

    @Override
    public List<String> deidentificationStrategyIds() {
        return List.of();
    }
}
