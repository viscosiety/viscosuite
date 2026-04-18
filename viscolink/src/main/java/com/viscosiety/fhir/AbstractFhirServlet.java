package com.viscosiety.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor;
import jakarta.servlet.ServletException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Abstract base for viscolink's HAPI FHIR plain servers.
 *
 * <p>Subclasses provide the servlet name, URL mapping, FHIR version context, and resource
 * providers. Actual Tomcat registration is handled by {@link FhirServletRegistrar}.</p>
 */
public abstract class AbstractFhirServlet extends RestfulServer {

    private static final long serialVersionUID = 1L;
    protected final Logger log = LogManager.getLogger(getClass());

    public abstract String getName();
    public abstract String getUrlMapping();

    protected abstract FhirContext createFhirContext();
    protected abstract List<IResourceProvider> createProviders();

    @Override
    protected void initialize() throws ServletException {
        super.initialize();
        setFhirContext(createFhirContext());
        setResourceProviders(createProviders());
        registerInterceptor(new ResponseHighlighterInterceptor());
        log.info("{} initialised — FHIR {}", getClass().getSimpleName(),
                getFhirContext().getVersion().getVersion());
    }
}
