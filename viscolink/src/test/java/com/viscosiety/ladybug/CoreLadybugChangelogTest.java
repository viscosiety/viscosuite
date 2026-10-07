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

package com.viscosiety.ladybug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Text assertions on the core's Ladybug changelog: Liquibase itself runs it live (Task 6 and the ITs).
 * The core owns the market-neutral subject column; the column of a pack's own subject identifier
 * (the health pack's patientid) comes from the pack's changelog, included if it is on the class path.
 */
class CoreLadybugChangelogTest {

    private static String changelog() throws Exception {
        try (InputStream in = CoreLadybugChangelogTest.class.getResourceAsStream("/ladybug/DatabaseChangelog_Custom.xml")) {
            assertNotNull(in, "the core's ladybug/DatabaseChangelog_Custom.xml");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theCoreAddsTheSubjectColumnAndNoHealthColumn() throws Exception {
        String xml = changelog();
        assertTrue(xml.contains("name=\"subjectid\""), "the core's subject column");
        assertFalse(xml.contains("patientid"), "patientid belongs to the health pack's changelog");
    }

    @Test
    void theCoreIncludesThePacksChangelogWhenThereIsOne() throws Exception {
        assertTrue(changelog().contains("<include file=\"ladybug/DatabaseChangelog_Pack.xml\""));
    }
}
