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
package com.viscosiety.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericXmlApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.wearefrank.ladybug.MetadataExtractor;
import org.wearefrank.ladybug.MetadataFieldExtractor;

import com.viscosiety.ladybug.StubMetadataFieldExtractor;

/**
 * Loads the real {@code springIbisTestToolVisco.xml} (the file the Frank!Framework picks up through
 * {@code ibistesttool.custom=Visco}) in a minimal Spring context, so the pack's subject identifier is
 * proven to reach Ladybug's metadata through the XML itself: the SpEL in the {@code <value>} entries
 * and the extractor bean, not just the extractor class.
 *
 * <p>Only the file's own {@code <import resource="springIbisTestTool.xml"/>} is replaced, by a stub that
 * defines the three standard views the file refers to; everything else in the file is the real thing.</p>
 */
class LadybugWiringTest {

    /** What springIbisTestTool.xml (via springTestToolCommon.xml) contributes that the Visco file builds on. */
    private static final String IMPORT_STUB = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd">
                <bean name="whiteBoxView" class="org.wearefrank.ladybug.filter.View">
                    <property name="name" value="White box"/>
                </bean>
                <bean name="grayBoxView" parent="whiteBoxView">
                    <property name="name" value="Gray box"/>
                </bean>
                <bean name="blackBoxView" parent="whiteBoxView">
                    <property name="name" value="Black box"/>
                </bean>
            </beans>
            """;

    /** The default views' column set as it stands in the file, with the subject column at index 5. */
    private static final List<String> DEFAULT_METADATA_NAMES = List.of("storageId", "endTime", "duration", "name",
            "flow", "patientId", "correlationId", "status", "stubbed", "numberOfCheckpoints",
            "estimatedMemoryUsage", "storageSize");
    private static final int DEFAULT_SUBJECT_INDEX = 5;

    /** The Shareable view's column set as it stands in the file, with the subject column at index 6. */
    private static final List<String> SHAREABLE_METADATA_NAMES = List.of("storageId", "endTime", "duration", "name",
            "originId", "sharable", "patientId", "correlationId", "status");
    private static final int SHAREABLE_SUBJECT_INDEX = 6;

    /** The extra extractors in file order, with the subject extractor at index 3. */
    private static final List<String> EXTRACTOR_NAMES = List.of("status", "stubbed", "flow", "patientId",
            "sharable", "originId");
    private static final int EXTRACTOR_SUBJECT_INDEX = 3;

    private GenericXmlApplicationContext context;

    @AfterEach
    void closeContextAndForgetPack() {
        if (context != null) {
            context.close();
        }
        PackRegistry.reset();
    }

    @Test
    void healthPackLeavesLadybugsMetadataAsItWasWithAHardcodedPatientId() {
        PackRegistry.override(new HealthValuesPack());
        loadVisco();

        MetadataExtractor extractor = context.getBean("metadataExtractor", MetadataExtractor.class);
        assertExtractors(extractor, "patientId", "PatientId");
        assertEquals(DEFAULT_METADATA_NAMES, namesBean("metadataNames"));
        assertEquals(SHAREABLE_METADATA_NAMES, namesBean("shareableViewMetadataNames"));
    }

    @Test
    void corePackPutsTheNeutralSubjectInTheSamePositions() {
        PackRegistry.override(new CorePack());
        loadVisco();

        MetadataExtractor extractor = context.getBean("metadataExtractor", MetadataExtractor.class);
        assertExtractors(extractor, "subjectId", "SubjectId");
        assertEquals(withSubject(DEFAULT_METADATA_NAMES, DEFAULT_SUBJECT_INDEX, "subjectId"),
                namesBean("metadataNames"));
        assertEquals(withSubject(SHAREABLE_METADATA_NAMES, SHAREABLE_SUBJECT_INDEX, "subjectId"),
                namesBean("shareableViewMetadataNames"));
    }

    @Test
    void eachSubjectFieldReachesItsOwnPlaceInTheXml() {
        // The shipped packs use one value for sessionKey and metadataName, so only distinct values show
        // that the SpEL entries take the metadata name and the bean takes name, label and session key.
        PackRegistry.override(new DistinctSubjectPack());
        loadVisco();

        MetadataExtractor extractor = context.getBean("metadataExtractor", MetadataExtractor.class);
        assertExtractors(extractor, "mn", "ML");
        assertEquals(withSubject(DEFAULT_METADATA_NAMES, DEFAULT_SUBJECT_INDEX, "mn"), namesBean("metadataNames"));
        assertEquals(withSubject(SHAREABLE_METADATA_NAMES, SHAREABLE_SUBJECT_INDEX, "mn"),
                namesBean("shareableViewMetadataNames"));

        MetadataFieldExtractor subject = extractor.extraMetadataFieldExtractors.get(EXTRACTOR_SUBJECT_INDEX);
        assertEquals("S-1", subject.extractMetadata(SubjectMetadataFieldExtractorTest.reportWithSessionKey("sk", "S-1")),
                "the bean reads the pack's sessionKey");
        assertNull(subject.extractMetadata(SubjectMetadataFieldExtractorTest.reportWithSessionKey("mn", "S-1")));
    }

    private void assertExtractors(MetadataExtractor extractor, String subjectName, String subjectLabel) {
        List<MetadataFieldExtractor> extras = extractor.extraMetadataFieldExtractors;
        List<String> expectedNames = withSubject(EXTRACTOR_NAMES, EXTRACTOR_SUBJECT_INDEX, subjectName);
        List<String> actualNames = new ArrayList<>();
        for (MetadataFieldExtractor extra : extras) {
            actualNames.add(extra.getName());
        }
        assertEquals(expectedNames, actualNames, "the extractors keep their order and names");

        SubjectMetadataFieldExtractor subject = assertInstanceOf(SubjectMetadataFieldExtractor.class,
                extras.get(EXTRACTOR_SUBJECT_INDEX));
        assertEquals(subjectName, subject.getName());
        assertEquals(subjectLabel, subject.getLabel());
        assertInstanceOf(StubMetadataFieldExtractor.class, extras.get(1), "the neighbours are untouched");
        assertEquals(1, extras.stream().filter(SubjectMetadataFieldExtractor.class::isInstance).count());
    }

    private static List<String> withSubject(List<String> names, int index, String subject) {
        List<String> copy = new ArrayList<>(names);
        copy.set(index, subject);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private List<String> namesBean(String beanName) {
        return context.getBean(beanName, List.class);
    }

    private void loadVisco() {
        context = new GenericXmlApplicationContext();
        context.load(new ViscoFileWithStubbedImport());
        context.refresh();
    }

    /** The real file on the class path; only its relative import of the Ladybug jar's XML is answered by the stub. */
    private static final class ViscoFileWithStubbedImport extends ClassPathResource {

        ViscoFileWithStubbedImport() {
            super("springIbisTestToolVisco.xml");
        }

        @Override
        public Resource createRelative(String relativePath) {
            assertEquals("springIbisTestTool.xml", relativePath, "the file must import nothing else");
            return new ByteArrayResource(IMPORT_STUB.getBytes(StandardCharsets.UTF_8), "stub of " + relativePath);
        }
    }
}
