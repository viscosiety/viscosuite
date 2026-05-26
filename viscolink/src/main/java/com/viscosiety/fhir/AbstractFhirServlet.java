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
