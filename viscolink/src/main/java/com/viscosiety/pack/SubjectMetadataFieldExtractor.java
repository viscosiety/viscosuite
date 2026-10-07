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

import org.wearefrank.ladybug.metadata.SessionKeyMetadataFieldExtractor;

/**
 * Ladybug metadata field for the pack's subject identifier: the column (name and label) and the
 * pipeline session key it is read from all come from {@link PackRegistry#get()}{@code .subject()}.
 *
 * <p>Spring creates this bean from {@code springIbisTestToolVisco.xml} with no properties, so the
 * subject is read once, in the constructor. With the health pack that is exactly the
 * {@code patientId} / {@code PatientId} / {@code patientId} the bean used to be configured with.</p>
 */
public class SubjectMetadataFieldExtractor extends SessionKeyMetadataFieldExtractor {

    public SubjectMetadataFieldExtractor() {
        SubjectIdentifier subject = PackRegistry.get().subject();
        name = subject.metadataName();
        label = subject.metadataLabel();
        sessionKey = subject.sessionKey();
    }
}
