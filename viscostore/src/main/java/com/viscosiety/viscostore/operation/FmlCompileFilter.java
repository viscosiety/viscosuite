package com.viscosiety.viscostore.operation;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.annotations.OnR4Condition;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.UriParam;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext;
import org.hl7.fhir.r4.model.StructureMap;
import org.hl7.fhir.r4.utils.StructureMapUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Servlet filter that exposes POST /fhir/StructureMap/$compile for FML compilation.
 *
 * Accepts FML text (Content-Type: text/fhir-mapping), compiles it using
 * StructureMapUtilities.parse(), upserts the result into ViscoStore via the
 * StructureMap DAO, and returns the persisted StructureMap as FHIR XML (or
 * JSON if the caller sends Accept: application/fhir+json).
 *
 * The filter intercepts before HAPI processes the request — returning without
 * calling chain.doFilter() prevents HAPI from seeing the raw FML body.
 *
 * HTTP semantics:
 *   201 Created — StructureMap did not exist and was created.
 *   200 OK      — StructureMap already existed and was updated in-place.
 *   400 Bad Request — FML parse failure; error message in the response body.
 *   500 Internal Server Error — DAO failure; error message in the response body.
 *
 * Registration: CodificationConfig registers this filter with a FilterRegistrationBean
 * scoped to exactly /fhir/StructureMap/$compile so that it does not intercept
 * any other HAPI requests.
 */
@Component
@Conditional(OnR4Condition.class)
public class FmlCompileFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(FmlCompileFilter.class);

    private static final String FML_CONTENT_TYPE = "text/fhir-mapping";
    private static final String FHIR_XML          = "application/fhir+xml";
    private static final String FHIR_JSON         = "application/fhir+json";

    @Autowired
    private FhirContext myFhirContext;

    @Autowired
    private IValidationSupport myValidationSupport;

    @Autowired
    private DaoRegistry myDaoRegistry;

    // -------------------------------------------------------------------------
    // Filter lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to initialise
    }

    @Override
    public void destroy() {
        // nothing to clean up
    }

    // -------------------------------------------------------------------------
    // Request interception
    // -------------------------------------------------------------------------

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  httpReq = (HttpServletRequest)  request;
        HttpServletResponse httpRes = (HttpServletResponse) response;

        String contentType = httpReq.getContentType();
        if (!"POST".equalsIgnoreCase(httpReq.getMethod())
                || contentType == null
                || !contentType.startsWith(FML_CONTENT_TYPE)) {
            // Not a FML upload — pass through to HAPI.
            chain.doFilter(request, response);
            return;
        }

        handleCompile(httpReq, httpRes);
        // Intentionally do NOT call chain.doFilter() — HAPI never sees the request.
    }

    // -------------------------------------------------------------------------
    // Compile and upsert
    // -------------------------------------------------------------------------

    private void handleCompile(HttpServletRequest req, HttpServletResponse res)
            throws IOException {

        // 1. Read FML body
        String fml;
        try (InputStreamReader reader = new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8)) {
            fml = FileCopyUtils.copyToString(reader);
        }

        if (fml.isBlank()) {
            writeError(res, HttpServletResponse.SC_BAD_REQUEST, "Request body is empty");
            return;
        }

        // 2. Parse FML → StructureMap
        StructureMap structureMap;
        try {
            StructureMapUtilities utils = new StructureMapUtilities(
                    new HapiWorkerContext(myFhirContext, myValidationSupport), null);
            structureMap = utils.parse(fml, "fml-upload");
        } catch (Exception e) {
            log.warn("FML parse failed: {}", e.getMessage());
            writeError(res, HttpServletResponse.SC_BAD_REQUEST, "FML parse failed: " + e.getMessage());
            return;
        }

        String canonicalUrl = structureMap.getUrl();
        if (canonicalUrl == null || canonicalUrl.isBlank()) {
            writeError(res, HttpServletResponse.SC_BAD_REQUEST,
                    "FML map declaration must include a canonical URL: map \"https://...\" = \"Name\"");
            return;
        }

        // 3. Upsert into ViscoStore: create if new, update existing by canonical URL
        IFhirResourceDao<StructureMap> dao = myDaoRegistry.getResourceDao(StructureMap.class);
        SystemRequestDetails srd = new SystemRequestDetails();
        boolean wasCreated;
        try {
            StructureMap existing = findByUrl(dao, canonicalUrl, srd);
            if (existing != null) {
                // Carry the server-assigned ID so the DAO performs an update, not a create
                structureMap.setId(existing.getIdElement().getIdPart());
                dao.update(structureMap, srd);
                wasCreated = false;
                log.info("Updated StructureMap: {}", canonicalUrl);
            } else {
                structureMap.setId((String) null); // let the server assign a new ID
                dao.create(structureMap, srd);
                wasCreated = true;
                log.info("Created StructureMap: {}", canonicalUrl);
            }
        } catch (Exception e) {
            log.error("Failed to persist StructureMap {}: {}", canonicalUrl, e.getMessage(), e);
            writeError(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to persist StructureMap: " + e.getMessage());
            return;
        }

        // 4. Return the compiled StructureMap (XML by default, JSON if requested)
        String accept = req.getHeader("Accept");
        boolean wantsJson = accept != null && accept.contains(FHIR_JSON) && !accept.contains(FHIR_XML);
        String body;
        String contentTypeHeader;
        if (wantsJson) {
            body = myFhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(structureMap);
            contentTypeHeader = FHIR_JSON + "; charset=UTF-8";
        } else {
            body = myFhirContext.newXmlParser().setPrettyPrint(true).encodeResourceToString(structureMap);
            contentTypeHeader = FHIR_XML + "; charset=UTF-8";
        }

        res.setStatus(wasCreated ? HttpServletResponse.SC_CREATED : HttpServletResponse.SC_OK);
        res.setContentType(contentTypeHeader);
        try (PrintWriter writer = res.getWriter()) {
            writer.write(body);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private StructureMap findByUrl(IFhirResourceDao<StructureMap> dao,
                                   String canonicalUrl,
                                   SystemRequestDetails srd) {
        SearchParameterMap params = new SearchParameterMap();
        params.add(StructureMap.SP_URL, new UriParam(canonicalUrl));
        IBundleProvider results = dao.search(params, srd);
        List<?> found = results.getResources(0, 1);
        return found.isEmpty() ? null : (StructureMap) found.get(0);
    }

    private void writeError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("text/plain; charset=UTF-8");
        try (PrintWriter writer = res.getWriter()) {
            writer.write(message);
        }
    }
}
