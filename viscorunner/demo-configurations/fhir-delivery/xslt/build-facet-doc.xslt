<?xml version="1.0" encoding="UTF-8"?>
<!--
    Builds the facet document an operator console can index: business keys extracted
    from the incoming FHIR bundle (study tag, patient MRN), joined to the message by
    the intake's message/correlation ids.

    Input:  FHIR XML bundle (a JSON submission skips facet publication — the intake
            forwards the XsltPipe's exception to the queue, facets are best-effort).
    Params: mid / cid — the intake session's message and correlation ids.
    Output: one JSON document (element content, so quotes are literal):
            {"flow":...,"messageId":...,"correlationId":...,"facets":{...}}
-->
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:f="http://hl7.org/fhir"
                xmlns:vf="urn:visco:xslt-functions">
    <xsl:output method="text" encoding="UTF-8"/>

    <xsl:param name="mid"/>
    <xsl:param name="cid"/>

    <!-- demo values are plain ids; strip the two JSON-breaking characters as a guard -->
    <xsl:function name="vf:safe" as="xs:string" xmlns:xs="http://www.w3.org/2001/XMLSchema">
        <xsl:param name="value"/>
        <xsl:sequence select="translate(string($value), '&quot;\', '')"/>
    </xsl:function>

    <xsl:template match="/">
        <xsl:variable name="study" select="(/f:Bundle/f:meta/f:tag[f:system/@value='urn:visco:study']/f:code/@value)[1]"/>
        <xsl:variable name="patient" select="(//f:identifier[f:system/@value='urn:visco:mrn']/f:value/@value)[1]"/>
        <xsl:text>{"flow":"FhirAsyncIntake"</xsl:text>
        <xsl:text>,"messageId":"</xsl:text><xsl:value-of select="vf:safe($mid)"/><xsl:text>"</xsl:text>
        <xsl:text>,"correlationId":"</xsl:text><xsl:value-of select="vf:safe($cid)"/><xsl:text>"</xsl:text>
        <xsl:text>,"facets":{</xsl:text>
        <xsl:if test="$study">
            <xsl:text>"study":"</xsl:text><xsl:value-of select="vf:safe($study)"/><xsl:text>"</xsl:text>
        </xsl:if>
        <xsl:if test="$study and $patient"><xsl:text>,</xsl:text></xsl:if>
        <xsl:if test="$patient">
            <xsl:text>"patient":"</xsl:text><xsl:value-of select="vf:safe($patient)"/><xsl:text>"</xsl:text>
        </xsl:if>
        <xsl:text>}}</xsl:text>
    </xsl:template>

</xsl:stylesheet>
