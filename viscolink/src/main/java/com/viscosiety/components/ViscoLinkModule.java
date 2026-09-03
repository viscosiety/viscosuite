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
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.jspecify.annotations.NonNull;

import org.frankframework.components.Module;
import org.frankframework.components.ModuleInformation;

/**
 * Frank!Framework module descriptor for ViscoLink.
 *
 * <p>Registers {@code springMllp.xml} so that the {@code MllpConnectionFactoryFactory} bean
 * is available in the Spring application context and can be auto-wired into
 * {@link com.viscosiety.mllp.MllpFacade} subclasses.</p>
 */
public class ViscoLinkModule implements Module {

    @Override
    @NonNull
    public ModuleInformation getModuleInformation() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Implementation-Title", "ViscoLink");
        attrs.putValue("Implementation-Version", "1.0.0-SNAPSHOT");
        attrs.putValue("Implementation-Vendor", "Viscosiety");
        attrs.putValue("groupId", "com.viscosiety");
        attrs.putValue("artifactId", "viscolink");
        return new ModuleInformation(manifest);
    }

    @Override
    public List<String> getSpringConfigurationFiles() {
        // Kubernetes lifecycle events come from the Frank!Framework's own
        // KubernetesEventPublisher (frankframework-kubernetes, @IbisInitializer) —
        // the viscolink implementation was upstreamed and then removed here.
        return List.of("springMllp.xml", "springFhir.xml", "springStubbedRun.xml",
                "springConsoleSecurity.xml");
    }
}
