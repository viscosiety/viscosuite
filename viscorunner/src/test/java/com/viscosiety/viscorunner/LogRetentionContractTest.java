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

package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Log retention in the runner image (2026-10-23): Frank!Framework's rolling log files and Tomcat's
 * daily files are kept for 30 days. Asserted on the files as text and XML (no docker needed). Paths
 * are relative to the module directory, where surefire runs.
 */
class LogRetentionContractTest {

    private static final Path MODULE = Path.of(".");
    private static final String RETENTION_FILE = "src/scripts/log4j4retention.xml";
    private static final String IMAGE_PATH = "/opt/frank/log4j/log4j4retention.xml";
    private static final String GLOB = "*.{log,json,xml}.*";

    /** Appender name -> its expected {@code max} (the rolled-file history). */
    private static final Map<String, String> MAX = new LinkedHashMap<>();
    static {
        MAX.put("leak-log-appender", "${ff:log.minor.maxBackupIndex:-4}");
        MAX.put("file", "${ff:log.maxBackupIndex}");
        MAX.put("msg-text", "${ff:log.msg.maxBackupIndex}");
        MAX.put("msg-json", "${ff:log.msg.maxBackupIndex}");
        MAX.put("security", "${ff:log.minor.maxBackupIndex:-4}");
        MAX.put("heartbeat", "${ff:log.minor.maxBackupIndex:-4}");
        MAX.put("config", "5");
        MAX.put("ladybug", "${ff:log.minor.maxBackupIndex:-4}");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MODULE.resolve(relative));
    }

    private static Document parse(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(in);
    }

    /** {@code <Appender type="RollingFile" name=...>} elements by name. */
    private static Map<String, Element> rollingAppenders(Document doc) {
        Map<String, Element> byName = new LinkedHashMap<>();
        NodeList appenders = doc.getElementsByTagName("Appender");
        for (int i = 0; i < appenders.getLength(); i++) {
            Element appender = (Element) appenders.item(i);
            if ("RollingFile".equals(appender.getAttribute("type"))) {
                byName.put(appender.getAttribute("name"), appender);
            }
        }
        return byName;
    }

    private static Element only(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        assertEquals(1, nodes.getLength(), parent.getAttribute("name") + ": exactly one <" + tag + ">");
        return (Element) nodes.item(0);
    }

    @Test
    void theRetentionFileIsTheLastOfTheCompositeConfiguration() throws IOException {
        String line = read("src/scripts/catalinaAdditional.properties").lines()
            .filter(l -> l.startsWith("log4j.configurationFile="))
            .findFirst().orElse(null);
        assertNotNull(line, "catalinaAdditional.properties sets log4j.configurationFile");
        // Later files replace same-named appenders of earlier ones (log4j2 DefaultMergeStrategy).
        assertEquals("log4j.configurationFile=log4j4ibis.xml,log4j2stdout.xml," + IMAGE_PATH, line);
    }

    @Test
    void theImageCarriesTheRetentionFile() throws IOException {
        List<String> lines = read("Dockerfile").lines()
            .filter(l -> !l.stripLeading().startsWith("#"))
            .map(l -> l.replaceAll("\\s+", " ").strip())
            .collect(Collectors.toList());
        assertTrue(lines.contains("COPY --chown=tomcat src/scripts/log4j4retention.xml " + IMAGE_PATH),
            "the retention file lands where log4j.configurationFile points");
    }

    @Test
    void everyRollingAppenderDeletesRolledFilesAfterThirtyDays() throws Exception {
        Document doc;
        try (InputStream in = Files.newInputStream(MODULE.resolve(RETENTION_FILE))) {
            doc = parse(in);
        }
        Map<String, Element> appenders = rollingAppenders(doc);
        assertEquals(new TreeSet<>(MAX.keySet()), new TreeSet<>(appenders.keySet()), "F!F's eight rolling appenders");
        assertEquals(0, doc.getElementsByTagName("Loggers").getLength(),
            "no loggers: the composite keeps F!F's own and log2stdout's appender references");
        for (Map.Entry<String, String> expected : MAX.entrySet()) {
            Element appender = appenders.get(expected.getKey());
            Element strategy = only(appender, "RolloverStrategy");
            assertEquals("DefaultRolloverStrategy", strategy.getAttribute("type"), expected.getKey());
            assertEquals("min", strategy.getAttribute("fileIndex"), expected.getKey());
            assertEquals(expected.getValue(), strategy.getAttribute("max"), expected.getKey() + " max");
            Element delete = only(strategy, "Delete");
            assertEquals("${ff:log.dir}", delete.getAttribute("basePath"), expected.getKey());
            assertEquals("1", delete.getAttribute("maxDepth"), expected.getKey());
            assertEquals(GLOB, only(delete, "IfFileName").getAttribute("glob"), expected.getKey());
            assertEquals("30d", only(delete, "IfLastModified").getAttribute("age"), expected.getKey());
        }
    }

    @Test
    void theGlobMatchesRolledFrankFilesOnly() {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + GLOB);
        for (String rolled : List.of("viscolink.log.3", "viscolink-messages.json.1", "viscolink-messages.log.10",
                "viscolink-config.xml.2", "testtool4viscolink.log.1", "viscolink-security.log.4")) {
            assertTrue(matcher.matches(Path.of(rolled)), rolled);
        }
        for (String kept : List.of("viscolink.log", "viscolink-messages.json", "testtool4viscolink.log",
                "catalina.2026-10-01.log", "localhost.2026-10-01.log", "localhost_access_log.2026-10-01.txt",
                "java_pid1.hprof")) {
            assertFalse(matcher.matches(Path.of(kept)), kept);
        }
    }

    /**
     * Drift guard: when Frank!Framework's own file is on the test classpath, every redefined appender
     * writes the same files with the same layout as F!F's. Skipped when it is not (then the docker
     * proof in the plan covers it).
     */
    @Test
    void theAppendersMatchFrankFrameworksOwn() throws Exception {
        InputStream frank = getClass().getClassLoader().getResourceAsStream("log4j4ibis.xml");
        assumeTrue(frank != null, "frankframework-core is not on the test classpath");
        Map<String, Element> ours;
        Map<String, Element> theirs;
        try (InputStream in = Files.newInputStream(MODULE.resolve(RETENTION_FILE)); InputStream f = frank) {
            ours = rollingAppenders(parse(in));
            theirs = rollingAppenders(parse(f));
        }
        assertEquals(new TreeSet<>(theirs.keySet()), new TreeSet<>(ours.keySet()),
            "F!F's rolling appenders changed: re-copy them into " + RETENTION_FILE);
        for (String name : theirs.keySet()) {
            assertEquals(theirs.get(name).getAttribute("fileName"), ours.get(name).getAttribute("fileName"), name);
            assertEquals(theirs.get(name).getAttribute("filePattern"), ours.get(name).getAttribute("filePattern"), name);
        }
    }
}
