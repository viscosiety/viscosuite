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

package com.viscosiety.mllp;

import org.jspecify.annotations.NonNull;

import org.frankframework.resourcelocator.FrankResource;
import org.frankframework.resourcelocator.ObjectFactory;

/**
 * Spring-managed factory that resolves {@code mllp} resource entries from
 * {@code resources.yml} into {@link MllpConnectionFactory} instances.
 *
 * <p>Registered as a Spring bean in {@code springMllp.xml}. Frank!Framework's
 * {@code default-autowire="byType"} context wires it into {@link MllpFacade}
 * subclasses automatically.</p>
 *
 * <p>Example {@code resources.yml} entries:</p>
 * <pre>
 * mllp:
 *   - name: "inbound-2575"
 *     url: "mllp://0.0.0.0:2575"
 *     properties:
 *       socketTimeout: "60000"
 *       backlog: "10"
 *
 *   - name: "outbound-ris"
 *     url: "mllp://ris.hospital.local:2575"
 *     properties:
 *       connectTimeout: "5000"
 *       socketTimeout: "30000"
 * </pre>
 */
public class MllpConnectionFactoryFactory extends ObjectFactory<MllpConnectionFactory, Object> {

    public MllpConnectionFactoryFactory() {
        super(null, "mllp", "MLLP");
    }

    @NonNull
    @Override
    protected MllpConnectionFactory augment(@NonNull Object object, @NonNull String objectName) {
        if (object instanceof FrankResource resource) {
            return new MllpConnectionFactory(objectName, resource);
        }
        throw new IllegalArgumentException("Resource [" + objectName + "] is not of type FrankResource");
    }

    @NonNull
    public MllpConnectionFactory getConnectionFactory(String name) {
        return get(name, null);
    }
}
