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

package com.viscosiety.viscorunner.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * The viscorunner jar sits on Tomcat's common.loader, so every webapp can see what it carries.
 * Its Kubernetes client (for ContextFailureEventPublisher) must therefore stay invisible to them:
 * an unrelocated fabric8 copy registers an HttpClient$Factory that a webapp's own fabric8 then
 * loads from the shared copy, and the webapp fails to start with "not a subtype" (live
 * 2026-11-01). These checks run on the packaged jar, after the shade.
 */
class SharedJarIsolationIT {

    private static final String SHADED = "com/viscosiety/shaded/";
    private static final List<String> FORBIDDEN_PREFIXES =
            List.of("io/fabric8/", "io/vertx/", "io/netty/", "com/fasterxml/jackson/", "tools/jackson/",
                    "org/yaml/snakeyaml/", "org/snakeyaml/", "org/slf4j/", "org/checkerframework/", "META-INF/versions/");

    @Test
    void sharedJarExposesNoLibraryClassesOrRegistrations() throws IOException {
        List<String> leaks = new ArrayList<>();
        try (JarFile jar = new JarFile(sharedJar().toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                for (String prefix : FORBIDDEN_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        leaks.add(name);
                    }
                }
                if (name.startsWith("META-INF/services/") && !entry.isDirectory()) {
                    String service = name.substring("META-INF/services/".length());
                    if (isLibraryName(service)) {
                        leaks.add(name);
                    }
                    for (String provider : providers(jar, entry)) {
                        if (isLibraryName(provider)) {
                            leaks.add(name + " -> " + provider);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), leaks.stream().limit(20).toList(),
                "the shared jar must relocate every library it carries (" + leaks.size() + " leaks)");
    }

    @Test
    void relocatedClientPublishesAnEvent() throws Exception {
        AtomicReference<String> seenPath = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            seenPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            seenBody.set(new String(request, StandardCharsets.UTF_8));
            byte[] response = request.length > 0 ? request : "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader shared = new SharedFirstClassLoader(sharedJar().toUri().toURL(), getClass().getClassLoader())) {
            Thread.currentThread().setContextClassLoader(shared);
            Class<?> publisher = shared.loadClass("com.viscosiety.viscorunner.k8s.ContextFailureEventPublisher");
            assertEquals(shared, publisher.getClassLoader(), "the publisher comes from the packaged jar");
            Method publishOnce = publisher.getDeclaredMethod("publishOnce",
                    String.class, String.class, String.class, String.class, String.class);
            publishOnce.setAccessible(true);
            String masterUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            try {
                publishOnce.invoke(null, masterUrl, "tenant-x", "runner-pod", "/runtime", "boom");
            } catch (InvocationTargetException e) {
                throw new AssertionError("the relocated client could not publish", e.getCause());
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            server.stop(0);
        }
        assertEquals("POST /api/v1/namespaces/tenant-x/events", seenPath.get());
        assertTrue(seenBody.get().contains("\"reason\":\"ContextStartFailed\""), seenBody.get());
        assertTrue(seenBody.get().contains("context [/runtime] failed to start; boom"), seenBody.get());
    }

    private static boolean isLibraryName(String className) {
        String path = className.trim().replace('.', '/');
        return !path.isEmpty() && !path.startsWith("#") && !path.startsWith(SHADED)
                && FORBIDDEN_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private static List<String> providers(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(line -> line.replaceAll("#.*", "").trim())
                    .filter(line -> !line.isEmpty())
                    .toList();
        }
    }

    private static Path sharedJar() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            List<Path> jars = files
                    .filter(p -> p.getFileName().toString().matches("viscorunner-.*\\.jar"))
                    .filter(p -> !p.getFileName().toString().startsWith("original-"))
                    .toList();
            assertEquals(1, jars.size(), "exactly one packaged viscorunner jar in target/: " + jars);
            return jars.get(0);
        }
    }

    /**
     * Loads com.viscosiety.* from the packaged jar before the test class path (which holds the
     * unshaded classes), like the jar's own classes on Tomcat's common.loader.
     */
    private static final class SharedFirstClassLoader extends URLClassLoader {
        SharedFirstClassLoader(URL jar, ClassLoader parent) {
            super(new URL[] {jar}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("com.viscosiety.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }
}
