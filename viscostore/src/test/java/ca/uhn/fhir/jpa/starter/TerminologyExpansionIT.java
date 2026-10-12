package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import com.viscosiety.viscostore.operation.ExpandTextFilterGuard;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ValueSet/$expand over a CodeSystem stored in the store, with Hibernate Search off (the default).
 *
 * <p>HAPI expands from the database when no full-text search service is registered. The starter used
 * to register that service unconditionally while leaving Hibernate Search off, so HAPI took the
 * Hibernate Search path and answered 500 "HSEARCH800001: Hibernate Search was not initialized".
 * Without Hibernate Search HAPI ignores a text filter silently, so ExpandTextFilterGuard refuses it.
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {Application.class, NicknameServiceConfig.class},
    properties = {
        "spring.datasource.url=jdbc:h2:mem:dbterminologyexpansion",
        "hapi.fhir.fhir_version=R4",
        // The guard is one of our beans (production scans com.viscosiety.viscostore too)
        "hapi.fhir.custom-bean-packages=com.viscosiety.viscostore",
        "hapi.fhir.cr.enabled=false",
        "hapi.fhir.mdm_enabled=false",
        "hapi.fhir.enable_repository_validating_interceptor=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.main.allow-bean-definition-overriding=true",
    }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerminologyExpansionIT {

    private static final String SYSTEM = "https://example.org/CodeSystem/expansion-it";

    @LocalServerPort
    private int port;

    private IGenericClient client;

    @BeforeAll
    void storeTheCodeSystem() {
        FhirContext ctx = FhirContext.forR4();
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        ctx.getRestfulClientFactory().setSocketTimeout(60_000);
        client = ctx.newRestfulGenericClient("http://localhost:" + port + "/fhir/");

        CodeSystem cs = new CodeSystem();
        cs.setId("expansion-it");
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
    void aValueSetOfTheWholeCodeSystemExpands() {
        ValueSet vs = valueSet("whole");
        vs.getCompose().addInclude().setSystem(SYSTEM);

        assertEquals(List.of("apple", "bread", "fruit", "pear"), expand(vs));
    }

    @Test
    void aValueSetOfListedConceptsExpands() {
        ValueSet vs = valueSet("listed");
        vs.getCompose().addInclude().setSystem(SYSTEM).addConcept().setCode("bread");

        assertEquals(List.of("bread"), expand(vs));
    }

    /**
     * FHIR's is-a includes the concept itself; HAPI's database fallback
     * (TermReadSvcImpl.addConceptAndChildren, HAPI 8.12.1) adds only the descendants, so without
     * Hibernate Search is-a behaves like descendent-of. Pinned on purpose: when a HAPI release fixes
     * it, this fails and "fruit" belongs in the list. Workaround meanwhile: list the root concept too.
     */
    @Test
    void anIsAFilterExpandsTheDescendantsButNotTheConceptItself() {
        ValueSet vs = valueSet("is-a");
        vs.getCompose().addInclude().setSystem(SYSTEM).addFilter()
            .setProperty("concept")
            .setOp(ValueSet.FilterOperator.ISA)
            .setValue("fruit");

        assertEquals(List.of("apple", "pear"), expand(vs));
    }

    @Test
    void anUnsupportedFilterIsAClearError() {
        ValueSet vs = valueSet("descendent-of");
        vs.getCompose().addInclude().setSystem(SYSTEM).addFilter()
            .setProperty("concept")
            .setOp(ValueSet.FilterOperator.DESCENDENTOF)
            .setValue("fruit");

        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> expand(vs));
        assertTrue(e.getMessage().contains("Hibernate Search is disabled"), e.getMessage());
    }

    @Test
    void aTextFilterIsRefusedInsteadOfIgnoredOverGet() {
        String id = storeWholeValueSet("text-filter-get");

        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> client.operation()
            .onInstance(new IdType("ValueSet", id))
            .named("$expand")
            .withParameter(Parameters.class, "filter", new StringType("app"))
            .useHttpGet()
            .returnResourceType(ValueSet.class)
            .execute());
        assertTrue(e.getMessage().contains(ExpandTextFilterGuard.MESSAGE), e.getMessage());
    }

    @Test
    void aTextFilterIsRefusedInsteadOfIgnoredOverPost() {
        String id = storeWholeValueSet("text-filter-post");

        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> client.operation()
            .onInstance(new IdType("ValueSet", id))
            .named("$expand")
            .withParameter(Parameters.class, "filter", new StringType("app"))
            .returnResourceType(ValueSet.class)
            .execute());
        assertTrue(e.getMessage().contains(ExpandTextFilterGuard.MESSAGE), e.getMessage());
    }

    @Test
    void anEmptyTextFilterIsNotAFilter() {
        String id = storeWholeValueSet("empty-filter");

        ValueSet expanded = client.operation()
            .onInstance(new IdType("ValueSet", id))
            .named("$expand")
            .withParameter(Parameters.class, "filter", new StringType(""))
            .useHttpGet()
            .returnResourceType(ValueSet.class)
            .execute();
        assertEquals(4, expanded.getExpansion().getContains().size());
    }

    private String storeWholeValueSet(String id) {
        ValueSet vs = valueSet(id);
        vs.getCompose().addInclude().setSystem(SYSTEM);
        client.update().resource(vs).execute();
        return vs.getIdElement().getIdPart();
    }

    private static ValueSet valueSet(String id) {
        ValueSet vs = new ValueSet();
        vs.setId("expansion-it-" + id);
        vs.setUrl("https://example.org/ValueSet/expansion-it-" + id);
        vs.setStatus(Enumerations.PublicationStatus.ACTIVE);
        return vs;
    }

    private List<String> expand(ValueSet vs) {
        client.update().resource(vs).execute();
        ValueSet expanded = client.operation()
            .onInstance(new IdType("ValueSet", vs.getIdElement().getIdPart()))
            .named("$expand")
            .withNoParameters(Parameters.class)
            .returnResourceType(ValueSet.class)
            .execute();
        return expanded.getExpansion().getContains().stream()
            .map(ValueSet.ValueSetExpansionContainsComponent::getCode)
            .sorted()
            .toList();
    }
}
