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

package com.viscosiety.viscorunner.apigroups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiGroupNamesTest {

    @Test
    void parsesTrimsDropsDuplicatesAndBadNames() {
        assertEquals(List.of("webhooks", "public-v2"),
                ApiGroupNames.parse(" webhooks, public-v2 ,webhooks,Bad,x,oauth2,openapi.json,api-service,"));
    }

    @Test
    void keepsAtMostTen() {
        String raw = String.join(",", List.of("aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", "ii", "jj", "kk"));
        assertEquals(10, ApiGroupNames.parse(raw).size());
    }

    @Test
    void emptyOrMissingMeansNoGroups() {
        assertEquals(List.of(), ApiGroupNames.parse(null));
        assertEquals(List.of(), ApiGroupNames.parse(" , "));
    }

    @Test
    void propertyNameIsTheOneTheWebApplicationReads() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../viscolink/src/main/java/com/viscosiety/api/ApiGroups.java"));
        assertTrue(source.contains("PROPERTY = \"" + ApiGroupNames.PROPERTY + "\""));
        assertTrue(source.contains("\"^[a-z][a-z0-9-]{1,29}$\""));
        assertTrue(source.contains("Set.of(\"oauth2\", \"openapi.json\", \"api-service\")"));
    }
}
