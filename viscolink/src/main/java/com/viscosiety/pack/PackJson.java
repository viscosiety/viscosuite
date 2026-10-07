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

import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Renders a {@link PackDescriptor} as the JSON the descriptor endpoints serve. The keys come out
 * in a fixed order and the arrays are never omitted, so clients can rely on the shape.
 */
public final class PackJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PackJson() {
    }

    public static String of(PackDescriptor pack) {
        ObjectNode root = JSON.createObjectNode();
        root.put("id", pack.id());
        root.put("displayName", pack.displayName());
        root.put("version", pack.version());

        SubjectIdentifier subject = pack.subject();
        ObjectNode subjectNode = root.putObject("subject");
        subjectNode.put("sessionKey", subject.sessionKey());
        subjectNode.put("metadataName", subject.metadataName());
        subjectNode.put("metadataLabel", subject.metadataLabel());
        subjectNode.put("label", subject.displayLabel());
        // The pattern's source, or null, so a client can compile it with its own regex engine.
        subjectNode.put("format", subject.format().map(Pattern::pattern).orElse(null));

        ArrayNode views = root.putArray("consoleViews");
        for (ConsoleView view : pack.consoleViews()) {
            ObjectNode viewNode = views.addObject();
            viewNode.put("name", view.name());
            viewNode.put("url", view.url());
            viewNode.put("target", view.target());
        }

        ArrayNode paths = root.putArray("frankOwnedPaths");
        pack.frankOwnedPaths().forEach(paths::add);

        ArrayNode strategies = root.putArray("deidentificationStrategyIds");
        pack.deidentificationStrategyIds().forEach(strategies::add);

        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // Unreachable for a tree of strings and arrays.
            throw new IllegalStateException("Cannot render the descriptor of pack " + pack.id(), e);
        }
    }
}
