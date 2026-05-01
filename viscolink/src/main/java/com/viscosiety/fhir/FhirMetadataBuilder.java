package com.viscosiety.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.frankframework.util.CredentialFactory;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Builds FHIR {@code CapabilityStatement} responses for {@code /metadata} requests on a facade.
 *
 * <p>Two strategies:</p>
 * <ul>
 *   <li><b>Proxy facade</b>: fetches the upstream CDR's {@code /metadata}, adds a
 *       {@code proxied-by} extension and a description prefix, and annotates each resource entry
 *       whose operations are intercepted locally by ViscoLink.</li>
 *   <li><b>Non-proxy facade</b>: builds a minimal {@link org.hl7.fhir.r4.model.CapabilityStatement}
 *       (or DSTU3/R5 equivalent) from the operations registered in {@link FhirOperationRegistry}.
 *       When the CDR is unreachable the proxy case falls back to the same built statement.</li>
 * </ul>
 */
class FhirMetadataBuilder {

    private static final Logger log = LogManager.getLogger(FhirMetadataBuilder.class);

    private static final String PROXY_EXT_URL =
            "http://viscosiety.com/fhir/StructureDefinition/proxied-by";

    // ── Entry point ──────────────────────────────────────────────────────────

    static void handle(HttpServletRequest req, HttpServletResponse resp,
                       FhirContext fhirContext, String fhirVersion, String facadeName,
                       FhirListener proxyListener, HttpClient httpClient) throws IOException {
        boolean xml = wantsXml(req);
        IBaseResource cs = proxyListener != null
                ? buildProxyMetadata(fhirContext, fhirVersion, facadeName, proxyListener, httpClient)
                : buildFreshMetadata(fhirContext, fhirVersion, facadeName);

        IParser parser = (xml ? fhirContext.newXmlParser() : fhirContext.newJsonParser())
                .setPrettyPrint(true);
        String ct = (xml ? "application/fhir+xml" : "application/fhir+json") + "; charset=UTF-8";
        resp.setContentType(ct);
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(parser.encodeResourceToString(cs));
    }

    private static boolean wantsXml(HttpServletRequest req) {
        String format = req.getParameter("_format");
        if (format != null) {
            String f = format.toLowerCase(Locale.ROOT);
            if (f.contains("xml"))  return true;
            if (f.contains("json")) return false;
        }
        String accept = req.getHeader("Accept");
        return accept != null && accept.contains("xml") && !accept.contains("json");
    }

    // ── Proxy case: augment CDR CapabilityStatement ──────────────────────────

    private static IBaseResource buildProxyMetadata(FhirContext fhirContext, String fhirVersion,
                                                     String facadeName, FhirListener proxyListener,
                                                     HttpClient httpClient) {
        String cdrBase = proxyListener.getProxyCdrBaseUrl();
        String base    = cdrBase.endsWith("/") ? cdrBase.substring(0, cdrBase.length() - 1) : cdrBase;
        String metaUrl = base + "/metadata?_format=json";

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(metaUrl))
                .GET()
                .header("Accept", "application/fhir+json");

        String credAlias = proxyListener.getProxyCdrCredentialAlias();
        if (credAlias != null && !credAlias.isBlank()) {
            CredentialFactory cf = new CredentialFactory(credAlias, null, null);
            String encoded = Base64.getEncoder().encodeToString(
                    (cf.getUsername() + ":" + cf.getPassword()).getBytes(StandardCharsets.UTF_8));
            rb.header("Authorization", "Basic " + encoded);
        }

        try {
            HttpResponse<String> cdrResp = httpClient.send(rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (cdrResp.statusCode() != 200) {
                log.warn("FhirMetadataBuilder: CDR returned HTTP {} for /metadata — falling back to constructed statement",
                        cdrResp.statusCode());
                return buildFreshMetadata(fhirContext, fhirVersion, facadeName);
            }
            IBaseResource cs = fhirContext.newJsonParser().parseResource(cdrResp.body());
            augmentProxy(cs, fhirVersion, facadeName, cdrBase);
            return cs;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("FhirMetadataBuilder: CDR metadata fetch interrupted — falling back to constructed statement");
            return buildFreshMetadata(fhirContext, fhirVersion, facadeName);
        } catch (Exception e) {
            log.error("FhirMetadataBuilder: failed to fetch CDR metadata from [{}] — falling back to constructed statement",
                    metaUrl, e);
            return buildFreshMetadata(fhirContext, fhirVersion, facadeName);
        }
    }

    private static void augmentProxy(IBaseResource cs, String fhirVersion, String facadeName,
                                     String cdrBase) {
        Map<String, Set<String>> intercepted = new LinkedHashMap<>();
        for (FhirOperation op : FhirOperationRegistry.getOperationsForFacade(fhirVersion, facadeName)) {
            if (!"proxy".equals(op.operation()) && !"metadata".equals(op.operation())) {
                intercepted.computeIfAbsent(op.resourceType(), k -> new LinkedHashSet<>())
                           .add(op.operation());
            }
        }
        switch (fhirVersion.toUpperCase(Locale.ROOT)) {
            case "R4"    -> augmentR4((org.hl7.fhir.r4.model.CapabilityStatement) cs, intercepted, cdrBase);
            case "R5"    -> augmentR5((org.hl7.fhir.r5.model.CapabilityStatement) cs, intercepted, cdrBase);
            case "DSTU3" -> augmentDstu3((org.hl7.fhir.dstu3.model.CapabilityStatement) cs, intercepted, cdrBase);
            default      -> log.warn("FhirMetadataBuilder: unsupported FHIR version for proxy augmentation: {}", fhirVersion);
        }
    }

    private static void augmentR4(org.hl7.fhir.r4.model.CapabilityStatement cs,
                                   Map<String, Set<String>> intercepted, String cdrBase) {
        cs.addExtension(PROXY_EXT_URL, new org.hl7.fhir.r4.model.UriType(cdrBase));
        String note = "Proxied by ViscoLink from " + cdrBase + ".";
        String desc = cs.getDescription();
        cs.setDescription(desc == null || desc.isBlank() ? note : note + " " + desc);
        if (intercepted.isEmpty()) return;
        for (var rest : cs.getRest()) {
            for (var res : rest.getResource()) {
                Set<String> ops = intercepted.get(res.getType());
                if (ops != null) {
                    String intercept = "Intercepted by ViscoLink: " + String.join(", ", ops) + ".";
                    String doc = res.getDocumentation();
                    res.setDocumentation(doc == null || doc.isBlank() ? intercept : doc + " " + intercept);
                }
            }
        }
    }

    private static void augmentR5(org.hl7.fhir.r5.model.CapabilityStatement cs,
                                   Map<String, Set<String>> intercepted, String cdrBase) {
        cs.addExtension(PROXY_EXT_URL, new org.hl7.fhir.r5.model.UriType(cdrBase));
        String note = "Proxied by ViscoLink from " + cdrBase + ".";
        String desc = cs.getDescription();
        cs.setDescription(desc == null || desc.isBlank() ? note : note + " " + desc);
        if (intercepted.isEmpty()) return;
        for (var rest : cs.getRest()) {
            for (var res : rest.getResource()) {
                Set<String> ops = intercepted.get(res.getType());
                if (ops != null) {
                    String intercept = "Intercepted by ViscoLink: " + String.join(", ", ops) + ".";
                    String doc = res.getDocumentation();
                    res.setDocumentation(doc == null || doc.isBlank() ? intercept : doc + " " + intercept);
                }
            }
        }
    }

    private static void augmentDstu3(org.hl7.fhir.dstu3.model.CapabilityStatement cs,
                                      Map<String, Set<String>> intercepted, String cdrBase) {
        cs.addExtension(PROXY_EXT_URL, new org.hl7.fhir.dstu3.model.UriType(cdrBase));
        String note = "Proxied by ViscoLink from " + cdrBase + ".";
        String desc = cs.getDescription();
        cs.setDescription(desc == null || desc.isBlank() ? note : note + " " + desc);
        if (intercepted.isEmpty()) return;
        for (var rest : cs.getRest()) {
            for (var res : rest.getResource()) {
                Set<String> ops = intercepted.get(res.getType());
                if (ops != null) {
                    String intercept = "Intercepted by ViscoLink: " + String.join(", ", ops) + ".";
                    String doc = res.getDocumentation();
                    res.setDocumentation(doc == null || doc.isBlank() ? intercept : doc + " " + intercept);
                }
            }
        }
    }

    // ── Non-proxy case: build from registered operations ─────────────────────

    private static IBaseResource buildFreshMetadata(FhirContext fhirContext, String fhirVersion,
                                                     String facadeName) {
        Set<FhirOperation> ops = FhirOperationRegistry.getOperationsForFacade(fhirVersion, facadeName);
        return switch (fhirVersion.toUpperCase(Locale.ROOT)) {
            case "R4"    -> buildR4(facadeName, ops);
            case "R5"    -> buildR5(facadeName, ops);
            case "DSTU3" -> buildDstu3(facadeName, ops);
            default      -> throw new IllegalArgumentException("Unsupported FHIR version: " + fhirVersion);
        };
    }

    private static org.hl7.fhir.r4.model.CapabilityStatement buildR4(String facadeName,
                                                                        Set<FhirOperation> ops) {
        var cs = new org.hl7.fhir.r4.model.CapabilityStatement();
        cs.setStatus(org.hl7.fhir.r4.model.Enumerations.PublicationStatus.ACTIVE);
        cs.setKind(org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementKind.INSTANCE);
        cs.setDate(new Date());
        cs.setPublisher("Viscosiety ViscoLink");
        cs.setDescription("FHIR R4 capability statement for ViscoLink facade '" + facadeName
                + "'. Implemented natively by ViscoLink.");
        cs.setFhirVersion(org.hl7.fhir.r4.model.Enumerations.FHIRVersion._4_0_1);
        cs.addFormat("application/fhir+json");
        cs.addFormat("application/fhir+xml");

        var rest = cs.addRest();
        rest.setMode(org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode.SERVER);

        boolean hasTransaction = false;
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (FhirOperation op : ops) {
            if ("proxy".equals(op.operation()) || "metadata".equals(op.operation())) continue;
            if ("bundle-transaction".equals(op.operation())) { hasTransaction = true; continue; }
            byType.computeIfAbsent(op.resourceType(), k -> new ArrayList<>()).add(op.operation());
        }
        for (var entry : byType.entrySet()) {
            var res = rest.addResource();
            res.setType(entry.getKey());
            for (String opName : entry.getValue()) {
                res.addInteraction().setCode(toR4Interaction(opName));
            }
        }
        if (hasTransaction) {
            rest.addInteraction().setCode(
                    org.hl7.fhir.r4.model.CapabilityStatement.SystemRestfulInteraction.TRANSACTION);
        }
        return cs;
    }

    private static org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction toR4Interaction(
            String op) {
        return switch (op) {
            case "read"   -> org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction.READ;
            case "search" -> org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE;
            case "create" -> org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction.CREATE;
            case "update" -> org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction.UPDATE;
            case "delete" -> org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction.DELETE;
            default       -> org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction.NULL;
        };
    }

    private static org.hl7.fhir.r5.model.CapabilityStatement buildR5(String facadeName,
                                                                        Set<FhirOperation> ops) {
        var cs = new org.hl7.fhir.r5.model.CapabilityStatement();
        cs.setStatus(org.hl7.fhir.r5.model.Enumerations.PublicationStatus.ACTIVE);
        cs.setKind(org.hl7.fhir.r5.model.Enumerations.CapabilityStatementKind.INSTANCE);
        cs.setDate(new Date());
        cs.setPublisher("Viscosiety ViscoLink");
        cs.setDescription("FHIR R5 capability statement for ViscoLink facade '" + facadeName
                + "'. Implemented natively by ViscoLink.");
        cs.setFhirVersion(org.hl7.fhir.r5.model.Enumerations.FHIRVersion._5_0_0);
        cs.addFormat("application/fhir+json");
        cs.addFormat("application/fhir+xml");

        var rest = cs.addRest();
        rest.setMode(org.hl7.fhir.r5.model.CapabilityStatement.RestfulCapabilityMode.SERVER);

        boolean hasTransaction = false;
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (FhirOperation op : ops) {
            if ("proxy".equals(op.operation()) || "metadata".equals(op.operation())) continue;
            if ("bundle-transaction".equals(op.operation())) { hasTransaction = true; continue; }
            byType.computeIfAbsent(op.resourceType(), k -> new ArrayList<>()).add(op.operation());
        }
        for (var entry : byType.entrySet()) {
            var res = rest.addResource();
            res.setType(entry.getKey());
            for (String opName : entry.getValue()) {
                res.addInteraction().setCode(toR5Interaction(opName));
            }
        }
        if (hasTransaction) {
            rest.addInteraction().setCode(
                    org.hl7.fhir.r5.model.CapabilityStatement.SystemRestfulInteraction.TRANSACTION);
        }
        return cs;
    }

    private static org.hl7.fhir.r5.model.CapabilityStatement.TypeRestfulInteraction toR5Interaction(
            String op) {
        return switch (op) {
            case "read"   -> org.hl7.fhir.r5.model.CapabilityStatement.TypeRestfulInteraction.READ;
            case "search" -> org.hl7.fhir.r5.model.CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE;
            case "create" -> org.hl7.fhir.r5.model.CapabilityStatement.TypeRestfulInteraction.CREATE;
            case "update" -> org.hl7.fhir.r5.model.CapabilityStatement.TypeRestfulInteraction.UPDATE;
            case "delete" -> org.hl7.fhir.r5.model.CapabilityStatement.TypeRestfulInteraction.DELETE;
            default       -> org.hl7.fhir.r5.model.CapabilityStatement.TypeRestfulInteraction.NULL;
        };
    }

    private static org.hl7.fhir.dstu3.model.CapabilityStatement buildDstu3(String facadeName,
                                                                              Set<FhirOperation> ops) {
        var cs = new org.hl7.fhir.dstu3.model.CapabilityStatement();
        cs.setStatus(org.hl7.fhir.dstu3.model.Enumerations.PublicationStatus.ACTIVE);
        cs.setKind(org.hl7.fhir.dstu3.model.CapabilityStatement.CapabilityStatementKind.INSTANCE);
        cs.setDate(new Date());
        cs.setPublisher("Viscosiety ViscoLink");
        cs.setDescription("FHIR DSTU3 capability statement for ViscoLink facade '" + facadeName
                + "'. Implemented natively by ViscoLink.");
        cs.setFhirVersion("3.0.2");
        cs.addFormat("application/fhir+json");
        cs.addFormat("application/fhir+xml");

        var rest = cs.addRest();
        rest.setMode(org.hl7.fhir.dstu3.model.CapabilityStatement.RestfulCapabilityMode.SERVER);

        boolean hasTransaction = false;
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (FhirOperation op : ops) {
            if ("proxy".equals(op.operation()) || "metadata".equals(op.operation())) continue;
            if ("bundle-transaction".equals(op.operation())) { hasTransaction = true; continue; }
            byType.computeIfAbsent(op.resourceType(), k -> new ArrayList<>()).add(op.operation());
        }
        for (var entry : byType.entrySet()) {
            var res = rest.addResource();
            res.setType(entry.getKey());
            for (String opName : entry.getValue()) {
                res.addInteraction().setCode(toDstu3Interaction(opName));
            }
        }
        if (hasTransaction) {
            rest.addInteraction().setCode(
                    org.hl7.fhir.dstu3.model.CapabilityStatement.SystemRestfulInteraction.TRANSACTION);
        }
        return cs;
    }

    private static org.hl7.fhir.dstu3.model.CapabilityStatement.TypeRestfulInteraction toDstu3Interaction(
            String op) {
        return switch (op) {
            case "read"   -> org.hl7.fhir.dstu3.model.CapabilityStatement.TypeRestfulInteraction.READ;
            case "search" -> org.hl7.fhir.dstu3.model.CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE;
            case "create" -> org.hl7.fhir.dstu3.model.CapabilityStatement.TypeRestfulInteraction.CREATE;
            case "update" -> org.hl7.fhir.dstu3.model.CapabilityStatement.TypeRestfulInteraction.UPDATE;
            case "delete" -> org.hl7.fhir.dstu3.model.CapabilityStatement.TypeRestfulInteraction.DELETE;
            default       -> org.hl7.fhir.dstu3.model.CapabilityStatement.TypeRestfulInteraction.NULL;
        };
    }
}
