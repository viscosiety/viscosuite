<?xml version="1.0" encoding="UTF-8"?>
<!--
    FHIR R5 Bundle transaction transform.

    Context document : a FHIR R5 Bundle serialised as XML (xmlns="http://hl7.org/fhir")
    $fhirVersion     : "R5" (injected by FhirOperationListener)
    $fhirOperation   : "bundle-transaction" (injected by FhirOperationListener)

    Default behaviour: identity transform — the Bundle passes through unchanged.

    To rewrite specific resource types, add templates that match on fhir: elements.
    Example — rewrite every Patient.identifier system value:

        <xsl:template match="fhir:Patient/fhir:identifier/fhir:system">
            <system xmlns="http://hl7.org/fhir"
                    value="urn:myorg:patid"/>
        </xsl:template>

    The fhir: prefix is declared on the stylesheet root below; use it in all
    match/select expressions to avoid the default-namespace ambiguity that exists
    in XSLT instruction attributes.
-->
<xsl:stylesheet version="3.0"
    xmlns:xsl  ="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs   ="http://www.w3.org/2001/XMLSchema"
    xmlns:fhir ="http://hl7.org/fhir"
    exclude-result-prefixes="xs fhir">

    <xsl:output method="xml" indent="no" encoding="UTF-8"/>

    <xsl:param name="fhirVersion"   as="xs:string?"/>
    <xsl:param name="fhirOperation" as="xs:string?"/>

    <!-- Identity transform: copy every node unchanged.
         Override individual templates below to rewrite specific elements. -->
    <xsl:mode on-no-match="shallow-copy"/>

</xsl:stylesheet>
