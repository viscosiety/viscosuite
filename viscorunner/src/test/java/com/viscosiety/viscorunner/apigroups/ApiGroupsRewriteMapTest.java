package com.viscosiety.viscorunner.apigroups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ApiGroupsRewriteMapTest {

    @Test
    void answersYesForListedGroupsOnly() {
        System.setProperty(ApiGroupNames.PROPERTY, "webhooks");
        try {
            ApiGroupsRewriteMap map = new ApiGroupsRewriteMap();
            map.setParameters((String) null);
            assertEquals("yes", map.lookup("webhooks"));
            assertNull(map.lookup("orders"));
            assertNull(map.lookup(null));
        } finally {
            System.clearProperty(ApiGroupNames.PROPERTY);
        }
    }

    @Test
    void readsTheListWithoutSetParametersBecauseTomcatDoesNotCallItForAMapWithoutParameters() {
        System.setProperty(ApiGroupNames.PROPERTY, "webhooks, partners");
        try {
            ApiGroupsRewriteMap map = new ApiGroupsRewriteMap();
            assertEquals("yes", map.lookup("partners"));
            assertNull(map.lookup("webhooksx"));
        } finally {
            System.clearProperty(ApiGroupNames.PROPERTY);
        }
    }
}
