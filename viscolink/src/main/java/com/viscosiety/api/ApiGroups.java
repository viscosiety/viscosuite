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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The REST endpoint groups this runtime serves (viscoFoundry spec 2026-11-27 api-endpoint-groups): the property
 * {@value #PROPERTY}, a comma list of names. A group {@code webhooks} answers {@code /runtime/api/webhooks/...} under
 * the login of servlet {@code api-webhooks}. Same rules as
 * {@code com.viscosiety.viscorunner.apigroups.ApiGroupNames} in the shared jar (kept apart: that jar cannot depend on
 * this module); ApiGroupNamesTest pins the two together.
 */
public final class ApiGroups {

    public static final String PROPERTY = "visco.api.groups";
    static final int MAX = 10;
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{1,29}$");
    private static final Set<String> RESERVED = Set.of("oauth2", "openapi.json", "api-service");
    private static final Logger LOG = LogManager.getLogger(ApiGroups.class);

    private ApiGroups() {
    }

    /** Read on every call: the property is set before the runtime starts. */
    public static List<String> names() {
        String raw = System.getProperty(PROPERTY);
        return parse(raw != null ? raw : System.getenv(PROPERTY));
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
                LOG.warn("api-groups: [{}] is not a valid group name; ignored", name);
                continue;
            }
            if (names.size() == MAX) {
                LOG.warn("api-groups: more than {} groups; [{}] ignored", MAX, name);
                continue;
            }
            names.add(name);
        }
        return names;
    }

    /** The listed group named by the first segment of a path info ({@code /webhooks/github} -> webhooks), else null. */
    public static String groupOf(String pathInfo) {
        if (pathInfo == null || !pathInfo.startsWith("/")) {
            return null;
        }
        String rest = pathInfo.substring(1);
        int slash = rest.indexOf('/');
        String first = slash < 0 ? rest : rest.substring(0, slash);
        return names().contains(first) ? first : null;
    }
}
