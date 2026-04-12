<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:hl7="urn:hl7-org:v2xml"
                xmlns:fn="urn:hl7v2-fhir-functions"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                exclude-result-prefixes="hl7 fn xs">

    <!--
      ADT_A02.xslt
      ============
      Maps an HL7v2 ADT^A02 (Transfer a Patient) message to a FHIR R4
      transaction Bundle containing:
        - Patient         (from PID)  — conditional PUT on MR identifier
        - Encounter       (from PV1)  — conditional PUT on visit identifier
                                        PV1.3 current location → status active
                                        (PV1.6 prior location omitted; PUT replaces the resource)

      XML root element: ADT_A02  (A02 has its own message structure in HL7 2.5)
    -->

    <xsl:import href="hl7v2-fhir-functions.xslt"/>

    <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

    <xsl:template match="/hl7:ADT_A02">

        <xsl:variable name="messageId" select="(hl7:MSH/hl7:MSH.10, '')[1]"/>
        <xsl:variable name="patientId" select="(hl7:PID/hl7:PID.3/hl7:CX.1, '')[1]"/>
        <xsl:variable name="visitId"   select="(hl7:PV1/hl7:PV1.19/hl7:CX.1, '')[1]"/>

        <Bundle xmlns="http://hl7.org/fhir">
            <id value="{$messageId}"/>
            <type value="transaction"/>

            <!-- Patient -->
            <entry>
                <fullUrl value="urn:uuid:patient-{$patientId}"/>
                <resource>
                    <xsl:apply-templates select="hl7:PID" mode="Patient">
                        <xsl:with-param name="patientId" select="$patientId"/>
                    </xsl:apply-templates>
                </resource>
                <request>
                    <method value="PUT"/>
                    <url value="Patient?identifier=urn:oid:2.16.840.1.113883.2.4.6.3|{$patientId}"/>
                </request>
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
                <request>
                    <method value="PUT"/>
                    <url value="Encounter?identifier={$visitId}"/>
                </request>
            </entry>

        </Bundle>
    </xsl:template>

    <xsl:template match="hl7:PV1" mode="Encounter">
        <xsl:param name="visitId"   as="xs:string?"/>
        <xsl:param name="patientId" as="xs:string?"/>

        <Encounter xmlns="http://hl7.org/fhir">
            <identifier>
                <use value="official"/>
                <value value="{$visitId}"/>
            </identifier>

            <!-- Patient is still admitted after transfer -->
            <status value="in-progress"/>

            <class>
                <system value="http://terminology.hl7.org/CodeSystem/v3-ActCode"/>
                <code   value="{fn:toFhirEncounterClass(hl7:PV1.2)}"/>
            </class>

            <subject>
                <reference value="urn:uuid:patient-{$patientId}"/>
            </subject>

            <!-- Admit date/time preserved from original A01 (PV1.44) -->
            <xsl:if test="hl7:PV1.44/hl7:TS.1">
                <period>
                    <start value="{fn:toFhirDateTime(hl7:PV1.44/hl7:TS.1)}"/>
                </period>
            </xsl:if>

            <!-- Current location (PV1.3) — where the patient is now after transfer.
                 Prior location (PV1.6) is intentionally omitted: this is a full
                 conditional PUT that replaces the resource, so adding the prior
                 location here would just accumulate stale entries on each transfer. -->
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

        </Encounter>
    </xsl:template>

</xsl:stylesheet>
