package com.viscosiety.viscorunner.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * SPIKE (2026-11-12, tunnel API) Q3: the Tomcat filter-order rules the runtime web application relies
 * on, with stand-ins. "security" is registered the way Spring Security's
 * AbstractSecurityWebApplicationInitializer registers springSecurityFilterChain (programmatic,
 * isMatchAfter=false, REQUEST/ERROR/ASYNC/FORWARD/INCLUDE, "/*", during onStartup, first). "guard" is
 * registered later, programmatically, the way ConsoleSecurityRegistrar adds its filter from a Spring
 * bean (isMatchAfter=false) -- but only for REQUEST on "/api/*". "declared" stands for a web.xml filter.
 * Results in docs/design/2026-11-12-tunnel-api-spike.md.
 */
// Off in the normal build: Tomcat's ConfigFileLoader keeps the FIRST embedded Tomcat's base dir for the whole
// JVM, so a spike Tomcat started before RuntimeSessionCookieScopeTest makes that test read no rewrite.config.
// Run with: ./mvnw test -pl viscorunner -Dtest='Tunnel*SpikeTest' -Dspike=true
@EnabledIfSystemProperty(named = "spike", matches = "true")
class TunnelFilterOrderSpikeTest {

    private static Tomcat tomcat;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("tunnel-filter-spike").toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context runtime = tomcat.addContext("/runtime", new File(".").getAbsolutePath());

        FilterDef declared = new FilterDef();
        declared.setFilterName("declared");
        declared.setFilter(tracer("declared"));
        runtime.addFilterDef(declared);
        FilterMap declaredMap = new FilterMap();
        declaredMap.setFilterName("declared");
        declaredMap.addURLPattern("/*");
        declaredMap.setDispatcher(DispatcherType.REQUEST.name());
        declaredMap.setDispatcher(DispatcherType.FORWARD.name());
        runtime.addFilterMap(declaredMap);

        ServletContainerInitializer initializer = (classes, servletContext) -> {
            servletContext.addFilter("security", tracer("security"))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC,
                            DispatcherType.FORWARD, DispatcherType.INCLUDE), false, "/*");
            servletContext.addFilter("guard", tracer("guard"))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/api/*");
        };
        runtime.addServletContainerInitializer(initializer, null);

        Tomcat.addServlet(runtime, "api", new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.getWriter().write("api" + request.getPathInfo() + " " + trace(request));
            }
        });
        runtime.addServletMappingDecoded("/api/*", "api");
        Tomcat.addServlet(runtime, "tunnelApi", new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                trace(request).add("tunnelApi-servlet");
                request.getRequestDispatcher("/api" + request.getPathInfo()).forward(request, response);
            }
        });
        runtime.addServletMappingDecoded("/tunnel-api/*", "tunnelApi");
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stop() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    @SuppressWarnings("unchecked")
    private static List<String> trace(HttpServletRequest request) {
        Object trace = request.getAttribute("trace");
        if (trace == null) {
            trace = new ArrayList<String>();
            request.setAttribute("trace", trace);
        }
        return (List<String>) trace;
    }

    private static Filter tracer(String name) {
        return (request, response, chain) -> {
            trace((HttpServletRequest) request).add(name + ":" + request.getDispatcherType());
            chain.doFilter(request, response);
        };
    }

    private static String get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void aLaterProgrammaticFilterRunsAfterTheEarlierOneAndBeforeDeclaredFilters() throws Exception {
        assertEquals("api/orders [security:REQUEST, guard:REQUEST, declared:REQUEST]", get("/runtime/api/orders"));
    }

    @Test
    void aRequestOnlyGuardDoesNotRunOnTheTunnelServletsForwardButSecurityDoes() throws Exception {
        assertEquals("api/orders [security:REQUEST, declared:REQUEST, tunnelApi-servlet, security:FORWARD, declared:FORWARD]",
                get("/runtime/tunnel-api/orders"));
    }

}
