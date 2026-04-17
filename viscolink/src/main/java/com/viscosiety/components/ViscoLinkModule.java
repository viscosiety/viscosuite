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
        return List.of("springMllp.xml");
    }
}
