<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:hl7="urn:hl7-org:v2xml"
                exclude-result-prefixes="hl7">

    <!--
        extract-department.xslt
        =======================
        Extracts the ward/department code from PV1.3 (Assigned Patient Location) / PL.1.
        Output is plain text, suitable for storing in a session key and comparing with IfPipe.
        Returns an empty string when PV1.3 is absent.
    -->

    <xsl:output method="text"/>

    <xsl:template match="/*">
        <xsl:value-of select="(hl7:PV1/hl7:PV1.3/hl7:PL.1, '')[1]"/>
    </xsl:template>

</xsl:stylesheet>
