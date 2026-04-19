<?xml version="1.0" encoding="UTF-8"?>
<!--
    FHIR R5 Patient read transform.

    Context document : <id value="{id}"/> — the requested patient ID
    $fhirVersion     : "R5"
    $fhirOperation   : "patient-read"

    Default behaviour: returns a minimal stub Patient echoing the requested ID.

    Replace the content of the xsl:template below with a real lookup — for example
    an IbisLocalSender call to a database adapter — once you have a data source wired up.
-->
<xsl:stylesheet version="3.0"
    xmlns:xsl  ="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs   ="http://www.w3.org/2001/XMLSchema"
    exclude-result-prefixes="xs">

    <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

    <xsl:param name="fhirVersion"   as="xs:string?"/>
    <xsl:param name="fhirOperation" as="xs:string?"/>

    <xsl:template match="/id">
        <Patient xmlns="http://hl7.org/fhir">
            <id value="{@value}"/>
            <meta>
                <source value="fhir-to-fhir/patient-read-r5.xslt"/>
            </meta>
            <text>
                <status value="generated"/>
                <div xmlns="http://www.w3.org/1999/xhtml">Stub patient for id <xsl:value-of select="@value"/></div>
            </text>
        </Patient>
    </xsl:template>

</xsl:stylesheet>
