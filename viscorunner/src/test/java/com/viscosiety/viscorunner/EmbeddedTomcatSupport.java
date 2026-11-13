package com.viscosiety.viscorunner;

import java.lang.reflect.Field;
import java.nio.file.Path;

import org.apache.catalina.startup.CatalinaBaseConfigurationSource;
import org.apache.tomcat.util.file.ConfigFileLoader;

/**
 * Test helper for embedded Tomcats. Tomcat keeps the configuration source of the FIRST embedded Tomcat for
 * the whole JVM (set-once), so a later Tomcat with another base directory read no rewrite.config (found in the
 * 2026-11-12 tunnel spike). Call this before {@code start()}.
 */
public final class EmbeddedTomcatSupport {

    private EmbeddedTomcatSupport() {
    }

    /**
     * {@code ConfigFileLoader.setSource} only works once per JVM (it ignores a second call), so the field is set
     * directly: every embedded Tomcat then reads its own conf/ directory.
     */
    public static void useBaseDir(Path baseDir) {
        try {
            Field source = ConfigFileLoader.class.getDeclaredField("source");
            source.setAccessible(true);
            source.set(null, new CatalinaBaseConfigurationSource(baseDir.toFile(), null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set Tomcat's configuration source", e);
        }
    }
}
