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

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How a pack names the subject of a message: the session key a pipeline stores it under, the
 * Ladybug metadata field that carries it, and the label the UIs show.
 *
 * @param sessionKey    the pipeline session key holding the subject's identifier
 * @param metadataName  the Ladybug metadata field name
 * @param metadataLabel the Ladybug metadata field label (the column header Ladybug renders)
 * @param displayLabel  the word the viscolink UIs use for the subject ("Patient", "Subject")
 * @param format        optional shape of a valid identifier, which a UI may use to flag a bad value
 */
public record SubjectIdentifier(String sessionKey, String metadataName, String metadataLabel,
                                String displayLabel, Optional<Pattern> format) {
}
