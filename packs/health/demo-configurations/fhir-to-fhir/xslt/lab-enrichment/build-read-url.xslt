<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema">

    <!--
      build-read-url.xslt
      ===================
      Converts an <id value="…"/> document (produced by FhirReadProvider) into a
      viscostore search URL that retrieves the single Observation together with its
      Specimen so that enrich-loinc.xslt can resolve the specimen type.

      A _id search is used instead of a direct read so that _include=Observation:specimen
      can be appended; FHIR does not support _include on read-by-id.

      Input:
        <id value="ccd84811-f4e8-4c12-8ec3-ef562e3eb574"/>

      Params:
        $viscoBaseUrl  — FHIR base URL of viscostore, ending with '/'

      Output (plain text):
        http://localhost:8080/viscostore/fhir/Observation
            ?_id=ccd84811-…&_format=xml&_include=Observation%3Aspecimen
    -->

    <xsl:output method="text" encoding="UTF-8"/>

    <xsl:param name="viscoBaseUrl" as="xs:string" select="''"/>

    <xsl:template match="/id">
        <xsl:value-of select="concat(
            $viscoBaseUrl, 'Observation',
            '?_id=',       encode-for-uri(@value),
            '&amp;_format=xml',
            '&amp;_include=Observation%3Aspecimen'
        )"/>
    </xsl:template>

</xsl:stylesheet>
