<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:hl7="urn:hl7-org:v2xml"
                xmlns:fn="urn:hl7v2-fhir-functions"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                exclude-result-prefixes="hl7 fn xs">

    <!--
      ADT_A08.xslt
      ============
      Maps an HL7v2 ADT^A08 (Update Patient Information) message to a FHIR R4
      transaction Bundle containing:
        - Patient only    (from PID)  — conditional PUT on MR identifier

      No Encounter entry: A08 signals a change to the patient's demographic
      record, not to the visit itself.

      XML root element: ADT_A01  (A08 shares the ADT_A01 message structure in HL7 2.5)
      Routing: pipeline routes to ADT_A08.xslt via MSG.2 = A08.
    -->

    <xsl:import href="hl7v2-fhir-functions.xslt"/>

    <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

    <xsl:template match="/hl7:ADT_A01">

        <xsl:variable name="messageId" select="(hl7:MSH/hl7:MSH.10, '')[1]"/>
        <xsl:variable name="patientId" select="(hl7:PID/hl7:PID.3[hl7:CX.5='MR'][1]/hl7:CX.1, '')[1]"/>
        <xsl:variable name="mrSystem"  select="if (hl7:PID/hl7:PID.3[hl7:CX.5='MR'][1]/hl7:CX.4/hl7:HD.2)
                                               then concat('urn:oid:', hl7:PID/hl7:PID.3[hl7:CX.5='MR'][1]/hl7:CX.4/hl7:HD.2)
                                               else concat($mrSystemBase, hl7:PID/hl7:PID.3[hl7:CX.5='MR'][1]/hl7:CX.4/hl7:HD.1)"/>

        <Bundle xmlns="http://hl7.org/fhir">
            <id value="{$messageId}"/>
            <type value="transaction"/>

            <!-- Patient only — demographics update, no Encounter change -->
            <entry>
                <fullUrl value="urn:uuid:patient-{$patientId}"/>
                <resource>
                    <xsl:apply-templates select="hl7:PID" mode="Patient">
                        <xsl:with-param name="patientId" select="$patientId"/>
                    </xsl:apply-templates>
                </resource>
                <request>
                    <method value="PUT"/>
                    <url value="Patient?identifier={$mrSystem}|{$patientId}"/>
                </request>
            </entry>

        </Bundle>
    </xsl:template>

</xsl:stylesheet>
