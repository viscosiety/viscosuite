<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:hl7="urn:hl7-org:v2xml"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                exclude-result-prefixes="hl7 xs">

    <!--
        HL7v2-ACK.xslt
        ==============
        Produces a HAPI-format HL7v2 XML ACK in response to any inbound HL7v2
        message in urn:hl7-org:v2xml format.

        Input:  any HL7v2 message root element (e.g. ADT_A01, ORU_R01, ...)
        Output: <ACK xmlns="urn:hl7-org:v2xml"> with MSH + MSA

        Parameters:
        ackCode             MSA.1 value: AA (accept), AE (error), AR (reject).
        errorMessage        Optional free-text placed in MSA.3. Omit for AA.
        sendingApplication  MSH.3 of the ACK (our application identity).
        sendingFacility     MSH.4 of the ACK (our facility identity).

        MSH sender/receiver are swapped so the ACK is addressed back to
        the originating system. MSA.2 echoes the original message control ID.
    -->

    <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

    <xsl:param name="ackCode"            as="xs:string?" select="'AA'"/>
    <xsl:param name="errorMessage"       as="xs:string?" select="''"/>
    <xsl:param name="sendingApplication" as="xs:string?" select="'viscolink'"/>
    <xsl:param name="sendingFacility"    as="xs:string?" select="'viscosiety'"/>

    <xsl:template match="/*">
        <xsl:variable name="msh"          select="hl7:MSH"/>
        <xsl:variable name="triggerEvent" select="($msh/hl7:MSH.9/hl7:MSG.2, '')[1]"/>
        <xsl:variable name="controlId"    select="string($msh/hl7:MSH.10)"/>
        <xsl:variable name="procId"       select="($msh/hl7:MSH.11/hl7:PT.1, 'P')[1]"/>
        <xsl:variable name="version"      select="($msh/hl7:MSH.12/hl7:VID.1, '2.5')[1]"/>
        <xsl:variable name="ts"           select="format-dateTime(current-dateTime(), '[Y0001][M01][D01][H01][m01][s01]')"/>

        <ACK xmlns="urn:hl7-org:v2xml">
            <MSH>
                <MSH.1>|</MSH.1>
                <MSH.2>^~\&amp;</MSH.2>
                <MSH.3><HD.1><xsl:value-of select="$sendingApplication"/></HD.1></MSH.3>
                <MSH.4><HD.1><xsl:value-of select="$sendingFacility"/></HD.1></MSH.4>
                <!-- Swap: original MSH.3/4 (sender) becomes ACK MSH.5/6 (receiver) -->
                <MSH.5><xsl:copy-of select="$msh/hl7:MSH.3/node()"/></MSH.5>
                <MSH.6><xsl:copy-of select="$msh/hl7:MSH.4/node()"/></MSH.6>
                <MSH.7><TS.1><xsl:value-of select="$ts"/></TS.1></MSH.7>
                <MSH.9>
                    <MSG.1>ACK</MSG.1>
                    <MSG.2><xsl:value-of select="$triggerEvent"/></MSG.2>
                    <MSG.3>ACK</MSG.3>
                </MSH.9>
                <MSH.10><xsl:value-of select="concat('ACK', $ts)"/></MSH.10>
                <MSH.11><PT.1><xsl:value-of select="$procId"/></PT.1></MSH.11>
                <MSH.12><VID.1><xsl:value-of select="$version"/></VID.1></MSH.12>
            </MSH>
            <MSA>
                <MSA.1><xsl:value-of select="$ackCode"/></MSA.1>
                <MSA.2><xsl:value-of select="$controlId"/></MSA.2>
                <xsl:if test="string-length($errorMessage) gt 0">
                    <MSA.3><xsl:value-of select="$errorMessage"/></MSA.3>
                </xsl:if>
            </MSA>
        </ACK>
    </xsl:template>

</xsl:stylesheet>
