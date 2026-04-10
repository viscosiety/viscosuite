/*
   Copyright 2024 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package com.viscosiety.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.IdType;

import jakarta.servlet.ServletException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simple FHIR Server using HAPI FHIR.
 * This server provides basic FHIR R4 capabilities.
 */
public class SimpleFhirServer extends ca.uhn.fhir.rest.server.RestfulServer {

    private static final long serialVersionUID = 1L;
    private final Logger log = LogManager.getLogger(SimpleFhirServer.class);

    @Override
    protected void initialize() throws ServletException {
        super.initialize();
        log.info("SimpleFhirServer initialize...");

        // Set the FHIR version to R4
        setFhirContext(FhirContext.forR4());

        // Register resource providers
        List<IResourceProvider> providers = new ArrayList<>();
        providers.add(new SimplePatientProvider());
        setResourceProviders(providers);

        // Add response highlighter for browser testing
        registerInterceptor(new ResponseHighlighterInterceptor());
    }

    /**
     * Simple Patient resource provider for demonstration purposes.
     */
    public static class SimplePatientProvider implements IResourceProvider {

        @Override
        public Class<? extends org.hl7.fhir.r4.model.Patient> getResourceType() {
            return Patient.class;
        }

        /**
         * Handle GET requests for Patient resources.
         * This is a simple implementation that returns a mock patient.
         */
        @Read
        public Patient read(@IdParam IdType theId, RequestDetails theRequestDetails) {
            Patient patient = new Patient();
            patient.setId(theId);
            patient.addName()
                .setFamily("Doe")
                .addGiven("John");
            return patient;
        }
    }
}
