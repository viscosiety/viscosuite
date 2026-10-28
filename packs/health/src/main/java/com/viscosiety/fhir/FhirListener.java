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

package com.viscosiety.fhir;

import org.frankframework.configuration.ConfigurationWarning;

/**
 * Old name of {@link com.viscosiety.pack.health.fhir.FhirListener}, kept so configurations written before the health pack moved to its own
 * namespace keep loading. Frank!Framework reports every use as a configuration warning; write
 * {@code className="com.viscosiety.pack.health.fhir.FhirListener"} instead. Removed in a later release.
 */
@Deprecated(since = "0.21.0", forRemoval = true)
@ConfigurationWarning("Use className=\"com.viscosiety.pack.health.fhir.FhirListener\"")
public class FhirListener extends com.viscosiety.pack.health.fhir.FhirListener {
}
