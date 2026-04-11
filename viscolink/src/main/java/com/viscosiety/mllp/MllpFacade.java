package com.viscosiety.mllp;

import org.springframework.context.ApplicationContext;

import org.frankframework.configuration.ConfigurationException;
import org.frankframework.core.FrankElement;
import org.frankframework.core.HasPhysicalDestination;
import org.frankframework.core.IConfigurable;
import org.frankframework.core.NameAware;
import org.frankframework.doc.Mandatory;

/**
 * Base class for MLLP listener and sender.
 *
 * <p>Requires an MLLP resource to be configured in {@code resources.yml}. Example:</p>
 * <pre>{@code
 * mllp:
 *   - name: "inbound-2575"
 *     url: "mllp://0.0.0.0:2575"
 *     properties:
 *       socketTimeout: "60000"
 *       backlog: "10"
 *
 *   - name: "outbound-ris"
 *     url: "mllp://ris.hospital.local:2575"
 *     properties:
 *       connectTimeout: "5000"
 *       socketTimeout: "30000"
 * }</pre>
 *
 * <p>Use {@link MllpListener} in a {@code <Receiver>} and {@link MllpSender} in a
 * {@code <SenderPipe>}. HL7v2 ↔ XML conversion is handled separately by
 * {@code Hl7v2ToXmlPipe} and {@code XmlToHl7v2Pipe} in the pipeline.</p>
 */
public abstract class MllpFacade implements HasPhysicalDestination, IConfigurable, NameAware, FrankElement {

    /** Spring will wire this via byType auto-wiring. */
    private MllpConnectionFactoryFactory mllpConnectionFactoryFactory;

    private ApplicationContext applicationContext;
    private String name;
    private String resourceName;

    private MllpConnectionFactory connectionFactory;

    // ---- ApplicationContextAware (from FrankElement) ----

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    // ---- NameAware ----

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    // ---- IConfigurable ----

    @Override
    public void configure() throws ConfigurationException {
        if (resourceName == null || resourceName.isBlank()) {
            throw new ConfigurationException("resourceName is required");
        }
    }

    // ---- HasPhysicalDestination ----

    @Override
    public String getPhysicalDestinationName() {
        try {
            MllpConnectionFactory f = getConnectionFactory();
            return f.getHost() + ":" + f.getPort();
        } catch (Exception e) {
            return "mllp/" + resourceName + " (unavailable)";
        }
    }

    // ---- Spring wiring ----

    /** Spring byType-wires the factory registered in springMllp.xml. */
    public void setMllpConnectionFactoryFactory(MllpConnectionFactoryFactory factory) {
        this.mllpConnectionFactoryFactory = factory;
    }

    public MllpConnectionFactoryFactory getMllpConnectionFactoryFactory() {
        return mllpConnectionFactoryFactory;
    }

    /** Name of the MLLP resource entry in {@code resources.yml}. */
    @Mandatory
    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getResourceName() {
        return resourceName;
    }

    protected MllpConnectionFactory getConnectionFactory() {
        if (connectionFactory == null) {
            connectionFactory = mllpConnectionFactoryFactory.getConnectionFactory(resourceName);
        }
        return connectionFactory;
    }

    protected String getLogPrefix() {
        return "[" + getName() + "] ";
    }
}
