package com.viscosiety.mllp;

import org.jspecify.annotations.NonNull;

import org.frankframework.jdbc.datasource.FrankResource;
import org.frankframework.jdbc.datasource.ObjectFactory;

/**
 * Spring-managed factory that resolves {@code mllp} resource entries from
 * {@code resources.yml} into {@link MllpConnectionFactory} instances.
 *
 * <p>Registered as a Spring bean in {@code springMllp.xml}. Frank!Framework's
 * {@code default-autowire="byType"} context wires it into {@link MllpFacade}
 * subclasses automatically.</p>
 *
 * <p>Example {@code resources.yml} entries:</p>
 * <pre>
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
 * </pre>
 */
public class MllpConnectionFactoryFactory extends ObjectFactory<MllpConnectionFactory, Object> {

    public MllpConnectionFactoryFactory() {
        super(null, "mllp", "MLLP");
    }

    @NonNull
    @Override
    protected MllpConnectionFactory augment(@NonNull Object object, @NonNull String objectName) {
        if (object instanceof FrankResource resource) {
            return new MllpConnectionFactory(objectName, resource);
        }
        throw new IllegalArgumentException("Resource [" + objectName + "] is not of type FrankResource");
    }

    @NonNull
    public MllpConnectionFactory getConnectionFactory(String name) {
        return get(name, null);
    }
}
