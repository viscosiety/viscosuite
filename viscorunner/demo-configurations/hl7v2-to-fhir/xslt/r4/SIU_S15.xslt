<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:hl7="urn:hl7-org:v2xml"
                xmlns:fn="urn:hl7v2-fhir-functions"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                exclude-result-prefixes="hl7 fn xs">

    <!--
      SIU_S15.xslt
      ============
      Maps an HL7v2 SIU^S15 (Appointment Cancellation) message
      to a FHIR R4 transaction Bundle containing:
        - Patient     (from PID), conditional PUT on MR identifier (idempotent upsert)
        - Appointment (from SCH), conditional PUT on filler/placer appointment ID, status=cancelled

      Imports: hl7v2-fhir-functions.xslt
    -->

    <xsl:import href="hl7v2-fhir-functions.xslt"/>

    <xsl:param name="transactionUuid" as="xs:string"/>
    <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

    <xsl:template match="/hl7:SIU_S12">

        <xsl:variable name="messageId"      select="(hl7:MSH/hl7:MSH.10, '')[1]"/>
        <xsl:variable name="patientId"      select="(hl7:SIU_S12.PATIENT/hl7:PID/hl7:PID.3[hl7:CX.5='MR'][1]/hl7:CX.1, '')[1]"/>
        <xsl:variable name="mrSystem"       select="if (hl7:SIU_S12.PATIENT/hl7:PID/hl7:PID.3[hl7:CX.5='MR'][1]/hl7:CX.4/hl7:HD.2)
                                                    then concat('urn:oid:', hl7:SIU_S12.PATIENT/hl7:PID/hl7:PID.3[hl7:CX.5='MR'][1]/hl7:CX.4/hl7:HD.2)
                                                    else concat($mrSystemBase, hl7:SIU_S12.PATIENT/hl7:PID/hl7:PID.3[hl7:CX.5='MR'][1]/hl7:CX.4/hl7:HD.1)"/>
        <xsl:variable name="appointmentId"  select="(hl7:SCH/hl7:SCH.2/hl7:EI.1, hl7:SCH/hl7:SCH.1/hl7:EI.1, '')[1]"/>

        <xsl:variable name="patientFullUrl"
                      select="concat('urn:uuid:', fn:derivePlaceholderUuid($transactionUuid, 'Patient', $patientId))"/>

        <xsl:variable name="appointmentFullUrl"
                      select="concat('urn:uuid:', fn:derivePlaceholderUuid($transactionUuid, 'Appointment', $appointmentId))"/>

        <Bundle xmlns="http://hl7.org/fhir">
            <id value="{$messageId}"/>
            <type value="transaction"/>

            <!-- Patient (idempotent upsert) -->
            <entry>
                <fullUrl value="{$patientFullUrl}"/>
                <resource>
                    <xsl:apply-templates select="hl7:SIU_S12.PATIENT/hl7:PID" mode="Patient">
                        <xsl:with-param name="patientId" select="$patientId"/>
                        <xsl:with-param name="nk1Segments" select="hl7:SIU_S12.PATIENT/hl7:NK1"/>
                    </xsl:apply-templates>
                </resource>
                <request>
                    <method value="PUT"/>
                    <url value="Patient?identifier={$mrSystem}|{$patientId}"/>
                </request>
            </entry>

            <!-- Appointment (cancelled) -->
            <entry>
                <fullUrl value="{$appointmentFullUrl}"/>
                <resource>
                    <xsl:apply-templates select="hl7:SCH" mode="Appointment">
                        <xsl:with-param name="appointmentId"    select="$appointmentId"/>
                        <xsl:with-param name="status"           select="'cancelled'"/>
                        <xsl:with-param name="patientReference" select="$patientFullUrl"/>
                        <xsl:with-param name="siuRoot"          select="."/>
                    </xsl:apply-templates>
                </resource>
                <request>
                    <method value="PUT"/>
                    <url value="Appointment?identifier={$appointmentId}"/>
                </request>
            </entry>

        </Bundle>
    </xsl:template>

</xsl:stylesheet>
