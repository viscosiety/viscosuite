package com.viscosiety.viscorunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fetcher and the placer, run with sh on file:// artifacts (no registry). The same scripts run
 * at image build (the fat images), in the pod's init container (M3) and in the casting bake.
 */
class FetchComponentsScriptTest {

    private static final Path FETCH = Path.of("src/scripts/fetch-components").toAbsolutePath();
    private static final Path PLACE = Path.of("src/scripts/place-components").toAbsolutePath();

    @BeforeAll
    static void tools() {
        for (String tool : new String[] {"sh", "curl", "unzip"}) {
            assumeTrue(onPath(tool), "needs " + tool);
        }
        assumeTrue(onPath("sha256sum") || onPath("shasum"), "needs sha256sum or shasum");
    }

    private static boolean onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, tool))) return true;
        }
        return false;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static Path war(Path dir, String name) throws IOException {
        Path war = dir.resolve(name);
        Files.writeString(war, "not really a war, but bytes are bytes: " + name);
        return war;
    }

    private static Path overlayZip(Path dir) throws IOException {
        Path zip = dir.resolve("pack.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("WEB-INF/lib/pack.jar"));
            out.write("jar".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("WEB-INF/classes/console/index.html"));
            out.write("<html>".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    record Run(int exit, String out, String err) {}

    private static Run run(Path script, Path cwd, Map<String, String> env, String... args) throws Exception {
        String[] cmd = new String[args.length + 2];
        cmd[0] = "sh"; cmd[1] = script.toString();
        System.arraycopy(args, 0, cmd, 2, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
        pb.environment().remove("CI_JOB_TOKEN");
        pb.environment().remove("COMPONENT_REGISTRY_TOKEN");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.waitFor(), out, err);
    }

    @Test
    void fetchesVerifiesPlacesAndWritesTheManifest(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectory(tmp.resolve("src"));
        Path store = war(src, "viscostore.war");
        Path zip = overlayZip(src);
        Path list = tmp.resolve("components.list");
        Files.writeString(list, String.join("\n",
                "release 1.0.0",
                "store war 1.0.0 file://" + store + " " + sha256(store) + " fhir-store",
                "pack-health overlay 1.0.0 file://" + zip + " " + sha256(zip) + " overlay/runtime",
                "postgres container 16 docker.io/library/postgres@sha256:abc - -",
                ""));
        Path components = tmp.resolve("components");
        Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", components.toString()), list.toString());
        assertEquals(0, r.exit(), r.err());

        assertTrue(Files.isRegularFile(components.resolve("webapps/fhir-store.war")), "the war under its context name");
        assertTrue(Files.isRegularFile(components.resolve("overlay/runtime/WEB-INF/lib/pack.jar")), "the overlay unzipped");
        assertEquals("release=1.0.0\ncomponent=store war 1.0.0\ncomponent=pack-health overlay 1.0.0\ncomponent=postgres container 16\n",
                Files.readString(components.resolve("MANIFEST")));
        assertEquals("{\"release\":\"1.0.0\",\"components\":[{\"id\":\"store\",\"kind\":\"war\",\"version\":\"1.0.0\"},"
                + "{\"id\":\"pack-health\",\"kind\":\"overlay\",\"version\":\"1.0.0\"},{\"id\":\"postgres\",\"kind\":\"container\",\"version\":\"16\"}]}\n",
                Files.readString(components.resolve("components.json")));

        Path webapps = Files.createDirectories(tmp.resolve("tomcat/webapps/ROOT")).getParent();
        Path overlays = Files.createDirectories(tmp.resolve("webapp-overlay/viscolink")).getParent();
        Run placed = run(PLACE, tmp, Map.of("VF_COMPONENTS_DIR", components.toString(),
                "VF_WEBAPPS_DIR", webapps.toString(), "VF_OVERLAY_DIR", overlays.toString()));
        assertEquals(0, placed.exit(), placed.err());
        assertTrue(Files.isRegularFile(webapps.resolve("fhir-store.war")), "placed into webapps/");
        assertTrue(Files.isRegularFile(overlays.resolve("viscolink/WEB-INF/lib/pack.jar")), "overlay/runtime lands in webapp-overlay/viscolink (M1 dir name)");
        assertTrue(Files.isRegularFile(overlays.resolve("viscolink/WEB-INF/classes/console/index.html")));
        assertTrue(Files.isRegularFile(webapps.resolve("ROOT/components.json")), "the landing page's components.json");

        // Placing twice is harmless (every start runs it).
        assertEquals(0, run(PLACE, tmp, Map.of("VF_COMPONENTS_DIR", components.toString(),
                "VF_WEBAPPS_DIR", webapps.toString(), "VF_OVERLAY_DIR", overlays.toString())).exit());
    }

    @Test
    void aWrongSha256FailsAndDeploysNothing(@TempDir Path tmp) throws Exception {
        Path store = war(Files.createDirectory(tmp.resolve("src")), "viscostore.war");
        Path list = tmp.resolve("components.list");
        Files.writeString(list, "release 1.0.0\nstore war 1.0.0 file://" + store + " " + "0".repeat(64) + " fhir-store\n");
        Path components = tmp.resolve("components");
        Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", components.toString()), list.toString());
        assertNotEquals(0, r.exit());
        assertTrue(r.err().contains("store") && r.err().contains("sha256"), r.err());
        assertFalse(Files.exists(components.resolve("webapps/fhir-store.war")), "nothing placed");
        assertFalse(Files.exists(components.resolve("MANIFEST")), "no manifest on failure");
    }

    @Test
    void aMissingFileFails(@TempDir Path tmp) throws Exception {
        Path list = tmp.resolve("components.list");
        Files.writeString(list, "release 1.0.0\nstore war 1.0.0 file://" + tmp.resolve("absent.war") + " " + "0".repeat(64) + " fhir-store\n");
        Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", tmp.resolve("c").toString()), list.toString());
        assertNotEquals(0, r.exit());
        assertTrue(r.err().contains("store"), r.err());
    }

    @Test
    void aBadLineOrKindOrDeployAsFails(@TempDir Path tmp) throws Exception {
        Path store = war(Files.createDirectory(tmp.resolve("src")), "viscostore.war");
        String sha = sha256(store);
        for (String bad : new String[] {
                "store war 1.0.0 file://" + store + " " + sha,                       // five fields
                "store blob 1.0.0 file://" + store + " " + sha + " fhir-store",      // unknown kind
                "store war 1.0.0 file://" + store + " " + sha + " overlay/x",       // a war with an overlay target
                "pack overlay 1.0.0 file://" + store + " " + sha + " runtime",      // an overlay without overlay/
                "st ore war 1.0.0 file://" + store + " " + sha + " fhir-store x",   // id with a space (7 fields)
                "s\"t war 1.0.0 file://" + store + " " + sha + " fhir-store"}) {    // id with a quote
            Files.writeString(tmp.resolve("components.list"), "release 1.0.0\n" + bad + "\n");
            Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", tmp.resolve("c").toString()), tmp.resolve("components.list").toString());
            assertNotEquals(0, r.exit(), "accepted: " + bad);
        }
    }

    @Test
    void theTokenNeverReachesTheOutput(@TempDir Path tmp) throws Exception {
        Path list = tmp.resolve("components.list");
        Files.writeString(list, "release 1.0.0\nstore war 1.0.0 https://127.0.0.1:9/nothing.war " + "0".repeat(64) + " fhir-store\n");
        Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", tmp.resolve("c").toString(), "COMPONENT_REGISTRY_TOKEN", "glpat-SECRET-VALUE"), list.toString());
        assertNotEquals(0, r.exit());
        assertFalse((r.out() + r.err()).contains("SECRET-VALUE"), "the token is never printed");
    }

    @Test
    void aListWithOnlyAReleaseLineIsAnEmptyCompositionNotAnError(@TempDir Path tmp) throws Exception {
        Path list = tmp.resolve("components.list");
        Files.writeString(list, "release 1.0.0\n");
        Path components = tmp.resolve("components");
        Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", components.toString()), list.toString());
        assertEquals(0, r.exit(), r.err());
        assertEquals("release=1.0.0\n", Files.readString(components.resolve("MANIFEST")));
        assertEquals("{\"release\":\"1.0.0\",\"components\":[]}\n", Files.readString(components.resolve("components.json")));
    }

    @Test
    void aLastLineWithoutANewlineIsStillFetched(@TempDir Path tmp) throws Exception {
        Path store = war(Files.createDirectory(tmp.resolve("src")), "viscostore.war");
        Path list = tmp.resolve("components.list");
        Files.writeString(list, "release 1.0.0\nstore war 1.0.0 file://" + store + " " + sha256(store) + " fhir-store");
        Path components = tmp.resolve("components");
        Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", components.toString()), list.toString());
        assertEquals(0, r.exit(), r.err());
        assertTrue(Files.isRegularFile(components.resolve("webapps/fhir-store.war")), "the unterminated last line is placed");
        assertEquals("release=1.0.0\ncomponent=store war 1.0.0\n", Files.readString(components.resolve("MANIFEST")));
    }

    @Test
    void aTokenIsNeverSentOverHttp(@TempDir Path tmp) throws Exception {
        Path list = tmp.resolve("components.list");
        Files.writeString(list, "release 1.0.0\nstore war 1.0.0 http://127.0.0.1:9/nothing.war " + "0".repeat(64) + " fhir-store\n");
        Run r = run(FETCH, tmp, Map.of("VF_COMPONENTS_DIR", tmp.resolve("c").toString(), "COMPONENT_REGISTRY_TOKEN", "glpat-SECRET-VALUE"), list.toString());
        assertNotEquals(0, r.exit());
        assertTrue(r.err().contains("cleartext"), r.err());
        assertFalse((r.out() + r.err()).contains("SECRET-VALUE"), "the token is never printed");
    }
}
