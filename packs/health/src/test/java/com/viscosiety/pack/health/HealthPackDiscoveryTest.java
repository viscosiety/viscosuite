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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ServiceLoader;

import org.frankframework.components.Module;
import org.junit.jupiter.api.Test;

import com.viscosiety.pack.PackRegistry;

/** The pack registers itself through its two services files; nothing in the core names it. */
class HealthPackDiscoveryTest {

    @Test
    void theRegistryResolvesTheHealthPackFromThePacksServicesFile() {
        assertInstanceOf(HealthPack.class, PackRegistry.get());
        assertEquals("health", PackRegistry.get().id());
    }

    @Test
    void thePackIsAFrankFrameworkModuleThatBringsItsSpringFiles() throws Exception {
        List<Module> modules = ServiceLoader.load(Module.class).stream().map(ServiceLoader.Provider::get).toList();
        Module pack = modules.stream().filter(HealthPackModule.class::isInstance).findFirst().orElseThrow();
        assertEquals(List.of("springMllp.xml", "springFhir.xml"), pack.getSpringConfigurationFiles());
        assertEquals(PackVersion.get(), pack.getModuleInformation().getVersion());
    }

    @Test
    void thePackJarCarriesItsSpringFiles() {
        for (String file : List.of("springMllp.xml", "springFhir.xml")) {
            assertTrue(HealthPack.class.getResource("/" + file) != null, file + " must be on the pack's class path");
        }
    }
}
