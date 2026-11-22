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

package com.viscosiety.filesystem;

import java.lang.reflect.Field;
import java.security.CodeSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;

/**
 * Refuses to start unless the loaded {@code org.frankframework.filesystem.LocalFileSystem} is ViscoLink's
 * root-containment copy (see that class's banner and frankframework/frankframework#12074).
 *
 * <p>The override only wins because the servlet spec has the webapp classloader search {@code WEB-INF/classes}
 * before {@code WEB-INF/lib}. That precedence exists in a WAR and nowhere else: run the same classes from a
 * plain class path, a shaded jar or an unexpected packaging layout and {@code frankframework-filesystem.jar}
 * wins silently, which would put path containment back to upstream's unchecked {@code toFile}. A missing
 * marker field is therefore a hard startup failure, not a warning: every {@code LocalFileSystemPipe} and
 * {@code LocalFileSystemSender} in every configuration would otherwise accept absolute filenames and
 * {@code ..} traversal out of its configured root.
 *
 * <p>Registered through {@code springLocalFileSystemOverride.xml}
 * ({@link com.viscosiety.components.ViscoLinkModule}). Delete together with the override.
 */
public class LocalFileSystemOverrideCheck implements InitializingBean {

    static final String OVERRIDDEN_CLASS = "org.frankframework.filesystem.LocalFileSystem";
    static final String MARKER_FIELD = "VISCO_OVERRIDE";

    private static final Logger log = LogManager.getLogger(LocalFileSystemOverrideCheck.class);

    @Override
    public void afterPropertiesSet() {
        Class<?> loaded = loadOverriddenClass();
        log.info("ViscoLink [{}] override is active: [{}={}], loaded from [{}]",
                OVERRIDDEN_CLASS, MARKER_FIELD, readMarker(loaded), codeSourceOf(loaded));
    }

    /**
     * @return the marker's value, for the log line.
     * @throws IllegalStateException when the class that got loaded is not ViscoLink's copy.
     */
    String readMarker(Class<?> loaded) {
        try {
            Field marker = loaded.getDeclaredField(MARKER_FIELD);
            Object value = marker.get(null);
            if (!(value instanceof String text) || text.isEmpty()) {
                throw new IllegalStateException(failureMessage(loaded,
                        "its [" + MARKER_FIELD + "] field holds [" + value + "] instead of a non-empty String"));
            }
            return text;
        } catch (NoSuchFieldException e) {
            // The framework's own class, from WEB-INF/lib: no marker field, no containment.
            throw new IllegalStateException(failureMessage(loaded, "it has no [" + MARKER_FIELD + "] field"), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(failureMessage(loaded, "its [" + MARKER_FIELD + "] field is not readable"), e);
        }
    }

    private Class<?> loadOverriddenClass() {
        try {
            return Class.forName(OVERRIDDEN_CLASS, true, LocalFileSystemOverrideCheck.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("[" + OVERRIDDEN_CLASS + "] is not on the class path at all", e);
        }
    }

    private String failureMessage(Class<?> loaded, String what) {
        return "the loaded [" + OVERRIDDEN_CLASS + "] is NOT ViscoLink's root-containment override, because "
                + what + ". It was loaded from [" + codeSourceOf(loaded) + "]. Without the override a "
                + "LocalFileSystem does not confine file access to its root (frankframework/frankframework#12074), "
                + "so startup is refused. The override is only picked up from WEB-INF/classes of a deployed WAR; "
                + "check the stage-localfilesystem-override execution in viscolink/pom.xml and the packaging layout.";
    }

    private String codeSourceOf(Class<?> type) {
        if (type.getProtectionDomain() == null) {
            return "unknown";
        }
        CodeSource codeSource = type.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return "unknown";
        }
        return codeSource.getLocation().toString();
    }
}
