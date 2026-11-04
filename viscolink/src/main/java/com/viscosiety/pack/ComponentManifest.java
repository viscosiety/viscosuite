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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the component fetcher composed this runtime with: the release and every component, read
 * from the {@code MANIFEST} it writes. A runtime without the file (a fat image built before the
 * fetcher existed, a plain Tomcat with the WAR) reports no release and no components. The file's
 * format is the fetcher's contract: {@code release=<version>} then {@code component=<id> <kind>
 * <version>} per line; blank lines and {@code #} lines are ignored, anything else is an error
 * (the fetcher writes it, so a malformed file is a bug, not a condition to hide).
 */
public final class ComponentManifest {

    public static final String PATH_PROPERTY = "components.manifest";
    public static final String DEFAULT_PATH = "/opt/frank/components/MANIFEST";
    public static final ComponentManifest NONE = new ComponentManifest(null, List.of());

    public record Component(String id, String kind, String version) {
    }

    private final String release;
    private final List<Component> components;

    private ComponentManifest(String release, List<Component> components) {
        this.release = release;
        this.components = components;
    }

    public Optional<String> release() {
        return Optional.ofNullable(release);
    }

    public List<Component> components() {
        return components;
    }

    /** The manifest of this runtime: the file named by {@value #PATH_PROPERTY}, by default {@value #DEFAULT_PATH}. */
    public static ComponentManifest current() {
        return read(Path.of(System.getProperty(PATH_PROPERTY, DEFAULT_PATH)));
    }

    public static ComponentManifest read(Path file) {
        if (!Files.isRegularFile(file)) {
            return NONE;
        }
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the component manifest " + file, e);
        }
    }

    static ComponentManifest parse(List<String> lines) {
        String release = null;
        List<Component> found = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("release=")) {
                String value = line.substring("release=".length()).strip();
                release = value.isEmpty() ? null : value;
                continue;
            }
            if (line.startsWith("component=")) {
                String[] fields = line.substring("component=".length()).strip().split("\\s+");
                if (fields.length != 3) {
                    throw new IllegalArgumentException("MANIFEST line is not 'component=<id> <kind> <version>': " + line);
                }
                found.add(new Component(fields[0], fields[1], fields[2]));
                continue;
            }
            throw new IllegalArgumentException("MANIFEST line not understood: " + line);
        }
        return new ComponentManifest(release, List.copyOf(found));
    }
}
