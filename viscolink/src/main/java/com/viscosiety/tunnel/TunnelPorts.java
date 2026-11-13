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


package com.viscosiety.tunnel;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** The HTTPS tunnel ports this runtime opened at start (TunnelPortListener in the shared jar publishes them). */
public final class TunnelPorts {
    /** Same literal as com.viscosiety.viscorunner.tunnel.TunnelPortListener.PORTS_PROPERTY. */
    static final String PORTS_PROPERTY = "visco.tunnel.api.ports";

    private TunnelPorts() {
    }

    /** Read on every call: the property is set once at Tomcat start, before any request. */
    public static boolean isTunnelPort(int localPort) {
        return ports().contains(localPort);
    }

    static Set<Integer> ports() {
        String value = System.getProperty(PORTS_PROPERTY, "");
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> part.matches("\\d{1,5}"))
                .map(Integer::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}
