<?xml version="1.0" encoding="UTF-8"?>
<!--
    Tags a FHIR R4 resource into the ViscoSuite inbound zone: adds the data-zone
    tag and meta.source, preserving any meta content the sender supplied
    (nl-core senders carry meta.profile — that stays).

    The inbound zone stores source data unchanged and 1-to-1 traceable; this
    stylesheet adds only metadata, never touches the clinical content.

    Param: source — logical id of the ingesting endpoint (meta.source).
-->
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:f="http://hl7.org/fhir" xmlns="http://hl7.org/fhir">
    <xsl:output method="xml" encoding="UTF-8" omit-xml-declaration="yes" indent="yes"/>

    <xsl:param name="source" select="'urn:viscosuite:nl-core-intake'"/>

    <!-- identity by default -->
    <xsl:template match="@*|node()">
        <xsl:copy><xsl:apply-templates select="@*|node()"/></xsl:copy>
    </xsl:template>

    <!-- root resource: emit meta (merged) in its correct position, then the rest -->
    <xsl:template match="/f:*">
        <xsl:copy>
            <xsl:apply-templates select="@*"/>
            <xsl:apply-templates select="f:id"/>
            <!-- meta children in R4 canonical order: versionId, lastUpdated, source,
                 profile, security, tag. The ingest owns meta.source (it names where
                 WE got the resource); a sender-supplied source is replaced. -->
            <meta>
                <xsl:apply-templates select="f:meta/f:versionId"/>
                <xsl:apply-templates select="f:meta/f:lastUpdated"/>
                <source value="{$source}"/>
                <xsl:apply-templates select="f:meta/f:profile"/>
                <xsl:apply-templates select="f:meta/f:security"/>
                <xsl:apply-templates select="f:meta/f:tag"/>
                <tag>
                    <system value="https://ig.viscosiety.com/CodeSystem/data-zone"/>
                    <code value="inbound"/>
                    <display value="Inbound Zone"/>
                </tag>
            </meta>
            <xsl:apply-templates select="node()[not(self::f:id) and not(self::f:meta)]"/>
        </xsl:copy>
    </xsl:template>

</xsl:stylesheet>
