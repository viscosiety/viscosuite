package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.dao.IFulltextSearchSvc;
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import com.viscosiety.viscostore.config.ExpandTextFilterGuardConfig;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The other side of the switch: with Hibernate Search on, the full-text search service is registered,
 * the text-filter guard is not, and HAPI handles $expand?filter and is-a itself.
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {Application.class, NicknameServiceConfig.class},
    properties = {
        "spring.datasource.url=jdbc:h2:mem:dbterminologyexpansionhsearch",
        "hapi.fhir.fhir_version=R4",
        "hapi.fhir.custom-bean-packages=com.viscosiety.viscostore",
        "hapi.fhir.cr.enabled=false",
        "hapi.fhir.mdm_enabled=false",
        "hapi.fhir.enable_repository_validating_interceptor=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.properties.hibernate.search.enabled=true",
        "spring.jpa.properties.hibernate.search.backend.type=lucene",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
        "spring.jpa.properties.hibernate.search.backend.analysis.configurer="
            + "ca.uhn.fhir.jpa.search.HapiHSearchAnalysisConfigurers$HapiLuceneAnalysisConfigurer",
    }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerminologyExpansionWithHibernateSearchIT {

    private static final String SYSTEM = "https://example.org/CodeSystem/expansion-hsearch-it";

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    private IGenericClient client;

    @BeforeAll
    void storeTheCodeSystem() {
        FhirContext ctx = FhirContext.forR4();
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        ctx.getRestfulClientFactory().setSocketTimeout(60_000);
        client = ctx.newRestfulGenericClient("http://localhost:" + port + "/fhir/");

        CodeSystem cs = new CodeSystem();
        cs.setId("expansion-hsearch-it");
        cs.setUrl(SYSTEM);
        cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
        cs.setContent(CodeSystem.CodeSystemContentMode.COMPLETE);
        CodeSystem.ConceptDefinitionComponent fruit = cs.addConcept().setCode("fruit").setDisplay("Fruit");
        fruit.addConcept().setCode("apple").setDisplay("Apple");
        fruit.addConcept().setCode("pear").setDisplay("Pear");
        cs.addConcept().setCode("bread").setDisplay("Bread");
        client.update().resource(cs).execute();
    }

    @Test
    void theFullTextServiceIsThereAndTheGuardIsNot() {
        assertEquals(1, context.getBeanNamesForType(IFulltextSearchSvc.class).length);
        assertEquals(0, context.getBeanNamesForType(ExpandTextFilterGuardConfig.class).length);
    }

    @Test
    void aTextFilterFilters() {
        ValueSet vs = new ValueSet();
        vs.setId("expansion-hsearch-it-whole");
        vs.setUrl("https://example.org/ValueSet/expansion-hsearch-it-whole");
        vs.setStatus(Enumerations.PublicationStatus.ACTIVE);
        vs.getCompose().addInclude().setSystem(SYSTEM);
        client.update().resource(vs).execute();

        ValueSet expanded = client.operation()
            .onInstance(new IdType("ValueSet", "expansion-hsearch-it-whole"))
            .named("$expand")
            .withParameter(Parameters.class, "filter", new StringType("app"))
            .useHttpGet()
            .returnResourceType(ValueSet.class)
            .execute();

        List<String> codes = expanded.getExpansion().getContains().stream()
            .map(ValueSet.ValueSetExpansionContainsComponent::getCode)
            .toList();
        assertTrue(codes.contains("apple") && !codes.contains("bread"), "filter=app gave " + codes);
    }
}
