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

package com.viscosiety.viscorunner.it;

import java.time.Duration;

/**
 * How long the launcher ITs wait for a server they started in its own JVM to answer.
 *
 * <p>These are ceilings, not sleeps: Awaitility returns the moment the condition holds, so a healthy
 * local run is unaffected by a generous value. Their only job is to not go red when a shared CI
 * runner is slow.
 *
 * <p>They are deliberately far above the observed times. The previous values were 120s for ViscoStore
 * and 90s for ViscoLink, which looked ample against a ~20s local start but had almost no margin on a
 * loaded runner: pipeline 2012 (2026-11-22) failed with ViscoStore logging
 * {@code Started Application in 110.783 seconds} against the 120s ceiling — its Tomcat came up less
 * than a second after the gate expired — taking {@code Hl7v2ToFhirIT} and {@code LabEnrichmentIT}
 * down with it and skipping all 16 publish jobs. A timing ceiling that close to the real duration is
 * a coin flip, not a check, and a false red on a security release costs far more than the wait.
 *
 * <p>If a server genuinely hangs, these now take longer to report it. That is the intended trade:
 * the ITs log every poll attempt's status and body, so a hang is diagnosed from the log rather than
 * from how fast the build went red.
 */
final class ItTimeouts {

    /** ViscoStore (HAPI FHIR, 147 resource providers, terminology cache, Batch2 job definitions). */
    static final Duration VISCOSTORE_READY = Duration.ofMinutes(5);

    /** ViscoLink (the Frank!Framework WAR, configurations and adapters). */
    static final Duration VISCOLINK_READY = Duration.ofMinutes(3);

    private ItTimeouts() {
        // constants only
    }
}
