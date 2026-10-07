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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The pack's own version, from the build-filtered {@code pack-version.properties}. */
public final class PackVersion {

    private static final String RESOURCE = "/pack-version.properties";

    private PackVersion() {
    }

    public static String get() {
        try (InputStream in = PackVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " missing from the pack jar");
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "").trim();
            if (v.isEmpty() || v.startsWith("${")) {
                throw new IllegalStateException(RESOURCE + " was not filtered at build time: " + v);
            }
            return v;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }
}
