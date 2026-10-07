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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class PackJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode parse(PackDescriptor pack) throws Exception {
        return MAPPER.readTree(PackJson.of(pack));
    }

    @Test
    void healthPackHasExactlyTheDocumentedKeysInTheDocumentedOrder() throws Exception {
        JsonNode json = parse(new HealthValuesPack());
        assertEquals(List.of("id", "displayName", "version", "subject", "consoleViews",
                "frankOwnedPaths", "deidentificationStrategyIds"), keys(json));
        assertEquals(List.of("sessionKey", "metadataName", "metadataLabel", "label", "format"),
                keys(json.get("subject")));
    }

    @Test
    void healthPackValues() throws Exception {
        JsonNode json = parse(new HealthValuesPack());
        assertEquals("health", json.get("id").asText());
        assertEquals("Healthcare", json.get("displayName").asText());
        assertEquals(new HealthValuesPack().version(), json.get("version").asText());

        JsonNode subject = json.get("subject");
        assertEquals("patientId", subject.get("sessionKey").asText());
        assertEquals("patientId", subject.get("metadataName").asText());
        assertEquals("PatientId", subject.get("metadataLabel").asText());
        assertEquals("Patient", subject.get("label").asText());
        assertTrue(subject.get("format").isNull(), "format is JSON null, not missing and not \"null\"");

        assertEquals("[\"/fhir/\"]", json.get("frankOwnedPaths").toString());
        assertEquals("[\"fhir-patient\",\"hl7v2\"]", json.get("deidentificationStrategyIds").toString());
    }

    @Test
    void corePackSerialisesEmptyArraysNotMissingKeys() throws Exception {
        JsonNode json = parse(new CorePack());
        assertEquals(List.of("id", "displayName", "version", "subject", "consoleViews",
                "frankOwnedPaths", "deidentificationStrategyIds"), keys(json));
        assertEquals("core", json.get("id").asText());
        assertEquals("Core", json.get("displayName").asText());
        for (String array : List.of("consoleViews", "frankOwnedPaths", "deidentificationStrategyIds")) {
            assertTrue(json.get(array).isArray(), array);
            assertEquals(0, json.get(array).size(), array);
        }
        JsonNode subject = json.get("subject");
        assertEquals("subjectId", subject.get("sessionKey").asText());
        assertEquals("subjectId", subject.get("metadataName").asText());
        assertEquals("SubjectId", subject.get("metadataLabel").asText());
        assertEquals("Subject", subject.get("label").asText());
        assertTrue(subject.get("format").isNull());
    }

    @Test
    void eachSubjectFieldIsRenderedUnderItsOwnKey() throws Exception {
        // The shipped packs use one value for sessionKey and metadataName, so a swapped pair of keys
        // would go unnoticed against them; with four different values each key must carry its own.
        JsonNode subject = parse(new DistinctSubjectPack()).get("subject");
        assertEquals("sk", subject.get("sessionKey").asText());
        assertEquals("mn", subject.get("metadataName").asText());
        assertEquals("ML", subject.get("metadataLabel").asText());
        assertEquals("DL", subject.get("label").asText());
    }

    @Test
    void propertyDefaultsAreNeverSerialised() throws Exception {
        PackDescriptor withDefaults = new DistinctSubjectPack() {
            @Override
            public Map<String, String> propertyDefaults() {
                return Map.of("pack.secret.key", "pack-secret-value");
            }
        };

        String json = PackJson.of(withDefaults);

        assertFalse(json.contains("pack.secret.key"), json);
        assertFalse(json.contains("pack-secret-value"), json);
        assertFalse(json.contains("propertyDefaults"), json);
        assertEquals(List.of("id", "displayName", "version", "subject", "consoleViews",
                "frankOwnedPaths", "deidentificationStrategyIds"), keys(MAPPER.readTree(json)));
    }

    @Test
    void aFormatIsRenderedAsThePatternSource() throws Exception {
        JsonNode withFormat = parse(new FormatPack(Pattern.compile("^\\d{9}$"), List.of()));
        assertEquals("^\\d{9}$", withFormat.get("subject").get("format").asText());
    }

    @Test
    void consoleViewsAreRenderedWithNameUrlAndTarget() throws Exception {
        JsonNode json = parse(new FormatPack(null, List.of(new ConsoleView("Views", "/v/", "_self"))));
        JsonNode view = json.get("consoleViews").get(0);
        assertEquals(List.of("name", "url", "target"), keys(view));
        assertEquals("Views", view.get("name").asText());
        assertEquals("/v/", view.get("url").asText());
        assertEquals("_self", view.get("target").asText());
    }

    /** A descriptor with a subject format and views, which neither shipped pack has. */
    private record FormatPack(Pattern format, List<ConsoleView> views) implements PackDescriptor {
        @Override public String id() { return "public"; }
        @Override public String displayName() { return "Public sector"; }
        @Override public String version() { return "1"; }
        @Override public SubjectIdentifier subject() {
            return new SubjectIdentifier("bsn", "bsn", "Bsn", "BSN", Optional.ofNullable(format));
        }
        @Override public List<ConsoleView> consoleViews() { return views; }
        @Override public List<String> frankOwnedPaths() { return List.of(); }
        @Override public Map<String, String> propertyDefaults() { return Map.of(); }
        @Override public List<String> deidentificationStrategyIds() { return List.of(); }
    }
}
