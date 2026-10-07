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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wearefrank.ladybug.Checkpoint;
import org.wearefrank.ladybug.Report;

class SubjectMetadataFieldExtractorTest {

    @AfterEach
    void clearOverride() {
        PackRegistry.reset();
    }

    @Test
    void healthPackKeepsTodaysPatientIdField() {
        PackRegistry.override(new HealthValuesPack());

        SubjectMetadataFieldExtractor extractor = new SubjectMetadataFieldExtractor();

        assertEquals("patientId", extractor.getName());
        assertEquals("PatientId", extractor.getLabel());
        assertEquals("P-42", extractor.extractMetadata(reportWithSessionKey("patientId", "P-42")),
                "the extractor must read the session key patientId, as the hardcoded bean did");
        assertNull(extractor.extractMetadata(reportWithSessionKey("subjectId", "S-7")));
    }

    @Test
    void corePackUsesTheNeutralSubjectField() {
        PackRegistry.override(new CorePack());

        SubjectMetadataFieldExtractor extractor = new SubjectMetadataFieldExtractor();

        assertEquals("subjectId", extractor.getName());
        assertEquals("SubjectId", extractor.getLabel());
        assertEquals("S-7", extractor.extractMetadata(reportWithSessionKey("subjectId", "S-7")));
        assertNull(extractor.extractMetadata(reportWithSessionKey("patientId", "P-42")));
    }

    @Test
    void eachSubjectFieldLandsInItsOwnPlace() {
        // The shipped packs use one value for sessionKey and metadataName, so only distinct values prove
        // which field feeds which part of the extractor.
        PackRegistry.override(new DistinctSubjectPack());

        SubjectMetadataFieldExtractor extractor = new SubjectMetadataFieldExtractor();

        assertEquals("mn", extractor.getName(), "the column name is the pack's metadataName");
        assertEquals("ML", extractor.getLabel(), "the column label is the pack's metadataLabel, not its displayLabel");
        assertEquals("S-1", extractor.extractMetadata(reportWithSessionKey("sk", "S-1")),
                "the value is read from the session key named by the pack's sessionKey");
        assertNull(extractor.extractMetadata(reportWithSessionKey("mn", "S-1")), "not from the metadata name");
        assertNull(extractor.extractMetadata(reportWithSessionKey("ML", "S-1")), "not from the metadata label");
        assertNull(extractor.extractMetadata(reportWithSessionKey("DL", "S-1")), "not from the display label");
    }

    @Test
    void theSubjectIsFixedWhenTheExtractorIsConstructed() {
        PackRegistry.override(new HealthValuesPack());
        SubjectMetadataFieldExtractor first = new SubjectMetadataFieldExtractor();
        PackRegistry.override(new CorePack());
        SubjectMetadataFieldExtractor second = new SubjectMetadataFieldExtractor();

        assertEquals("patientId", first.getName(), "an extractor keeps the pack it was constructed under");
        assertEquals("subjectId", second.getName(), "a new extractor sees the pack that is current now");
    }

    /** A report whose only checkpoint is the one Ladybug records for a pipeline session key. */
    static Report reportWithSessionKey(String key, String value) {
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.getName()).thenReturn("SessionKey " + key);
        when(checkpoint.getMessage()).thenReturn(value);
        Report report = mock(Report.class);
        when(report.getCheckpoints()).thenReturn(List.of(checkpoint));
        return report;
    }
}
