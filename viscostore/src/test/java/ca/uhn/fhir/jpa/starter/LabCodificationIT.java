package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the FML-based lab codification pipeline.
 *
 * Verifies the end-to-end flow across the ViscoStore codification stack:
 *   1. FmlCompileFilter   — compiles the FML StructureMap via POST /$compile
 *   2. CodificationInterceptor — detects the inbound-zone tag and ext-fml-map-ref
 *                                on every persisted resource
 *   3. CodificationService    — runs StructureMapUtilities.transform() after commit
 *                               and persists the resulting codified resource
 *
 * A Patient is created first so the Observation subject reference resolves cleanly.
 * The inbound Observation carries NullFlavor/OTH + code.text "Calcium"; the FML map
 * replaces that with LOINC 17861-6.
 *
 * The FML map and SearchParameter are loaded once for the whole test class via
 * {@code @BeforeAll}. {@code @TestInstance(PER_CLASS)} allows non-static {@code @BeforeAll}
 * so that {@code @LocalServerPort} is already injected when the setup method runs.
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {Application.class, NicknameServiceConfig.class},
    properties = {
        // Isolated H2 database — avoids cross-test interference
        "spring.datasource.url=jdbc:h2:mem:dbcodification",
        // Ensure R4 mode regardless of application.yaml (satisfies OnR4Condition)
        "hapi.fhir.fhir_version=R4",
        // Ensure the codification beans are scanned
        "hapi.fhir.custom-bean-packages=com.viscosiety.viscostore",
        // Disable unused features to keep the context light
        "hapi.fhir.cr.enabled=false",
        "hapi.fhir.mdm_enabled=false",
        "hapi.fhir.enable_repository_validating_interceptor=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
    }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabCodificationIT {

    // -------------------------------------------------------------------------
    // ViscoSuite constants
    // -------------------------------------------------------------------------

    private static final String STRUCTURE_MAP_CANONICAL =
        "https://ig.viscosiety.com/StructureMap/InboundLabObservation-to-Observation";

    private static final String EXT_FML_MAP_REF =
        "https://ig.viscosiety.com/StructureDefinition/ext-fml-map-ref";

    private static final String EXT_INBOUND_SOURCE_REF =
        "https://ig.viscosiety.com/StructureDefinition/ext-inbound-source-ref";

    private static final String EXT_DATA_PROVENANCE =
        "https://ig.viscosiety.com/StructureDefinition/ext-data-provenance";

    private static final String DATA_ZONE_SYSTEM =
        "https://ig.viscosiety.com/CodeSystem/data-zone";

    private static final String LOINC_SYSTEM = "http://loinc.org";
    private static final String NULL_FLAVOR_SYSTEM =
        "http://terminology.hl7.org/CodeSystem/v3-NullFlavor";

    // -------------------------------------------------------------------------
    // FML source — mirrors util/demo/fixtures/codification/InboundLabObservation-to-Observation.map
    // -------------------------------------------------------------------------

    private static final String FML_MAP = """
        map "https://ig.viscosiety.com/StructureMap/InboundLabObservation-to-Observation" = "InboundLabObservationToObservation"

        uses "http://hl7.org/fhir/StructureDefinition/Observation" alias InboundObs as source
        uses "http://hl7.org/fhir/StructureDefinition/Observation" alias Observation as target

        group MapLabObservation(source src : InboundObs, target tgt : Observation) {
          src.status          as v  -> tgt.status         = v   "status";
          src.category        as v  -> tgt.category        = v   "category";
          src.subject         as v  -> tgt.subject         = v   "subject";
          src.effective       as v  -> tgt.effective       = v   "effective";
          src.value           as v  -> tgt.value           = v   "value";
          src.specimen        as v  -> tgt.specimen        = v   "specimen";
          src.referenceRange  as v  -> tgt.referenceRange  = v   "referenceRange";
          src.interpretation  as v  -> tgt.interpretation  = v   "interpretation";
          src.code            as sc -> tgt.code            as tc then MapCode(sc, tc) "code";
        }

        group MapCode(source sc, target tc) {
          sc -> tc.coding as tcoding then MapLoinc(sc, tcoding) "make-coding";
          sc.text as t -> tc.text = t "code-text";
        }

        group MapLoinc(source sc, target tcoding) {
          sc -> tcoding.system  = 'http://loinc.org'                          "loinc-system";
          sc -> tcoding.code    = '17861-6'                                    "loinc-code";
          sc -> tcoding.display = 'Calcium [Moles/volume] in Serum or Plasma'  "loinc-display";
        }
        """;

    // -------------------------------------------------------------------------
    // Spring / HAPI wiring
    // -------------------------------------------------------------------------

    @LocalServerPort
    private int port;

    private IGenericClient client;
    private String baseUrl;

    // -------------------------------------------------------------------------
    // One-time setup: compile StructureMap + register SearchParameter
    // -------------------------------------------------------------------------

    @BeforeAll
    void setUpOnce() throws Exception {
        FhirContext ctx = FhirContext.forR4();
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        ctx.getRestfulClientFactory().setSocketTimeout(60_000);
        baseUrl = "http://localhost:" + port + "/fhir/";
        client = ctx.newRestfulGenericClient(baseUrl);

        compileStructureMap();
        registerSearchParameter();
    }

    /**
     * Compiles the FML StructureMap via the custom FmlCompileFilter endpoint.
     * Returns 201 on first call (create) or 200 on subsequent calls (update).
     */
    private void compileStructureMap() throws Exception {
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "StructureMap/$compile"))
            .header("Content-Type", "text/fhir-mapping")
            .header("Accept", "application/fhir+xml")
            .POST(HttpRequest.BodyPublishers.ofString(FML_MAP))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201,
            "FML compile failed with HTTP " + resp.statusCode() + ": " + resp.body());
    }

    /**
     * Registers the inbound-source-ref SearchParameter that CodificationService
     * uses for the conditional-create idempotency check (ifNoneExist).
     *
     * Must be registered before any codified Observation is created so that HAPI
     * indexes the codified resource against this parameter at write time.
     */
    private void registerSearchParameter() {
        SearchParameter sp = new SearchParameter();
        sp.setId("observation-inbound-source-ref");
        sp.setUrl("https://ig.viscosiety.com/SearchParameter/Observation-inbound-source-ref");
        sp.setName("ObservationInboundSourceRef");
        sp.setStatus(Enumerations.PublicationStatus.ACTIVE);
        sp.setDescription("Searches codified Observations by their inbound-zone source reference.");
        sp.setCode("inbound-source-ref");
        sp.addBase("Observation");
        sp.setType(Enumerations.SearchParamType.REFERENCE);
        sp.setExpression(
            "Observation.extension.where(url='" + EXT_INBOUND_SOURCE_REF + "').value.ofType(Reference)");
        sp.addTarget("Observation");

        client.update().resource(sp).execute();
    }

    // -------------------------------------------------------------------------
    // Test
    // -------------------------------------------------------------------------

    /**
     * Posts an inbound-zone Observation carrying NullFlavor/OTH + code.text "Calcium"
     * and verifies that CodificationService produces a codified-zone Observation with:
     *   - LOINC code 17861-6 replacing the NullFlavor/OTH placeholder
     *   - meta.tag  data-zone|codified
     *   - ext-inbound-source-ref pointing back to the inbound Observation
     *   - ext-data-provenance recording mappingType=fml and the StructureMap canonical
     */
    @Test
    void whenInboundObservationPosted_codificationFiresAndProducesLoincCodedObservation() {
        // Arrange: create a Patient so the Observation subject reference is valid
        Patient patient = new Patient();
        patient.setActive(true);
        String patientId = client.create().resource(patient).execute()
            .getId().getIdPart();

        // Build the inbound-zone Observation
        Observation inbound = buildInboundObservation(patientId);

        // Act: POST the inbound Observation — the interceptor fires afterCommit
        String inboundId = client.create().resource(inbound).execute()
            .getId().getIdPart();

        // Assert: poll until a codified Observation appears linked to this inbound resource.
        // CodificationService.codify() runs synchronously in afterCommit(), so the codified
        // resource is typically already present by the time the HTTP 201 is received.
        // Awaitility handles any edge-case scheduling lag.
        Bundle codifiedBundle = await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .until(
                () -> client.search().forResource(Observation.class)
                    .where(new ca.uhn.fhir.rest.gclient.StringClientParam("inbound-source-ref")
                        .matches().value("Observation/" + inboundId))
                    .returnBundle(Bundle.class)
                    .execute(),
                bundle -> bundle.getTotal() > 0
            );

        assertEquals(1, codifiedBundle.getTotal(),
            "Expected exactly one codified Observation for inbound Observation/" + inboundId);

        Observation codified = (Observation) codifiedBundle.getEntryFirstRep().getResource();

        // LOINC code replaces the NullFlavor/OTH placeholder
        assertTrue(
            codified.getCode().getCoding().stream()
                .anyMatch(c -> LOINC_SYSTEM.equals(c.getSystem()) && "17861-6".equals(c.getCode())),
            "Codified Observation must carry LOINC code 17861-6");

        assertFalse(
            codified.getCode().getCoding().stream()
                .anyMatch(c -> NULL_FLAVOR_SYSTEM.equals(c.getSystem())),
            "Codified Observation must not retain the NullFlavor/OTH placeholder coding");

        // code.text is carried forward by the FML map
        assertEquals("Calcium", codified.getCode().getText());

        // Zone tag: codified
        assertTrue(
            codified.getMeta().getTag().stream()
                .anyMatch(t -> DATA_ZONE_SYSTEM.equals(t.getSystem()) && "codified".equals(t.getCode())),
            "Codified Observation must carry the data-zone|codified meta tag");

        // Back-reference to the inbound resource
        Extension sourceRef = codified.getExtensionByUrl(EXT_INBOUND_SOURCE_REF);
        assertNotNull(sourceRef, "ext-inbound-source-ref must be present on codified Observation");
        assertEquals(
            "Observation/" + inboundId,
            ((Reference) sourceRef.getValue()).getReference(),
            "ext-inbound-source-ref must point to the originating inbound Observation");

        // Data provenance: FML mapping type + StructureMap URL
        Extension provenance = codified.getExtensionByUrl(EXT_DATA_PROVENANCE);
        assertNotNull(provenance, "ext-data-provenance must be present on codified Observation");

        Extension mappingType = provenance.getExtensionByUrl("mappingType");
        assertNotNull(mappingType, "ext-data-provenance must contain mappingType sub-extension");
        assertEquals("fml", ((CodeType) mappingType.getValue()).getValue());

        Extension mapUrl = provenance.getExtensionByUrl("structureMapUrl");
        assertNotNull(mapUrl, "ext-data-provenance must contain structureMapUrl sub-extension");
        assertEquals(STRUCTURE_MAP_CANONICAL, ((CanonicalType) mapUrl.getValue()).getValue());
    }

    // -------------------------------------------------------------------------
    // Builder helpers
    // -------------------------------------------------------------------------

    private Observation buildInboundObservation(String patientId) {
        Observation obs = new Observation();

        // Zone tag: inbound — triggers CodificationInterceptor
        obs.getMeta().addTag()
            .setSystem(DATA_ZONE_SYSTEM)
            .setCode("inbound")
            .setDisplay("Inbound Zone");

        // FML map reference — CodificationInterceptor reads this to find the StructureMap
        obs.addExtension()
            .setUrl(EXT_FML_MAP_REF)
            .setValue(new CanonicalType(STRUCTURE_MAP_CANONICAL));

        obs.setStatus(Observation.ObservationStatus.FINAL);

        // NullFlavor/OTH placeholder + code.text — FML replaces this with LOINC
        obs.getCode()
            .addCoding()
            .setSystem(NULL_FLAVOR_SYSTEM)
            .setCode("OTH")
            .setDisplay("Other");
        obs.getCode().setText("Calcium");

        obs.setSubject(new Reference("Patient/" + patientId));

        return obs;
    }
}
