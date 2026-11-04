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

package com.viscosiety.components;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.jspecify.annotations.NonNull;

import org.frankframework.components.Module;
import org.frankframework.components.ModuleInformation;

/**
 * Frank!Framework module descriptor for ViscoLink: registers the core's own Spring files.
 */
public class ViscoLinkModule implements Module {

    /**
     * The module's {@code Implementation-Version}: the build's version, read once from the
     * Maven-filtered {@code META-INF/viscolink-version.properties}, so the manifest below and
     * {@link com.viscosiety.pack.CorePack#version()} (the only other reader) follow every
     * {@code versions:set} in CI instead of a constant that drifted. "unknown" only when the
     * resource is missing, which a packaged WAR never is.
     */
    public static final String IMPLEMENTATION_VERSION = readVersion();

    private static String readVersion() {
        Properties props = new Properties();
        try (InputStream in = ViscoLinkModule.class.getResourceAsStream("/META-INF/viscolink-version.properties")) {
            if (in == null) {
                return "unknown";
            }
            props.load(in);
        } catch (IOException e) {
            return "unknown";
        }
        return props.getProperty("version", "unknown");
    }

    @Override
    @NonNull
    public ModuleInformation getModuleInformation() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Implementation-Title", "ViscoLink");
        attrs.putValue("Implementation-Version", IMPLEMENTATION_VERSION);
        attrs.putValue("Implementation-Vendor", "Viscosiety");
        attrs.putValue("groupId", "com.viscosiety");
        attrs.putValue("artifactId", "viscolink");
        return new ModuleInformation(manifest);
    }

    @Override
    public List<String> getSpringConfigurationFiles() {
        // Kubernetes lifecycle events: WARN/ERROR come from the Frank!Framework's own
        // KubernetesEventPublisher (frankframework-kubernetes, @IbisInitializer); the INFO
        // lifecycle (started / closed / failed to start) from springLifecycleEvents.xml below.
        return List.of("springStubbedRun.xml", "springConsoleSecurity.xml", "springPoolWaitLimit.xml",
                "springLifecycleEvents.xml");
    }
}
