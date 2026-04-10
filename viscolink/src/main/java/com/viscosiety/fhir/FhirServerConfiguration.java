/*
   Copyright 2024 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0
*/
package com.viscosiety.fhir;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.frankframework.lifecycle.ServletManager;
import org.frankframework.lifecycle.servlets.ServletConfiguration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FhirServerConfiguration {

    private static final Logger log = LogManager.getLogger(FhirServerConfiguration.class);

    @Bean
    public ServletConfiguration fhirServletConfiguration() {
        ServletConfiguration cfg = new ServletConfiguration();
        cfg.setName(FhirServlet.SERVLET_NAME);
        cfg.setServlet(new FhirServlet());

        cfg.setUrlMapping("fhir/*");

        log.info("Created ServletConfiguration name={} mapping={}", FhirServlet.SERVLET_NAME, "blaat/*");
        return cfg;
    }

    @Bean
    public InitializingBean registerFhirServlet(ServletManager servletManager, ServletConfiguration fhirServletConfiguration) {
        return () -> {
            log.info("Registering servlet via ServletManager: {}", fhirServletConfiguration);
            servletManager.register(fhirServletConfiguration);
        };
    }

    public static class FhirServlet extends SimpleFhirServer {
        private static final long serialVersionUID = 1L;

        public static final String SERVLET_NAME = "FhirServlet";
        private static final Logger log = LogManager.getLogger(FhirServlet.class);

    }
}
