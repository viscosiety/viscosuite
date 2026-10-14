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
package com.viscosiety.pipes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.lemmabase.lemma.EngineError;
import com.lemmabase.lemma.Show;
import com.lemmabase.lemma.schema.ShowPropertyType;

/** Pure helpers of the Lemma pipe: what goes into the engine and how its errors read. */
final class LemmaInputs {

    /** A {@code uses} line naming a LemmaBase repository: {@code uses @o/r spec} or {@code uses alias: @o/r spec}. */
    private static final Pattern REGISTRY_USES =
            Pattern.compile("^\\s*uses\\s+(?:\\w+\\s*:\\s*)?@\\S+.*$", Pattern.MULTILINE);
    private static final Pattern BARE_NUMBER = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");

    private LemmaInputs() {
    }

    static Optional<String> registryImport(String source) {
        var matcher = REGISTRY_USES.matcher(source);
        return matcher.find() ? Optional.of(matcher.group().strip()) : Optional.empty();
    }

    static Map<String, String> singleUnits(Show show) {
        Map<String, String> units = new LinkedHashMap<>();
        show.data().forEach((name, data) -> {
            if (data.type() instanceof ShowPropertyType.Measure measure && measure.units().size() == 1) {
                units.put(name, measure.units().get(0).name());
            }
        });
        return units;
    }

    static Map<String, Object> toData(Map<String, String> values, Map<String, String> singleUnits) {
        Map<String, Object> data = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                return;
            }
            String trimmed = value.strip();
            String unit = singleUnits.get(name);
            data.put(name, unit != null && BARE_NUMBER.matcher(trimmed).matches() ? trimmed + " " + unit : value);
        });
        return data;
    }

    static String describe(List<EngineError> errors) {
        return errors.stream().map(LemmaInputs::describe).collect(Collectors.joining("; "));
    }

    private static String describe(EngineError error) {
        var source = error.source();
        if (source != null && source.attribute() != null) {
            return source.attribute() + ":" + source.line() + ":" + source.column() + " " + error.message();
        }
        return error.spec() != null ? "spec " + error.spec() + ": " + error.message() : error.message();
    }
}
