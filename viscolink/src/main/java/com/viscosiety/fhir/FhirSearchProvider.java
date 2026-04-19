package com.viscosiety.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.util.BundleUtil;
import org.frankframework.core.ListenerException;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.List;
import java.util.Map;

/**
 * Generic HAPI FHIR {@code @Search} provider backed by a Frank!Framework {@link FhirListener}.
 *
 * <p>Handles {@code GET /fhir/{version}/{facadeName}/{Resource}} with arbitrary search parameters.
 * All HAPI search parameters are serialised to a {@code <searchParams>} XML document and
 * forwarded to the F!F pipeline.  The pipeline always queries the upstream FHIR store and
 * enriches the result using XML internally, regardless of the requested output format.</p>
 *
 * <h3>Format handling</h3>
 * <p>{@code _format} is stripped from the {@code <searchParams>} document before it is sent
 * to the pipeline: the F!F pipeline always communicates with the CDR in XML (enforced by
 * appending {@code _format=xml} to the CDR search URL) so that the XSLT enrichment step can
 * process the response.  After the enriched XML Bundle is returned from the pipeline it is
 * parsed and, if the caller requested JSON, re-serialised to JSON before being handed back to
 * HAPI.  HAPI then wraps the resources in a searchset Bundle in the caller's chosen format.</p>
 *
 * <h3>Message contract</h3>
 * <pre>
 * Input to the F!F pipeline ({@code _format} excluded):
 *   &lt;searchParams&gt;
 *     &lt;param name="patient"   value="Patient/123"/&gt;
 *     &lt;param name="_include"  value="Observation:specimen"/&gt;
 *     &lt;param name="_tag"      value="http://example.org/fixture|myTag"/&gt;
 *   &lt;/searchParams&gt;
 *
 * Expected output from the F!F pipeline:
 *   FHIR Bundle XML (searchset) whose entries contain the enriched resources.
 * </pre>
 */
public class FhirSearchProvider<T extends IBaseResource> extends AbstractBridgeProvider {

    private final Class<T> resourceClass;

    @SuppressWarnings("unchecked")
    private final Class<? extends IBaseResource> bundleClass;

    public FhirSearchProvider(FhirFfBridge bridge, FhirContext fhirContext,
                              FhirOperation operation, Class<T> resourceClass) {
        super(bridge, fhirContext, operation);
        this.resourceClass = resourceClass;
        this.bundleClass = (Class<? extends IBaseResource>)
                fhirContext.getResourceDefinition("Bundle").getImplementingClass();
    }

    @Override
    public Class<T> getResourceType() {
        return resourceClass;
    }

    @Search(allowUnknownParams = true)
    public List<IBaseResource> search(RequestDetails requestDetails) {
        Map<String, String[]> params = requestDetails.getParameters();

        // Detect the caller's preferred format before stripping _format from the params.
        // The pipeline always uses XML internally; we convert here if JSON was requested.
        boolean wantsJson = isJsonRequested(params);

        String searchParamsXml = buildSearchParamsXml(params);
        try {
            // Pipeline always returns enriched FHIR XML.
            String responseXml = callBridge(searchParamsXml);

            IBaseBundle bundle = wantsJson
                    ? (IBaseBundle) fromJson(responseXml, bundleClass)
                    : (IBaseBundle) fromXml(responseXml, bundleClass);

            return BundleUtil.toListOfResources(fhirContext, bundle);
        } catch (ListenerException e) {
            throw new InternalErrorException(
                    "Pipeline error processing " + getOperation(), e);
        }
    }

    /**
     * Returns {@code true} when the caller's {@code _format} parameter indicates a JSON
     * preference.  Matches the FHIR shorthand {@code "json"} as well as the full MIME type
     * {@code "application/fhir+json"}.  Case-insensitive.
     */
    private static boolean isJsonRequested(Map<String, String[]> params) {
        String[] fmt = params.get("_format");
        if (fmt == null || fmt.length == 0) return false;
        String v = fmt[0].toLowerCase();
        return v.equals("json") || v.contains("fhir+json");
    }

    /**
     * Serialises the HAPI search parameter map to a {@code <searchParams>} XML document.
     * {@code _format} is excluded: the pipeline is always XML-internal, and format
     * conversion is performed here after the pipeline response is received.
     * Multiple values for the same parameter name (e.g. repeated {@code _include}) produce
     * multiple {@code <param>} elements with the same {@code name} attribute.
     */
    private static String buildSearchParamsXml(Map<String, String[]> parameters) {
        StringBuilder sb = new StringBuilder("<searchParams>");
        for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("_format")) continue;
            String name = escapeXml(entry.getKey());
            for (String value : entry.getValue()) {
                sb.append("<param name=\"").append(name)
                  .append("\" value=\"").append(escapeXml(value))
                  .append("\"/>");
            }
        }
        sb.append("</searchParams>");
        return sb.toString();
    }

    /** Deserialise a JSON string back to a typed FHIR resource. */
    private <R extends IBaseResource> R fromJson(String json, Class<R> type) {
        return fhirContext.newJsonParser().parseResource(type, json);
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
