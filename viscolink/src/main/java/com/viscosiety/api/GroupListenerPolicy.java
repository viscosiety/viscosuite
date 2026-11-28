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

package com.viscosiety.api;

import java.util.Set;
import com.viscosiety.tunnel.TunnelOnlyApiFilter;
import org.frankframework.http.rest.ApiDispatchConfig;
import org.frankframework.http.rest.ApiListener;
import org.frankframework.http.rest.ApiServiceDispatcher;

/** Verifies literal listener ownership and tunnel visibility before grouped dispatch. */
final class GroupListenerPolicy {
    private GroupListenerPolicy() {}

    enum Action { PASS, NOT_FOUND, OPENAPI_NOT_FOUND, PATTERN_OPENAPI }

    /** The action plus, for PASS, the tunnel-only methods to keep out of the headers and, for PATTERN_OPENAPI, the pattern. */
    record Check(Action action, Set<String> hiddenMethods, ApiDispatchConfig config) {
        static Check of(Action action) {
            return new Check(action, Set.of(), null);
        }
    }

    /**
     * Check the pattern the shared dispatcher would select. A public group may serve only listeners
     * literally declared under its name, even when a wildcard listener also matches the URL.
     * Tunnel-only methods stay hidden from callers on the public port.
     */
    static Check check(String group, String method, String fullPath) {
        ApiListener.HttpMethod httpMethod = TunnelOnlyApiFilter.parseMethod(method);
        String uri = TunnelOnlyApiFilter.cleanUri(fullPath);
        if (httpMethod == null || uri == null) {
            return Check.of(Action.PASS);
        }
        ApiServiceDispatcher dispatcher = ApiServiceDispatcher.getInstance();
        if (uri.endsWith("openapi.json")) {
            ApiDispatchConfig config = dispatcher.findExactMatchingConfigForUri(uri.substring(0, uri.lastIndexOf('/')));
            if (config == null || !isMine(group, config) || TunnelOnlyApiFilter.hasTunnelListener(config)) {
                return Check.of(Action.OPENAPI_NOT_FOUND);
            }
            return new Check(Action.PATTERN_OPENAPI, Set.of(), config);
        }
        ApiDispatchConfig config = dispatcher.findConfigForRequest(httpMethod, uri);
        if (config == null) {
            return Check.of(Action.PASS);
        }
        if (!isMine(group, config)
                || TunnelOnlyApiFilter.decideForConfig(config, httpMethod) == TunnelOnlyApiFilter.Decision.NOT_FOUND) {
            return Check.of(Action.NOT_FOUND);
        }
        return new Check(Action.PASS, TunnelOnlyApiFilter.tunnelMethodsOf(config), null);
    }

    private static boolean isMine(String group, ApiDispatchConfig config) {
        return group.equals(ApiGroupOpenApi.firstSegment(config.getUriPattern()));
    }

}
