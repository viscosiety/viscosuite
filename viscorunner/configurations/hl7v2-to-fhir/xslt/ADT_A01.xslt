<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:hl7="urn:hl7-org:v2xml"
                xmlns:fn="urn:hl7v2-fhir-functions"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                exclude-result-prefixes="hl7 fn xs">

    <!--
      ADT_A01.xslt
      ============
      Maps an HL7v2 ADT^A01 (Admit/Visit Notification) message
      to a FHIR R4 Bundle containing:
        - MessageHeader
        - Patient         (from PID)
        - Encounter       (from PV1, patient class = inpatient)

      Imports: hl7v2-fhir-functions.xslt
    -->

    <xsl:import href="hl7v2-fhir-functions.xslt"/>

    <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

    <!-- ============================================================
         Root template
         ============================================================ -->
    <xsl:template match="/hl7:ADT_A01">

        <xsl:variable name="messageId" select="hl7:MSH/hl7:MSH.10"/>
        <xsl:variable name="patientId" select="hl7:PID/hl7:PID.3/hl7:CX.1"/>
        <xsl:variable name="visitId"   select="hl7:PV1/hl7:PV1.19/hl7:CX.1"/>

        <Bundle xmlns="http://hl7.org/fhir">
            <id value="{$messageId}"/>
            <type value="message"/>
            <timestamp value="{fn:toFhirDateTime(hl7:MSH/hl7:MSH.7/hl7:TS.1)}"/>

            <!-- MessageHeader -->
            <entry>
                <fullUrl value="urn:uuid:messageheader-{$messageId}"/>
                <resource>
                    <xsl:apply-templates select="hl7:MSH" mode="MessageHeader">
                        <xsl:with-param name="eventCode"    select="'A01'"/>
                        <xsl:with-param name="eventDisplay" select="'ADT/ACK - Admit/visit notification'"/>
                        <xsl:with-param name="focusRef"     select="concat('urn:uuid:encounter-', $visitId)"/>
                    </xsl:apply-templates>
                </resource>
            </entry>

            <!-- Patient -->
            <entry>
                <fullUrl value="urn:uuid:patient-{$patientId}"/>
                <resource>
                    <xsl:apply-templates select="hl7:PID" mode="Patient">
                        <xsl:with-param name="patientId" select="$patientId"/>
                    </xsl:apply-templates>
                </resource>
            </entry>

            <!-- Encounter -->
            <entry>
                <fullUrl value="urn:uuid:encounter-{$visitId}"/>
                <resource>
                    <xsl:apply-templates select="hl7:PV1" mode="Encounter">
                        <xsl:with-param name="visitId"   select="$visitId"/>
                        <xsl:with-param name="patientId" select="$patientId"/>
                    </xsl:apply-templates>
                </resource>
            </entry>

        </Bundle>
    </xsl:template>

    <!-- ============================================================
         Encounter — specific to ADT_A01 (admission)
         Defined here (not in shared) because the Encounter shape
         differs meaningfully between ADT event types:
           A01 → status=in-progress, no period.end
           A03 → status=finished,    period.end from PV1.45
           A08 → no Encounter at all
         ============================================================ -->
    <xsl:template match="hl7:PV1" mode="Encounter">
        <xsl:param name="visitId"   as="xs:string"/>
        <xsl:param name="patientId" as="xs:string"/>

        <Encounter xmlns="http://hl7.org/fhir">
            <id value="encounter-{$visitId}"/>

            <identifier>
                <use value="official"/>
                <value value="{$visitId}"/>
            </identifier>

            <!-- A01 = patient just arrived, encounter is active -->
            <status value="in-progress"/>

            <class>
                <system value="http://terminology.hl7.org/CodeSystem/v3-ActCode"/>
                <code   value="{fn:toFhirEncounterClass(hl7:PV1.2)}"/>
            </class>

            <subject>
                <reference value="urn:uuid:patient-{$patientId}"/>
            </subject>

            <!-- Admit date/time from PV1.44 -->
            <xsl:if test="hl7:PV1.44/hl7:TS.1">
                <period>
                    <start value="{fn:toFhirDateTime(hl7:PV1.44/hl7:TS.1)}"/>
                    <!-- No period.end on admission — that comes with ADT_A03 -->
                </period>
            </xsl:if>

            <!-- Ward / room / bed from PV1.3 -->
            <xsl:if test="hl7:PV1.3">
                <location>
                    <location>
                        <display value="{hl7:PV1.3/hl7:PL.1} / {hl7:PV1.3/hl7:PL.2} / {hl7:PV1.3/hl7:PL.3}"/>
                    </location>
                    <status value="active"/>
                </location>
            </xsl:if>

            <!-- Attending physician from PV1.7 -->
            <xsl:if test="hl7:PV1.7">
                <participant>
                    <type>
                        <coding>
                            <system value="http://terminology.hl7.org/CodeSystem/v3-ParticipationType"/>
                            <code value="ATND"/>
                            <display value="attender"/>
                        </coding>
                    </type>
                    <individual>
                        <display value="{hl7:PV1.7/hl7:XCN.3} {hl7:PV1.7/hl7:XCN.2/hl7:FN.1}"/>
                    </individual>
                </participant>
            </xsl:if>

            <!-- Referring physician from PV1.8 -->
            <xsl:if test="hl7:PV1.8">
                <participant>
                    <type>
                        <coding>
                            <system value="http://terminology.hl7.org/CodeSystem/v3-ParticipationType"/>
                            <code value="REF"/>
                            <display value="referrer"/>
                        </coding>
                    </type>
                    <individual>
                        <display value="{hl7:PV1.8/hl7:XCN.3} {hl7:PV1.8/hl7:XCN.2/hl7:FN.1}"/>
                    </individual>
                </participant>
            </xsl:if>

            <!-- Admission type from PV1.4 -->
            <xsl:if test="hl7:PV1.4">
                <type>
                    <coding>
                        <system value="http://terminology.hl7.org/CodeSystem/v2-0007"/>
                        <code   value="{hl7:PV1.4}"/>
                    </coding>
                </type>
            </xsl:if>

        </Encounter>
    </xsl:template>

</xsl:stylesheet>
