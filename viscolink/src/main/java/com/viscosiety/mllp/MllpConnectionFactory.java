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

import java.net.URI;
import java.nio.charset.Charset;
import java.util.Properties;

import org.frankframework.jdbc.datasource.FrankResource;

/**
 * Configuration holder for an MLLP resource entry from {@code resources.yml}.
 *
 * <p>Wraps a {@link FrankResource} and exposes typed MLLP connection parameters
 * parsed from the resource URL and optional properties.</p>
 *
 * <p>URL format:</p>
 * <ul>
 *   <li>Inbound listener: {@code mllp://0.0.0.0:2575} — bind on all interfaces</li>
 *   <li>Outbound sender:  {@code mllp://ris.hospital.local:2575} — connect to remote host</li>
 * </ul>
 *
 * <p>Additional properties (all optional):</p>
 * <pre>
 * properties:
 *   charset:       UTF-8   # wire encoding
 *   backlog:       10      # ServerSocket backlog (listener only)
 *   socketTimeout: 60000   # SO_TIMEOUT in ms
 *   connectTimeout: 5000   # TCP connect timeout in ms (sender only)
 * </pre>
 */
public class MllpConnectionFactory {

    private final String resourceName;
    private final String host;
    private final int    port;
    private final String charset;
    private final int    backlog;
    private final int    socketTimeout;
    private final int    connectTimeout;

    public MllpConnectionFactory(String resourceName, FrankResource resource) {
        this.resourceName = resourceName;

        URI uri;
        try {
            uri = new URI(resource.getUrl());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid MLLP resource URL for [" + resourceName + "]: " + resource.getUrl(), e);
        }

        String h = uri.getHost();
        this.host = (h == null || h.isBlank()) ? "0.0.0.0" : h;
        this.port = uri.getPort();
        if (this.port <= 0) {
            throw new IllegalArgumentException("MLLP resource [" + resourceName + "] URL must contain a port: " + resource.getUrl());
        }

        Properties props = resource.getProperties();
        this.charset       = props.getProperty("charset",       "UTF-8");
        this.backlog        = intProp(props, "backlog",        10);
        this.socketTimeout  = intProp(props, "socketTimeout",  60_000);
        this.connectTimeout = intProp(props, "connectTimeout",  5_000);

        // Validate charset early
        Charset.forName(this.charset);
    }

    private static int intProp(Properties props, String key, int defaultValue) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        return Integer.parseInt(v.trim());
    }

    public String getResourceName()   { return resourceName;   }
    public String getHost()           { return host;           }
    public int    getPort()           { return port;           }
    public String getCharset()        { return charset;        }
    public int    getBacklog()        { return backlog;        }
    public int    getSocketTimeout()  { return socketTimeout;  }
    public int    getConnectTimeout() { return connectTimeout; }

    public boolean isListenerResource() {
        return "0.0.0.0".equals(host) || "::".equals(host);
    }

    @Override
    public String toString() {
        return "MllpConnectionFactory[" + resourceName + " → " + host + ":" + port + "]";
    }
}
