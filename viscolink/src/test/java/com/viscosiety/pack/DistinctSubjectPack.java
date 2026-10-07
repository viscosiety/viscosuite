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
import java.util.Map;
import java.util.Optional;

/**
 * A descriptor whose four subject fields all differ, unlike the health and core packs, which use the
 * same value for {@code sessionKey} and {@code metadataName}. A consumer that reads the wrong field
 * (or the right one for the wrong purpose) passes against either shipped pack and fails against this
 * one. Open for subclassing so a test can vary one more thing.
 */
public class DistinctSubjectPack implements PackDescriptor {

    public static final String SESSION_KEY = "sk";
    public static final String METADATA_NAME = "mn";
    public static final String METADATA_LABEL = "ML";
    public static final String DISPLAY_LABEL = "DL";

    @Override
    public String id() {
        return "distinct";
    }

    @Override
    public String displayName() {
        return "Distinct";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public SubjectIdentifier subject() {
        return new SubjectIdentifier(SESSION_KEY, METADATA_NAME, METADATA_LABEL, DISPLAY_LABEL, Optional.empty());
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
    public Map<String, String> propertyDefaults() {
        return Map.of();
    }

    @Override
    public List<String> deidentificationStrategyIds() {
        return List.of();
    }
}
