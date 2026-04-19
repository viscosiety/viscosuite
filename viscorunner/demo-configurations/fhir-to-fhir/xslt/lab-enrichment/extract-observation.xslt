<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:fhir="http://hl7.org/fhir"
                exclude-result-prefixes="fhir">

    <!--
      extract-observation.xslt
      ========================
      Extracts the first Observation resource from a FHIR searchset Bundle and
      returns it as a standalone XML document.

      Used as the final step of the LabEnrichmentObservationRead pipeline: after
      enrich-loinc.xslt has processed the Bundle produced by a _id+_include search,
      this stylesheet peels off the Bundle wrapper so that FhirReadProvider receives
      a plain Observation document, as its contract requires.

      Input:   FHIR R4 Bundle XML (searchset, typically one Observation entry plus
               zero or more _include-d Specimen entries)
      Output:  The first fhir:Observation element as a standalone document
    -->

    <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

    <xsl:template match="/">
        <xsl:copy-of select="/fhir:Bundle/fhir:entry/fhir:resource/fhir:Observation[1]"/>
    </xsl:template>

</xsl:stylesheet>
