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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The REST endpoint groups, parsed from the property {@value #PROPERTY} (shared jar copy of
 * {@code com.viscosiety.api.ApiGroups}; the jar cannot depend on the web application module).
 */
public final class ApiGroupNames {

    public static final String PROPERTY = "visco.api.groups";
    static final int MAX = 10;
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{1,29}$");
    private static final Set<String> RESERVED = Set.of("oauth2", "openapi.json", "api-service");
    private static final Logger LOG = Logger.getLogger(ApiGroupNames.class.getName());

    private ApiGroupNames() {
    }

    public static List<String> parse(String raw) {
        List<String> names = new ArrayList<>();
        if (raw == null) {
            return names;
        }
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (name.isEmpty() || names.contains(name)) {
                continue;
            }
            if (!NAME.matcher(name).matches() || RESERVED.contains(name)) {
                LOG.warning("api-groups: [" + name + "] is not a valid group name; ignored");
                continue;
            }
            if (names.size() == MAX) {
                LOG.warning("api-groups: more than " + MAX + " groups; [" + name + "] ignored");
                continue;
            }
            names.add(name);
        }
        return names;
    }
}
