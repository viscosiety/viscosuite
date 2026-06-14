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

import java.util.UUID;

import org.frankframework.configuration.Configuration;
import org.frankframework.configuration.IbisManager;
import org.frankframework.core.Adapter;
import org.frankframework.core.PipeLineResult;
import org.frankframework.core.PipeLineSession;
import org.frankframework.stream.Message;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Generic, product-neutral "run an adapter with all senders stubbed" capability.
 *
 * <p>Runs in-process: resolves the {@link Adapter} from the {@link IbisManager} and calls
 * {@link Adapter#processMessageDirect} directly (the same call the F!F test-pipeline makes),
 * wrapped in {@link StubbingDebugger}'s stub-all window keyed by the run's correlation id.
 * All transform pipes execute; every sender is stubbed to a no-op, so there are no outbound
 * side effects. The run is captured as a normal Ladybug report under the returned
 * correlation id.</p>
 *
 * <p>Registered as a Spring bean in the F!F {@code IbisApplicationContext} (springStubbedRun.xml)
 * and exposed via a static instance so the {@code /flow-api/stubbed-run} servlet can delegate
 * without a network round-trip.</p>
 */
public class StubbedRunner implements ApplicationContextAware, InitializingBean {

    /** Session key marking a report as a PHI-free synthetic copy, filtered by the Ladybug "Shareable" view. */
    public static final String SHARABLE_KEY = "sharable";
    /** Session key carrying the source report id this run was derived from (shown in the Shareable view). */
    public static final String ORIGIN_ID_KEY = "originId";
    /** Correlation-id prefix used when the caller does not supply one. */
    public static final String DEFAULT_CID_PREFIX = "stubbed-run-";

    private static volatile StubbedRunner instance;

    private ApplicationContext applicationContext;

    public static StubbedRunner getInstance() {
        return instance;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        instance = this;
    }

    /** Outcome of a stubbed run: the correlation id the report was captured under, and the pipeline exit state. */
    public record Result(String correlationId, String state) {}

    /**
     * Run {@code adapterName} (optionally scoped to {@code configurationName}) on {@code input}
     * with all senders stubbed. The correlation id is {@code <cidPrefix><uuid>}; pass {@code null}
     * or blank for {@link #DEFAULT_CID_PREFIX}. Returns the correlation id (for fetching the captured
     * report) and the pipeline exit state.
     */
    public Result runStubbed(String configurationName, String adapterName, byte[] input, String originId, String cidPrefix) {
        Adapter adapter = resolveAdapter(configurationName, adapterName);
        if (adapter == null) {
            throw new IllegalArgumentException("adapter [" + adapterName + "] not found"
                    + (configurationName == null || configurationName.isBlank() ? "" : " in configuration [" + configurationName + "]"));
        }
        String correlationId = normalizePrefix(cidPrefix) + UUID.randomUUID();
        StubbingDebugger.enableStubAllSenders(correlationId);
        try (PipeLineSession session = new PipeLineSession()) {
            session.put(PipeLineSession.CORRELATION_ID_KEY, correlationId);
            // Mark this synthetic, side-effect-free run as a shareable copy and (optionally) record the source
            // report it was derived from. Both are captured as session keys on the produced report; the Ladybug
            // "Shareable" view filters on `sharable` and shows `originId` (see springIbisTestToolVisco.xml).
            session.put(SHARABLE_KEY, "true");
            if (originId != null && !originId.isBlank()) {
                session.put(ORIGIN_ID_KEY, originId);
            }
            PipeLineResult plr = adapter.processMessageDirect(correlationId, new Message(input), session);
            return new Result(correlationId, plr.getState() != null ? plr.getState().name() : "");
        } finally {
            StubbingDebugger.disableStubAllSenders(correlationId);
        }
    }

    /**
     * Sanitize a caller-supplied correlation-id prefix: keep only safe id characters, fall back to
     * {@link #DEFAULT_CID_PREFIX} when blank, and guarantee a trailing {@code '-'} separator before
     * the UUID so the prefix stays recognisable.
     */
    static String normalizePrefix(String cidPrefix) {
        if (cidPrefix == null) {
            return DEFAULT_CID_PREFIX;
        }
        String cleaned = cidPrefix.trim().replaceAll("[^a-zA-Z0-9._-]", "");
        if (cleaned.isEmpty()) {
            return DEFAULT_CID_PREFIX;
        }
        return cleaned.endsWith("-") ? cleaned : cleaned + "-";
    }

    private Adapter resolveAdapter(String configurationName, String adapterName) {
        IbisManager ibisManager = getIbisManager();
        for (Configuration configuration : ibisManager.getConfigurations()) {
            if (configurationName != null && !configurationName.isBlank()
                    && !configurationName.equals(configuration.getName())) {
                continue;
            }
            Adapter adapter = configuration.getRegisteredAdapter(adapterName);
            if (adapter != null) {
                return adapter;
            }
        }
        return null;
    }

    private IbisManager getIbisManager() {
        ApplicationContext ctx = applicationContext;
        while (ctx != null) {
            try {
                return ctx.getBean(IbisManager.class);
            } catch (org.springframework.beans.BeansException ignore) {
                ctx = ctx.getParent();
            }
        }
        throw new IllegalStateException("IbisManager bean not found in the context hierarchy");
    }
}
