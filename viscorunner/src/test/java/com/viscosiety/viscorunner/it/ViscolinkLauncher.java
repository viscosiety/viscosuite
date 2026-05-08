package com.viscosiety.viscorunner.it;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.ContextResource;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Standalone launcher for the viscolink Frank!Framework WAR.
 *
 * <p>Runs in a <strong>separate JVM</strong> (started by {@link LabEnrichmentIT} via
 * {@link ProcessBuilder}) so that the viscolink WAR's Spring&nbsp;7 / Spring&nbsp;Boot&nbsp;4
 * classpath is completely isolated from the test JVM's Spring&nbsp;6 / Spring
 * Boot&nbsp;3 classpath.  Without this isolation, class-loader constraint violations arise from
 * having two incompatible Spring / SLF4J versions in the same JVM.</p>
 *
 * <h3>Classpath of this JVM</h3>
 * <ul>
 *   <li>{@code tomcat-embed-core-11.x.jar} — embedded Tomcat 11</li>
 *   <li>{@code tomcat-dbcp-11.x.jar} — DBCP2 DataSource factory for Tomcat JNDI</li>
 *   <li>{@code h2-2.4.x.jar} — H2 driver (shared file-based DB with the test JVM)</li>
 *   <li>{@code target/test-classes} — this class itself</li>
 * </ul>
 *
 * <h3>Command-line arguments</h3>
 * <ol>
 *   <li>Absolute path to the viscolink WAR file</li>
 *   <li>Absolute path to the F!F configurations directory (demo-configurations)</li>
 *   <li>ViscoStore FHIR base URL, e.g. {@code http://localhost:8080/fhir/}</li>
 *   <li>H2 JDBC URL (file-based with AUTO_SERVER=TRUE)</li>
 *   <li>ViscoStore password for the {@code viscostore} credential alias</li>
 * </ol>
 *
 * <h3>Output protocol</h3>
 * <p>Once the server is ready it prints {@code READY:{port}} to stdout and then blocks
 * until the process is killed.  {@link LabEnrichmentIT} reads this line to discover the port.</p>
 */
public class ViscolinkLauncher {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: ViscolinkLauncher <warPath> <configsDir> <viscoStoreUrl> <h2Url> <vsPassword> [configurationName]");
            System.exit(1);
        }

        Path warPath              = Paths.get(args[0]);
        Path configsDir           = Paths.get(args[1]);
        String viscoStoreUrl      = args[2];
        String h2Url              = args[3];
        String vsPassword         = args[4];
        String configurationName  = args.length > 5 ? args[5] : "fhir-to-fhir";

        // ── Temp working directory ───────────────────────────────────────────────────────
        Path baseDir = Files.createTempDirectory("viscolink-launcher-");
        Path logDir  = baseDir.resolve("logs");
        Files.createDirectories(logDir);

        // ── F!F system properties (read by AppConstants during webapp init) ─────────────
        System.setProperty("log.dir",   logDir.toString());
        System.setProperty("dtap.stage", "LOC");

        // Credential factory: match the production configuration (PropertyFileCredentialFactory
        // reading from a properties file).
        Path credentialsFile = baseDir.resolve("credentials.properties");
        Files.writeString(credentialsFile,
            "viscostore/username=viscolink\n" +
            "viscostore/password=" + vsPassword + "\n");
        System.setProperty("credentialFactory.class",
            "org.frankframework.credentialprovider.PropertyFileCredentialFactory");
        System.setProperty("credentialFactory.map.properties", credentialsFile.toString());
        System.setProperty("configurations.directory",       configsDir.toString());
        System.setProperty("configurations.names",           configurationName);
        System.setProperty("configurations.directory.autoLoad", "false");
        System.setProperty("classloader.type",               "DirectoryClassLoader");
        System.setProperty("viscostore.fhir.base.url",       viscoStoreUrl);

        // H2 credentials for resources.yml jdbc/ladybug (managed by F!F)
        System.setProperty("VISCOLINK_JDBC_DRIVER",   "org.h2.Driver");
        System.setProperty("VISCOLINK_JDBC_URL",      h2Url);
        System.setProperty("VISCOLINK_JDBC_USERNAME", "sa");
        System.setProperty("VISCOLINK_JDBC_PASSWORD", "");

        // Dummy values for jdbc/viscostore JNDI (not used by lab-enrichment, avoids binding errors)
        System.setProperty("VISCOSTORE_JDBC_DRIVER",   "org.h2.Driver");
        System.setProperty("VISCOSTORE_JDBC_URL",      h2Url);
        System.setProperty("VISCOSTORE_JDBC_USERNAME", "sa");
        System.setProperty("VISCOSTORE_JDBC_PASSWORD", "");

        // ── Extract WAR and patch WEB-INF/ ───────────────────────────────────────────────
        Path webappDir = baseDir.resolve("webapps/viscolink");
        extractWar(warPath, webappDir);

        // Override DeploymentSpecifics.properties by writing to WEB-INF/classes/ — Tomcat's
        // WebappClassLoader searches WEB-INF/classes/ before WEB-INF/lib/*.jar, so this file
        // takes precedence over the bundled DeploymentSpecifics.properties inside
        // viscolink-1.0.0-SNAPSHOT.jar.
        //
        // Key: the per-configuration classloader property is
        //   configurations.{name}.classLoaderType=DirectoryClassLoader
        // (NOT the legacy classloader.type property).
        // DirectoryClassLoader reads Configuration.xml from:
        //   configurations.directory / configurationName / Configuration.xml
        Path classesDir = webappDir.resolve("WEB-INF/classes");
        Files.createDirectories(classesDir);
        String configsDirPath = configsDir.toAbsolutePath().toString().replace("\\", "/");
        Files.writeString(classesDir.resolve("DeploymentSpecifics.properties"),
            "instance.name=viscolink\n" +
            "configurations.directory=" + configsDirPath + "\n" +
            "configurations.names=" + configurationName + "\n" +
            "configurations.directory.autoLoad=false\n" +
            // Per-configuration classloader type — F!F reads configurations.{name}.classLoaderType
            "configurations." + configurationName + ".classLoaderType=DirectoryClassLoader\n" +
            "viscostore.fhir.base.url=" + viscoStoreUrl + "\n" +
            // Properties consumed by hl7v2-to-fhir XSLTs; harmless for other configurations
            "fhir.target.version=r4\n" +
            "mr.system.base=https://ig.viscosiety.com/fhir/NamingSystem/\n" +
            "manageDatabase.active=false\n" +
            "jdbc.migrator.active=true\n" +
            "ladybug.jdbc.datasource=jdbc/ladybug\n" +
            "ladybug.jdbc.migrator.active=true\n" +
            // Disable AMQP publishing — no RabbitMQ in the IT environment.
            "amqp.events.active=false\n"
        );

        // Override resources.yml so that all MLLP adapters can start successfully:
        //   - inbound-2575: bind a dynamically allocated free port (avoids port-conflict with
        //     any running production server)
        //   - outbound-ris:  the production resources.yml has this commented out; without it
        //     MllpSender cannot find its resource and SendHL7v2OverMLLP goes to ERROR state,
        //     which makes the F!F health endpoint return non-200 indefinitely.
        //     We point it at localhost on a stub port — the connection is lazy so the adapter
        //     starts cleanly even though nothing is listening there.
        int mllpInboundPort  = findFreePort();
        int mllpOutboundPort = findFreePort();
        Files.writeString(classesDir.resolve("resources.yml"),
            "jdbc:\n" +
            "  - name: \"ladybug\"\n" +
            "    type: \"org.h2.Driver\"\n" +
            "    url: \"" + h2Url + "\"\n" +
            "    username: \"sa\"\n" +
            "    password: \"\"\n" +
            "\n" +
            "mllp:\n" +
            "  - name: \"inbound-2575\"\n" +
            "    url: \"mllp://0.0.0.0:" + mllpInboundPort + "\"\n" +
            "    properties:\n" +
            "      charset: \"UTF-8\"\n" +
            "      backlog: \"10\"\n" +
            "      socketTimeout: \"60000\"\n" +
            "\n" +
            "  - name: \"outbound-ris\"\n" +
            "    url: \"mllp://localhost:" + mllpOutboundPort + "\"\n" +
            "    properties:\n" +
            "      charset: \"UTF-8\"\n" +
            "      connectTimeout: \"5000\"\n" +
            "      socketTimeout: \"30000\"\n"
        );

        // ── Embedded Tomcat ───────────────────────────────────────────────────────────────
        int port = findFreePort();

        Tomcat tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.setBaseDir(baseDir.toString());
        tomcat.getConnector(); // required to create the connector in Tomcat 10+
        tomcat.enableNaming(); // required for JNDI

        // Deploy the extracted webapp directory
        Context ctx = tomcat.addWebapp("/viscolink", webappDir.toString());

        // jdbc/viscolink — used by F!F's FixedQuerySender in Configuration-lab-enrichment.xml
        ctx.getNamingResources().addResource(buildH2Resource("jdbc/viscolink", h2Url));

        // jdbc/viscostore — defined in the production context.xml; not used by lab-enrichment
        // adapters but must be bound to avoid JNDI lookup errors at startup
        ctx.getNamingResources().addResource(buildH2Resource("jdbc/viscostore", h2Url));

        // ── Start and signal readiness ────────────────────────────────────────────────────
        tomcat.start();

        System.out.println("READY:" + port);
        System.out.flush();

        // Block until the test kills this process
        tomcat.getServer().await();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private static ContextResource buildH2Resource(String jndiName, String jdbcUrl) {
        ContextResource r = new ContextResource();
        r.setName(jndiName);
        r.setType("javax.sql.DataSource");
        r.setProperty("driverClassName", "org.h2.Driver");
        r.setProperty("url",      jdbcUrl);
        r.setProperty("username", "sa");
        r.setProperty("password", "");
        r.setProperty("maxTotal", "10");
        r.setProperty("maxIdle",  "3");
        return r;
    }

    private static void extractWar(Path warPath, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(warPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(targetDir)) {
                    throw new IllegalStateException("Zip slip: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        }
    }
}
