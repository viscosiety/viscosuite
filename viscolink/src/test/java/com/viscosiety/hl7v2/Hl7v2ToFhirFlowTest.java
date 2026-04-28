package com.viscosiety.hl7v2;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.parser.XMLParser;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.xml.transform.*;
import javax.xml.transform.stream.*;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: pipe-delimited HL7v2 → HL7v2 XML → XSLT → FHIR R4 Bundle.
 *
 * Exercises the same conversion chain as the production pipeline (HAPI HL7v2 +
 * Saxon XSLT 3.0) without requiring a running F!F container.
 *
 * PV1 field-position notes:
 *   - PV1.19 (visit number) needs 17 pipes after PV1.2 value:
 *     PV1|1|I|||||||||||||||||V001
 *   - PV1.44 (admit) is 25 pipes after PV1.19:
 *     V001|||||||||||||||||||||||||20240315143000
 *   - PV1.45 (discharge) is 1 pipe after PV1.44:
 *     20240315143000|20240318160000
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Hl7v2ToFhirFlowTest {

    private static final String XSLT_DIR =
            "../viscorunner/demo-configurations/hl7v2-to-fhir/xslt/r4";

    private static final FhirContext FHIR_CTX = FhirContext.forR4();

    // --- test messages -------------------------------------------------------

    private static final String ADT_A01 = msg("ADT^A01^ADT_A01", "001", "A01",
            "PV1|1|I|||||||||||||||||V001");

    private static final String ADT_A02 = msg("ADT^A02^ADT_A02", "002", "A02",
            "PV1|1|I|||||||||||||||||V001");

    // PV1.44 (admit) = 25 pipes after V001; PV1.45 (discharge) = 1 pipe after PV1.44
    private static final String ADT_A03 = msg("ADT^A03^ADT_A03", "003", "A03",
            "PV1|1|I|||||||||||||||||V001|||||||||||||||||||||||||20240315143000|20240318160000");

    // A04 uses ADT_A01 message structure; PV1.2=O (outpatient registration)
    private static final String ADT_A04 = msg("ADT^A04^ADT_A01", "004", "A04",
            "PV1|1|O|||||||||||||||||V004");

    // A08 uses ADT_A01 structure; PV1 present but A08.xslt ignores it (Patient-only output)
    private static final String ADT_A08 = msg("ADT^A08^ADT_A01", "008", "A08",
            "PV1|1|I|||||||||||||||||V001");

    // A11 uses ADT_A09 structure (shared with A09/A10/A12)
    private static final String ADT_A11 = msg("ADT^A11^ADT_A09", "011", "A11",
            "PV1|1|I|||||||||||||||||V001");

    // A13 uses ADT_A01 structure (cancel discharge, revert to in-progress)
    private static final String ADT_A13 = msg("ADT^A13^ADT_A01", "013", "A13",
            "PV1|1|I|||||||||||||||||V001");

    private static String msg(String msgType, String msgId, String evtCode, String pv1) {
        return "MSH|^~\\&|HIS|HOSPITAL|VISCO|DEST|20240315143000||" + msgType + "|MSG-" + msgId + "|P|2.5\r"
             + "EVN|" + evtCode + "|20240315143000\r"
             + "PID|1||PAT001^^^HOSPITAL^MR||SMITH^JANE^M||19751020|F\r"
             + pv1 + "\r";
    }

    // --- lifecycle -----------------------------------------------------------

    private DefaultHapiContext hapiContext;
    private PipeParser pipeParser;
    private XMLParser xmlParser;
    private TransformerFactory transformerFactory;

    @BeforeAll
    void setUp() {
        hapiContext = new DefaultHapiContext(new CanonicalModelClassFactory("2.5"));
        pipeParser = hapiContext.getPipeParser();
        xmlParser = hapiContext.getXMLParser();
        // Explicit Saxon instantiation — XSLT 3.0 features (maps, XPath 3.1) require it.
        transformerFactory = TransformerFactory.newInstance(
                "net.sf.saxon.TransformerFactoryImpl", getClass().getClassLoader());
    }

    @AfterAll
    void tearDown() throws Exception {
        hapiContext.close();
    }

    // --- helpers -------------------------------------------------------------

    private String toHl7Xml(String pipeDelimited) throws Exception {
        return xmlParser.encode(pipeParser.parse(pipeDelimited));
    }

    private Bundle transform(String hl7v2Pipe, String triggerEvent) throws Exception {
        return transform(hl7v2Pipe, triggerEvent, UUID.randomUUID().toString());
    }

    private Bundle transform(String hl7v2Pipe, String triggerEvent, String transactionUuid)
            throws Exception {
        String hl7Xml = toHl7Xml(hl7v2Pipe);
        Path xsltPath = Path.of(XSLT_DIR, "ADT_" + triggerEvent + ".xslt");
        Templates templates = transformerFactory.newTemplates(new StreamSource(xsltPath.toFile()));
        Transformer transformer = templates.newTransformer();
        transformer.setParameter("transactionUuid", transactionUuid);
        StringWriter out = new StringWriter();
        transformer.transform(new StreamSource(new StringReader(hl7Xml)), new StreamResult(out));
        return (Bundle) FHIR_CTX.newXmlParser().parseResource(out.toString());
    }

    private BundleEntryComponent findEntry(Bundle bundle, String resourceType) {
        return bundle.getEntry().stream()
                .filter(e -> e.getResource() != null
                        && resourceType.equals(e.getResource().getResourceType().name()))
                .findFirst()
                .orElse(null);
    }

    // --- tests ---------------------------------------------------------------

    @Test
    void adtA01_producesTransactionBundleWithPatientAndEncounter() throws Exception {
        Bundle bundle = transform(ADT_A01, "A01");

        assertEquals("transaction", bundle.getType().toCode());
        assertEquals(2, bundle.getEntry().size());

        BundleEntryComponent patientEntry = findEntry(bundle, "Patient");
        assertNotNull(patientEntry);
        assertTrue(patientEntry.getFullUrl().startsWith("urn:uuid:"),
                "Patient fullUrl should be a urn:uuid placeholder");

        BundleEntryComponent encounterEntry = findEntry(bundle, "Encounter");
        assertNotNull(encounterEntry);
        assertTrue(encounterEntry.getFullUrl().startsWith("urn:uuid:"),
                "Encounter fullUrl should be a urn:uuid placeholder");

        Patient patient = (Patient) patientEntry.getResource();
        assertEquals("SMITH", patient.getNameFirstRep().getFamily());
        assertEquals("JANE", patient.getNameFirstRep().getGiven().get(0).getValue());
        assertEquals("female", patient.getGender().toCode());

        Encounter encounter = (Encounter) encounterEntry.getResource();
        assertEquals("in-progress", encounter.getStatus().toCode());
        assertEquals(patientEntry.getFullUrl(), encounter.getSubject().getReference(),
                "Encounter.subject must reference the bundle-internal Patient fullUrl");
    }

    @Test
    void adtA01_differentTransactionUuidsProduceDifferentFullUrls() throws Exception {
        Bundle first  = transform(ADT_A01, "A01", UUID.randomUUID().toString());
        Bundle second = transform(ADT_A01, "A01", UUID.randomUUID().toString());

        assertNotEquals(
                findEntry(first,  "Patient").getFullUrl(),
                findEntry(second, "Patient").getFullUrl(),
                "Different transactionUuids must produce different fullUrls");
    }

    @Test
    void adtA01_sameTransactionUuidProducesSameFullUrls() throws Exception {
        String uuid = UUID.randomUUID().toString();
        Bundle first  = transform(ADT_A01, "A01", uuid);
        Bundle second = transform(ADT_A01, "A01", uuid);

        assertEquals(
                findEntry(first,  "Patient").getFullUrl(),
                findEntry(second, "Patient").getFullUrl(),
                "Same transactionUuid must produce identical fullUrls (deterministic)");
    }

    @Test
    void adtA03_dischargeProducesFinishedEncounterWithPeriodEnd() throws Exception {
        Bundle bundle = transform(ADT_A03, "A03");

        assertEquals(2, bundle.getEntry().size());
        Encounter encounter = (Encounter) findEntry(bundle, "Encounter").getResource();

        assertEquals("finished", encounter.getStatus().toCode());
        assertNotNull(encounter.getPeriod().getStart(), "A03 should carry admit start from PV1.44");
        assertNotNull(encounter.getPeriod().getEnd(),   "A03 discharge must set period.end from PV1.45");
    }

    @Test
    void adtA08_demographicsUpdateContainsPatientEntryOnly() throws Exception {
        Bundle bundle = transform(ADT_A08, "A08");

        assertEquals(1, bundle.getEntry().size());
        assertNotNull(findEntry(bundle, "Patient"),   "A08 must produce a Patient entry");
        assertNull   (findEntry(bundle, "Encounter"), "A08 must not produce an Encounter entry");
    }

    @Test
    void adtA11_cancelAdmitProducesCancelledEncounterWithoutPatient() throws Exception {
        Bundle bundle = transform(ADT_A11, "A11");

        assertEquals(1, bundle.getEntry().size());
        assertNull(findEntry(bundle, "Patient"), "A11 must not produce a Patient entry");

        Encounter encounter = (Encounter) findEntry(bundle, "Encounter").getResource();
        assertEquals("cancelled", encounter.getStatus().toCode());
    }

    static Stream<Arguments> smokeMessages() {
        return Stream.of(
                Arguments.of("A02", ADT_A02, 2),
                Arguments.of("A04", ADT_A04, 2),
                Arguments.of("A13", ADT_A13, 2));
    }

    @ParameterizedTest(name = "ADT^{0} → transaction bundle with {2} entr(ies)")
    @MethodSource("smokeMessages")
    void smoke_triggerEventProducesTransactionBundle(String trigger, String hl7, int expectedEntries)
            throws Exception {
        Bundle bundle = transform(hl7, trigger);
        assertEquals("transaction", bundle.getType().toCode());
        assertEquals(expectedEntries, bundle.getEntry().size());
    }
}
