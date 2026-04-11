package com.viscosiety.components;

import java.util.List;

import org.frankframework.components.Module;

/**
 * Frank!Framework module descriptor for ViscoLink.
 *
 * <p>Registers {@code springMllp.xml} so that the {@code MllpConnectionFactoryFactory} bean
 * is available in the Spring application context and can be auto-wired into
 * {@link com.viscosiety.mllp.MllpFacade} subclasses.</p>
 */
public class ViscoLinkModule implements Module {

    @Override
    public List<String> getSpringConfigurationFiles() {
        return List.of("springMllp.xml");
    }
}
