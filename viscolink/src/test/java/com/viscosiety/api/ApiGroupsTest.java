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

package com.viscosiety.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApiGroupsTest {

    @AfterEach
    void clear() {
        System.clearProperty(ApiGroups.PROPERTY);
        ApiGroups.resetForTests();
    }

    @Test
    void parsesTrimsDropsDuplicatesAndBadNames() {
        assertEquals(List.of("webhooks", "public-v2"),
                ApiGroups.parse(" webhooks, public-v2 ,webhooks,Bad,x,oauth2,openapi.json,api-service,"));
    }

    @Test
    void keepsAtMostTen() {
        String raw = String.join(",", List.of("aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", "ii", "jj", "kk"));
        assertEquals(10, ApiGroups.parse(raw).size());
    }

    @Test
    void emptyOrMissingMeansNoGroups() {
        assertEquals(List.of(), ApiGroups.parse(null));
        assertEquals(List.of(), ApiGroups.parse(" , "));
        assertEquals(List.of(), ApiGroups.names());
    }

    @Test
    void groupOfReadsTheFirstSegment() {
        ApiGroups.resetForTests();
        System.setProperty(ApiGroups.PROPERTY, "webhooks");
        assertEquals("webhooks", ApiGroups.groupOf("/webhooks/github"));
        assertEquals("webhooks", ApiGroups.groupOf("/webhooks"));
        assertEquals("webhooks", ApiGroups.groupOf("/webhooks/"));
        assertNull(ApiGroups.groupOf("/webhooksx/a"));
        assertNull(ApiGroups.groupOf("/orders"));
        assertNull(ApiGroups.groupOf(null));
        assertNull(ApiGroups.groupOf("/openapi.json"));
    }

    @Test
    void namesAreReadOnce() {
        ApiGroups.resetForTests();
        System.setProperty(ApiGroups.PROPERTY, "webhooks");
        assertEquals(List.of("webhooks"), ApiGroups.names());
        System.setProperty(ApiGroups.PROPERTY, "orders");
        assertEquals(List.of("webhooks"), ApiGroups.names());
    }
}
