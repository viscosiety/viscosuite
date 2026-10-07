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

package com.viscosiety.pack.health;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Text assertions on the pack's Ladybug changelog: Liquibase itself runs it live (Task 6 and the ITs).
 * The core's DatabaseChangelog_Custom.xml includes this file when a pack brings one.
 */
class HealthLadybugColumnTest {

    private static String changelog() throws Exception {
        try (InputStream in = HealthLadybugColumnTest.class.getResourceAsStream("/ladybug/DatabaseChangelog_Pack.xml")) {
            assertNotNull(in, "the pack's ladybug/DatabaseChangelog_Pack.xml");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void thePackAddsThePatientIdColumn() throws Exception {
        assertTrue(changelog().contains("name=\"patientid\""));
    }

    @Test
    void anExistingHealthDatabaseKeepsItsColumnThroughTheMarkRanPrecondition() throws Exception {
        // Health databases got the column from the core's old LadybugCustom:2; re-adding it would fail the start-up.
        // The precondition must be NEGATED: MARK_RAN when the column already exists, not when it is missing.
        assertTrue(Pattern.compile("(?s)<preConditions onFail=\"MARK_RAN\">.*?<not>\\s*<columnExists tableName=\"LADYBUG\" columnName=\"patientid\"/>\\s*</not>.*?</preConditions>")
                .matcher(changelog()).find());
    }
}
