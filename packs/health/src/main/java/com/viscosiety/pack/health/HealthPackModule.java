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

package com.viscosiety.pack.health;

import java.io.IOException;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.jspecify.annotations.NonNull;

import org.frankframework.components.Module;
import org.frankframework.components.ModuleInformation;

/**
 * The healthcare pack as a Frank!Framework module: brings {@code springMllp.xml} (the MLLP
 * connection-factory factory, auto-wired into {@code MllpFacade} subclasses) and
 * {@code springFhir.xml} (the FHIR bridge and servlet registrar). Discovered through
 * {@code META-INF/services/org.frankframework.components.Module} like {@code ViscoLinkModule}.
 */
public class HealthPackModule implements Module {

    @Override
    @NonNull
    public ModuleInformation getModuleInformation() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Implementation-Title", "ViscoLink healthcare pack");
        attrs.putValue("Implementation-Version", PackVersion.get());
        attrs.putValue("Implementation-Vendor", "Viscosiety");
        attrs.putValue("groupId", "com.visco");
        attrs.putValue("artifactId", "viscolink-pack-health");
        return new ModuleInformation(manifest);
    }

    @Override
    public List<String> getSpringConfigurationFiles() {
        return List.of("springMllp.xml", "springFhir.xml");
    }
}
