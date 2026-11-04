package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The release BOM's static part. The placeholders (${VERSION}, ${SHA_*}, ${BASE_*}, ${POSTGRES_DIGEST}) are
 * filled by scripts/ci/write-release-bom.sh; everything else is the contract viscoFoundry renders from.
 */
class ReleaseTemplateTest {

    private static final Path TEMPLATE = Path.of("release/viscosuite-release.template.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode template() throws IOException {
        return MAPPER.readTree(Files.readString(TEMPLATE));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void theTopLevelShape() throws IOException {
        JsonNode t = template();
        assertEquals("${VERSION}", t.get("release").asText());
        assertTrue(t.get("suiteRelease").isNull(), "a Suite release builds on nothing");
        JsonNode base = t.get("base");
        assertEquals("${BASE_IMAGE}", base.get("image").asText());
        assertEquals("${BASE_DIGEST}", base.get("digest").asText());
        assertEquals("${VERSION}", base.get("version").asText());
        assertEquals(List.of("ApiListenerServlet", "WebContentServlet", "configRef", "larvaRun", "agentApi", "pack"), strings(base.get("servlets")),
            "the runtime's own servlets, PackServlet included (it lives in the core WAR, not in a pack)");
    }

    @Test
    void theComponentsAndTheirFields() throws IOException {
        JsonNode components = template().get("components");
        List<String> ids = new ArrayList<>();
        for (JsonNode c : components) {
            ids.add(c.get("id").asText());
            for (String field : List.of("id", "kind", "version", "requires", "excludes", "servlets", "properties")) {
                assertTrue(c.has(field), c.get("id") + " has " + field);
            }
            assertTrue(c.get("requires").isArray() && c.get("excludes").isArray() && c.get("servlets").isArray() && c.get("properties").isArray());
            switch (c.get("kind").asText()) {
                case "war", "overlay" -> {
                    assertTrue(c.has("artifact") && c.has("sha256") && c.has("deployAs"), c.get("id") + ": artifact, sha256, deployAs");
                    assertTrue(c.get("artifact").asText().startsWith("com.visco:"), "Maven coordinates");
                }
                case "container" -> assertTrue(c.has("image") && c.has("digest"), c.get("id") + ": image, digest");
                default -> throw new AssertionError("unknown kind " + c.get("kind"));
            }
        }
        assertEquals(List.of("store", "pack-health", "postgres"), ids, "exactly the Suite's components, in this order");
    }

    @Test
    void theStoreNeedsPostgresAndDeploysAsFhirStore() throws IOException {
        JsonNode store = template().get("components").get(0);
        assertEquals("com.visco:viscostore:${VERSION}:war", store.get("artifact").asText());
        assertEquals("fhir-store", store.get("deployAs").asText());
        assertEquals(List.of("postgres"), strings(store.get("requires")));
        assertEquals("${SHA_STORE}", store.get("sha256").asText());
    }

    @Test
    void theHealthPackDeclaresExactlyThePropertiesItsFileDefines() throws IOException {
        JsonNode pack = template().get("components").get(1);
        assertEquals("com.visco:viscolink-pack-health:${VERSION}:zip:overlay", pack.get("artifact").asText());
        assertEquals("overlay/runtime", pack.get("deployAs").asText());
        assertEquals("${SHA_PACK_HEALTH}", pack.get("sha256").asText());
        assertEquals(List.of(), strings(pack.get("servlets")), "the FHIR facade servlet registers itself with its own security; nothing to render");
        Set<String> fileKeys = new TreeSet<>();
        for (String line : Files.readAllLines(Path.of("../packs/health/src/main/resources/DeploymentSpecifics.properties"))) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("!") || !s.contains("=")) continue;
            fileKeys.add(s.substring(0, s.indexOf('=')).strip());
        }
        assertEquals(fileKeys, new TreeSet<>(strings(pack.get("properties"))),
            "properties[] = the keys of packs/health/src/main/resources/DeploymentSpecifics.properties (update both together)");
    }

    @Test
    void postgresIsPinnedByDigestAtReleaseTime() throws IOException {
        JsonNode pg = template().get("components").get(2);
        assertEquals("docker.io/library/postgres", pg.get("image").asText());
        assertEquals("16", pg.get("version").asText());
        assertEquals("${POSTGRES_DIGEST}", pg.get("digest").asText());
    }
}
