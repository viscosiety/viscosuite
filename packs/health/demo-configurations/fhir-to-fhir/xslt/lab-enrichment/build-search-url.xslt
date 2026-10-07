<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema">

    <!--
      build-search-url.xslt
      =====================
      Converts a <searchParams> document — produced by FhirSearchProvider — to a
      complete URL for the viscostore FHIR search endpoint, with all parameter
      names and values percent-encoded.

      Input:
        <searchParams>
          <param name="patient"  value="Patient/123"/>
          <param name="_include" value="Observation:specimen"/>
          <param name="_tag"     value="http://terminology.viscosiety.com/fixture|tag"/>
        </searchParams>

      Params:
        $viscoBaseUrl  — FHIR base URL of viscostore, ending with '/'
                         (e.g. "http://localhost:8080/viscostore/fhir/")
        $resourceType  — FHIR resource to search (default: "Observation")

      Output:
        Plain text URL ready for use by HttpSender, e.g.:
        http://localhost:8080/viscostore/fhir/Observation?patient=Patient%2F123&_include=...&_format=xml

      Note: _format=xml is always appended so viscostore returns FHIR XML regardless of
      any Accept header negotiation — required because enrich-loinc.xslt expects XML input.
    -->

    <xsl:output method="text" encoding="UTF-8"/>

    <xsl:param name="viscoBaseUrl"  as="xs:string" select="''"/>
    <xsl:param name="resourceType"  as="xs:string" select="'Observation'"/>

    <xsl:template match="/searchParams">
        <!--
            _format is excluded by FhirSearchProvider before building this document,
            so no filtering is needed here.

            Two parameters are always appended to every CDR search URL:
              _format=xml              — forces XML so enrich-loinc.xslt can process the response
              _include=Observation:specimen — ensures Specimen resources are always present in
                                         the Bundle so enrich-loinc.xslt can resolve specimen
                                         types for LOINC mapping, even when the caller did not
                                         explicitly request the _include.

            If the caller also sent _include=Observation:specimen it will appear twice in the
            URL; HAPI FHIR deduplicates _include values server-side, so this is harmless.

            Format conversion for the caller is handled in FhirSearchProvider after
            the enriched XML is returned from the pipeline.
        -->
        <xsl:variable name="queryParts" as="xs:string*"
                      select="for $p in param
                              return concat(encode-for-uri($p/@name), '=',
                                           encode-for-uri($p/@value))"/>
        <xsl:value-of select="concat(
            $viscoBaseUrl, $resourceType, '?',
            string-join(($queryParts, '_format=xml', '_include=Observation%3Aspecimen'), '&amp;')
        )"/>
    </xsl:template>

</xsl:stylesheet>
