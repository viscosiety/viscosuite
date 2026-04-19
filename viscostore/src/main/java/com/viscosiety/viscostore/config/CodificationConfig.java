package com.viscosiety.viscostore.config;

import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.starter.annotations.OnR4Condition;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.viscosiety.viscostore.interceptor.CodificationInterceptor;
import com.viscosiety.viscostore.operation.ConvertOperationProvider;
import com.viscosiety.viscostore.operation.FmlCompileFilter;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the ViscoSuite codification interceptor and $convert operation provider into HAPI.
 *
 * Interceptor registration:
 *   Registered on {@link IInterceptorService} (not {@link RestfulServer}) so codification
 *   fires for ALL DAO-level writes — batch imports, programmatic creates, Frank!Framework
 *   ingest — not only HTTP REST requests.
 *
 * Provider registration:
 *   {@link ConvertOperationProvider} is registered directly on {@link RestfulServer} here
 *   rather than via hapi.fhir.custom-provider-classes in application.yaml. This keeps the
 *   registration scoped to contexts that scan com.viscosiety.viscostore (production), so
 *   tests that override custom-bean-packages to their own packages are unaffected.
 *
 * Both beans are discovered by Spring because com.viscosiety.viscostore is listed under
 * hapi.fhir.custom-bean-packages in application.yaml.
 */
@Configuration
@Conditional(OnR4Condition.class)
public class CodificationConfig {

    @Autowired
    private IInterceptorService myInterceptorService;

    @Autowired
    private RestfulServer myRestfulServer;

    @Autowired
    private CodificationInterceptor myCodificationInterceptor;

    @Autowired
    private ConvertOperationProvider myConvertOperationProvider;

    @Autowired
    private FmlCompileFilter myFmlCompileFilter;

    @PostConstruct
    public void register() {
        myInterceptorService.registerInterceptor(myCodificationInterceptor);
        myRestfulServer.registerProvider(myConvertOperationProvider);
    }

    /**
     * Registers the FML compile filter at exactly POST /fhir/StructureMap/$compile.
     *
     * The filter intercepts before HAPI's RestfulServer sees the request, so HAPI
     * never attempts to parse the raw FML body as a FHIR resource.
     *
     * The URL pattern is relative to the WAR context root (/viscostore), matching
     * the HAPI FHIR servlet path /fhir/*. The HIGHEST_PRECEDENCE order ensures the
     * filter runs before any other filters in the chain.
     */
    @Bean
    public FilterRegistrationBean<FmlCompileFilter> fmlCompileFilterRegistration() {
        FilterRegistrationBean<FmlCompileFilter> registration = new FilterRegistrationBean<>(myFmlCompileFilter);
        registration.addUrlPatterns("/fhir/StructureMap/$compile");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("fmlCompileFilter");
        return registration;
    }
}
