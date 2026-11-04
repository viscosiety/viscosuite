package com.viscosiety.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComponentManifestTest {

    @Test
    void parsesTheReleaseAndTheComponentsInOrder() {
        ComponentManifest m = ComponentManifest.parse(List.of(
                "release=1.0.0",
                "component=store war 1.0.0",
                "",
                "# a comment",
                "component=pack-health overlay 1.0.0",
                "component=postgres container 16"));
        assertEquals("1.0.0", m.release().orElseThrow());
        assertEquals(List.of(
                new ComponentManifest.Component("store", "war", "1.0.0"),
                new ComponentManifest.Component("pack-health", "overlay", "1.0.0"),
                new ComponentManifest.Component("postgres", "container", "16")), m.components());
    }

    @Test
    void anEmptyReleaseIsAbsent() {
        ComponentManifest m = ComponentManifest.parse(List.of("release=", "component=store war 1.0.0"));
        assertTrue(m.release().isEmpty());
    }

    @Test
    void aMalformedLineIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> ComponentManifest.parse(List.of("component=store war")));
        assertThrows(IllegalArgumentException.class, () -> ComponentManifest.parse(List.of("store war 1.0.0")));
    }

    @Test
    void aMissingFileIsNoManifest(@TempDir Path dir) {
        ComponentManifest m = ComponentManifest.read(dir.resolve("MANIFEST"));
        assertTrue(m.release().isEmpty());
        assertTrue(m.components().isEmpty());
    }

    @Test
    void readsTheFileThePropertyNames(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("MANIFEST");
        Files.writeString(file, "release=1.0.0-77\ncomponent=store war 1.0.0-77\n");
        String old = System.setProperty(ComponentManifest.PATH_PROPERTY, file.toString());
        try {
            assertEquals("1.0.0-77", ComponentManifest.current().release().orElseThrow());
        } finally {
            if (old == null) System.clearProperty(ComponentManifest.PATH_PROPERTY); else System.setProperty(ComponentManifest.PATH_PROPERTY, old);
        }
    }
}
