package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The store-only image has no shared viscorunner jar, so it cannot load the endpoint-group RewriteMap: it ships its own
 * rules file, the runner's without the map line and the group rule. This pins that the two cannot drift.
 */
class RewriteConfigContractTest {

    private static final Path MODULE = Path.of(".");

    private static String read(String relative) throws IOException {
        return Files.readString(MODULE.resolve(relative));
    }

    @Test
    void theStoreRulesAreTheRunnerRulesWithoutTheMapAndTheGroupBlock() throws IOException {
        List<String> runner = new ArrayList<>(List.of(read("conf/Catalina/localhost/rewrite.config").split("\n")));
        runner.removeIf(line -> line.startsWith("RewriteMap "));
        int rule = -1;
        for (int i = 0; i < runner.size(); i++) {
            if (runner.get(i).contains("/runtime/api-group/")) {
                rule = i;
            }
        }
        assertTrue(rule >= 2, "the runner file has the group rule");
        assertTrue(runner.get(rule - 1).contains("${apigroups:") && runner.get(rule - 2).contains("SERVER_PORT"),
            "the group rule has its two conditions");
        runner.subList(rule - 2, rule + 1).clear();
        assertEquals(String.join("\n", runner), String.join("\n", read("conf/viscostore/rewrite.config").split("\n")));
    }

    @Test
    void theStoreRulesHaveNoMapAtAll() throws IOException {
        String store = read("conf/viscostore/rewrite.config");
        assertFalse(store.lines().anyMatch(l -> l.startsWith("RewriteMap ")), "no RewriteMap line");
        assertFalse(store.contains("${"), "no map reference");
        assertFalse(store.contains("api-group"), "no group rule");
    }

    @Test
    void everyImageThatShipsAMapPutsTheSharedJarOnItsLoader() throws IOException {
        assertTrue(read("Dockerfile.viscostore").contains("COPY --chown=tomcat conf/viscostore/rewrite.config "),
            "the store image copies its own rules");
        try (Stream<Path> files = Files.list(MODULE)) {
            for (Path dockerfile : files.filter(p -> p.getFileName().toString().startsWith("Dockerfile")).toList()) {
                String text = Files.readString(dockerfile);
                if (text.contains("tomcat conf/Catalina/localhost/rewrite.config ")) {
                    assertTrue(text.contains("target/viscorunner-*.jar /opt/frank/lib/"), dockerfile + " ships the shared jar");
                    assertTrue(read("src/scripts/catalinaAdditional.properties").contains("\"/opt/frank/lib/*.jar\""),
                        "and its common.loader lists it");
                }
            }
        }
    }
}
