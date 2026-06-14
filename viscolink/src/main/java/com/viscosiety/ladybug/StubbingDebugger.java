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

package com.viscosiety.ladybug;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.frankframework.core.IListener;
import org.frankframework.core.ISender;
import org.frankframework.ladybug.LadybugDebugger;

/**
 * Generic, product-neutral extension of the Ladybug debugger that lets a single
 * pipeline run be executed with every sender stubbed (no-op), regardless of the
 * rerun/stub machinery.
 *
 * <p>A run is opted in by its correlation id: callers register the correlation id
 * with which they will drive the adapter (e.g. via the test-pipeline endpoint),
 * and for the duration of that run {@link #stubSender} returns {@code true} so
 * {@code IbisDebuggerAdvice} substitutes an empty result instead of calling the
 * sender. All transform pipes still execute and the run is captured as a normal
 * Ladybug report — but with zero outbound side effects.</p>
 *
 * <p>The correlation-id set mirrors {@code LadybugDebugger}'s own {@code inRerun}
 * design, so it is robust across the management-bus thread hop (a {@code ThreadLocal}
 * would not survive that). Registration is JVM-wide via static methods; the bean
 * instance wired into the debugger advice reads the same set.</p>
 *
 * <p>This carries no product-specific logic — it is a general "run with stubbed
 * senders" capability.</p>
 */
public class StubbingDebugger extends LadybugDebugger {

    private static final Set<String> STUB_ALL_SENDERS = ConcurrentHashMap.newKeySet();

    /** Opt a run (identified by its correlation id) into stub-all-senders mode. */
    public static void enableStubAllSenders(String correlationId) {
        if (correlationId != null) {
            STUB_ALL_SENDERS.add(correlationId);
        }
    }

    /** End stub-all-senders mode for the given correlation id. Always call in a finally block. */
    public static void disableStubAllSenders(String correlationId) {
        if (correlationId != null) {
            STUB_ALL_SENDERS.remove(correlationId);
        }
    }

    private static boolean isStubAll(String correlationId) {
        return correlationId != null && STUB_ALL_SENDERS.contains(correlationId);
    }

    @Override
    public boolean stubSender(ISender sender, String correlationId) {
        return isStubAll(correlationId) || super.stubSender(sender, correlationId);
    }

    @Override
    public boolean stubReplyListener(IListener<?> listener, String correlationId) {
        return isStubAll(correlationId) || super.stubReplyListener(listener, correlationId);
    }
}
