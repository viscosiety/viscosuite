package com.viscosiety.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.Delete;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.annotation.Update;
import ca.uhn.fhir.rest.server.IResourceProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.frankframework.util.CredentialFactory;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * HAPI FHIR plain-server servlet for a single FHIR facade.
 *
 * <p>One instance is created per {@code (fhirVersion, facadeName)} pair, triggered lazily when
 * the first {@link FhirListener} for that facade is configured.  The servlet is registered with
 * Tomcat by {@link FhirServletRegistrar} and serves requests at:</p>
 * <pre>
 *   /viscolink/fhir/{version}/{facadeName}/*
 * </pre>
 *
 * <p>Resource providers are built at HAPI {@code initialize()} time by querying
 * {@link FhirOperationRegistry} for all operations registered under the same
 * {@code (fhirVersion, facadeName)} key.</p>
 *
 * <h3>Transparent CDR proxy</h3>
 * <p>When a {@link FhirListener} with {@code operation="proxy"} is declared for this facade,
 * the servlet intercepts requests that no registered provider can satisfy and forwards them
 * verbatim to the upstream CDR at {@code proxyCdrBaseUrl}.  A provider is considered to
 * "satisfy" a request only when it has a method annotated with the appropriate HAPI annotation
 * for the inferred FHIR operation ({@code @Read}, {@code @Search}, {@code @Create},
 * {@code @Update}, {@code @Delete}).  This means a facade can selectively enrich one operation
 * (e.g. Observation search) while all other operations — including read-by-id on the same
 * resource type — are transparently proxied to the CDR.</p>
 */
public class FhirFacadeServlet extends AbstractFhirServlet {

    private static final long serialVersionUID = 1L;

    /** Shared HTTP client for CDR proxy calls — thread-safe, reuses connections. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String fhirVersion;
    private final String facadeName;
    private final FhirFfBridge bridge;

    /**
     * Set during {@link #initialize()} from {@link FhirOperationRegistry}.
     * {@code null} when no proxy adapter is declared for this facade.
     */
    private volatile FhirListener proxyListener;

    public FhirFacadeServlet(String fhirVersion, String facadeName, FhirFfBridge bridge) {
        this.fhirVersion = fhirVersion;
        this.facadeName  = facadeName;
        this.bridge      = bridge;
    }

    @Override
    public String getName() {
        return "FhirFacadeServlet-" + fhirVersion + "-" + facadeName;
    }

    /** URL pattern relative to the viscolink context root, e.g. {@code fhir/r4/my-facade/*}. */
    @Override
    public String getUrlMapping() {
        return "fhir/" + fhirVersion.toLowerCase() + "/" + facadeName + "/*";
    }

    @Override
    protected FhirContext createFhirContext() {
        return switch (fhirVersion.toUpperCase()) {
            case "R4"    -> FhirContext.forR4();
            case "R5"    -> FhirContext.forR5();
            case "DSTU3" -> FhirContext.forDstu3();
            default      -> throw new IllegalArgumentException("Unsupported FHIR version: " + fhirVersion);
        };
    }

    @Override
    protected List<IResourceProvider> createProviders() {
        FhirContext ctx = getFhirContext();
        return FhirOperationRegistry.getOperationsForFacade(fhirVersion, facadeName).stream()
                .map(op -> FhirProviderFactory.create(op, bridge, ctx))
                .filter(Objects::nonNull) // proxy operations return null — no HAPI provider needed
                .toList();
    }

    @Override
    protected void initialize() throws ServletException {
        super.initialize();
        proxyListener = FhirOperationRegistry.getProxyListener(fhirVersion, facadeName);
        if (proxyListener != null) {
            log.info("{}: CDR proxy active — unhandled routes forwarded to [{}]",
                    getName(), proxyListener.getProxyCdrBaseUrl());
        }
    }

    /**
     * Intercepts requests that no registered provider can satisfy and forwards them to the
     * upstream CDR when a proxy listener is configured.  All other requests are handled
     * normally by HAPI.
     */
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        if (proxyListener != null && shouldProxy(req)) {
            proxyRequest(req, resp, proxyListener.getProxyCdrBaseUrl());
            return;
        }
        super.service(req, resp);
    }

    /**
     * Returns {@code true} when the request should be proxied to the upstream CDR instead of
     * being handled by HAPI.
     *
     * <p>A request is proxied when:</p>
     * <ul>
     *   <li>No provider is registered for the resource type, or</li>
     *   <li>A provider is registered for the resource type but does not expose the inferred
     *       FHIR operation (e.g. a search-only provider for a read request).</li>
     * </ul>
     * <p>Non-resource paths ({@code /metadata}, {@code /$everything}) are never proxied —
     * HAPI handles them natively.</p>
     */
    private boolean shouldProxy(HttpServletRequest req) {
        String resourceType = parseResourceType(req.getPathInfo());
        if (resourceType == null) return false;

        List<IResourceProvider> providers = getResourceProviders().stream()
                .filter(p -> p.getResourceType().getSimpleName().equals(resourceType))
                .toList();

        if (providers.isEmpty()) return true; // no provider at all for this resource type

        // Proxy only when no registered provider covers this specific operation.
        // A resource type can have multiple providers (e.g. separate search and read),
        // so all of them must be checked before falling back to the CDR.
        String fhirOp = inferFhirOperation(req.getPathInfo(), req.getMethod());
        return providers.stream().noneMatch(p -> providerSupportsOperation(p, fhirOp));
    }

    /**
     * Infers the FHIR operation type from the HTTP method and path.
     *
     * <ul>
     *   <li>{@code GET /Resource}       → {@code "search"}</li>
     *   <li>{@code GET /Resource/id}    → {@code "read"}</li>
     *   <li>{@code POST /Resource}      → {@code "create"}</li>
     *   <li>{@code PUT /Resource/id}    → {@code "update"}</li>
     *   <li>{@code DELETE /Resource/id} → {@code "delete"}</li>
     * </ul>
     */
    private static String inferFhirOperation(String pathInfo, String httpMethod) {
        String[] segs = pathInfo == null ? new String[0]
                : Arrays.stream(pathInfo.split("/"))
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new);

        return switch (httpMethod.toUpperCase()) {
            // A second path segment that isn't a FHIR operation ($) is a resource id → read
            case "GET"    -> segs.length >= 2 && !segs[1].startsWith("$") ? "read" : "search";
            case "POST"   -> "create";
            case "PUT"    -> "update";
            case "DELETE" -> "delete";
            default       -> "unknown";
        };
    }

    /**
     * Returns {@code true} when {@code provider} has a method annotated with the HAPI annotation
     * that corresponds to {@code fhirOperation}.
     */
    private static boolean providerSupportsOperation(IResourceProvider provider, String fhirOperation) {
        Class<? extends Annotation> annotation = switch (fhirOperation) {
            case "read"   -> Read.class;
            case "search" -> Search.class;
            case "create" -> Create.class;
            case "update" -> Update.class;
            case "delete" -> Delete.class;
            default       -> null;
        };
        if (annotation == null) return false;
        final Class<? extends Annotation> ann = annotation;
        return Arrays.stream(provider.getClass().getMethods())
                .anyMatch(m -> m.isAnnotationPresent(ann));
    }

    /**
     * Forwards {@code req} verbatim to {@code cdrBaseUrl + pathInfo + queryString} and writes
     * the CDR's status code, Content-Type, and body back to {@code resp}.
     */
    private void proxyRequest(HttpServletRequest req, HttpServletResponse resp, String cdrBaseUrl)
            throws IOException {
        String pathInfo  = req.getPathInfo() != null ? req.getPathInfo() : "/";
        String query     = req.getQueryString();
        String base      = cdrBaseUrl.endsWith("/")
                ? cdrBaseUrl.substring(0, cdrBaseUrl.length() - 1)
                : cdrBaseUrl;
        String targetUrl = base + pathInfo + (query != null ? "?" + query : "");

        byte[] requestBody = req.getInputStream().readAllBytes();
        HttpRequest.BodyPublisher publisher = requestBody.length > 0
                ? HttpRequest.BodyPublishers.ofByteArray(requestBody)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .method(req.getMethod(), publisher);

        String accept      = req.getHeader("Accept");
        String contentType = req.getHeader("Content-Type");
        if (accept      != null) rb.header("Accept",       accept);
        if (contentType != null) rb.header("Content-Type", contentType);

        String credAlias = proxyListener != null ? proxyListener.getProxyCdrCredentialAlias() : null;
        if (credAlias != null && !credAlias.isBlank()) {
            CredentialFactory cf = new CredentialFactory(credAlias, null, null);
            String encoded = Base64.getEncoder().encodeToString(
                    (cf.getUsername() + ":" + cf.getPassword()).getBytes(StandardCharsets.UTF_8));
            rb.header("Authorization", "Basic " + encoded);
        }

        log.debug("{}: proxy {} {} → {}", getName(), req.getMethod(), req.getRequestURI(), targetUrl);

        try {
            HttpResponse<byte[]> cdrResp = HTTP_CLIENT.send(rb.build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            resp.setStatus(cdrResp.statusCode());
            cdrResp.headers().firstValue("Content-Type")
                    .ifPresent(resp::setContentType);
            cdrResp.headers().firstValue("ETag")
                    .ifPresent(v -> resp.setHeader("ETag", v));
            cdrResp.headers().firstValue("Last-Modified")
                    .ifPresent(v -> resp.setHeader("Last-Modified", v));
            resp.getOutputStream().write(cdrResp.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "CDR request interrupted");
        } catch (Exception e) {
            log.error("{}: proxy request failed for [{}]", getName(), targetUrl, e);
            resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "CDR proxy failed: " + e.getMessage());
        }
    }

    /**
     * Extracts the leading FHIR resource type from a servlet path-info string.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code /Patient/123}   → {@code "Patient"}</li>
     *   <li>{@code /Observation?…} → {@code "Observation"}</li>
     *   <li>{@code /metadata}      → {@code null} (non-resource path; let HAPI handle)</li>
     *   <li>{@code null}           → {@code null}</li>
     * </ul>
     *
     * <p>FHIR resource type names always start with an uppercase letter, which distinguishes
     * them from FHIR "compartment" and operation paths ({@code /metadata}, {@code /$everything})
     * that HAPI handles natively.</p>
     */
    private static String parseResourceType(String pathInfo) {
        if (pathInfo == null || pathInfo.isEmpty()) return null;
        for (String seg : pathInfo.split("/")) {
            if (!seg.isEmpty()) {
                return Character.isUpperCase(seg.charAt(0)) ? seg : null;
            }
        }
        return null;
    }
}
