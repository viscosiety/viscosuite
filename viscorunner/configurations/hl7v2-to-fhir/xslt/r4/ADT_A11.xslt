<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:hl7="urn:hl7-org:v2xml"
                xmlns:fn="urn:hl7v2-fhir-functions"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                exclude-result-prefixes="hl7 fn xs">

    <!--
      ADT_A11.xslt
      ============
      Maps an HL7v2 ADT^A11 (Cancel Admit/Visit Notification) message to a
      FHIR R4 transaction Bundle containing:
        - Encounter only  (from PV1)  — conditional PUT on visit identifier
                                        status=cancelled

      No Patient update: a cancellation does not change demographics.

      XML root element: ADT_A09  (A11 uses the ADT_A09 message structure in HL7 2.5,
                                   shared with A09/A10/A12)
      Routing: pipeline routes to ADT_A11.xslt via MSG.2 = A11.
    -->

    <xsl:import href="hl7v2-fhir-functions.xslt"/>

    <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

    <xsl:template match="/hl7:ADT_A09">

        <xsl:variable name="messageId" select="(hl7:MSH/hl7:MSH.10, '')[1]"/>
        <xsl:variable name="patientId" select="(hl7:PID/hl7:PID.3[hl7:CX.5='MR'][1]/hl7:CX.1, '')[1]"/>
        <xsl:variable name="mrSystem"  select="if (hl7:PID/hl7:PID.3[hl7:CX.5='MR'][1]/hl7:CX.4/hl7:HD.2)
                                               then concat('urn:oid:', hl7:PID/hl7:PID.3[hl7:CX.5='MR'][1]/hl7:CX.4/hl7:HD.2)
                                               else concat($mrSystemBase, hl7:PID/hl7:PID.3[hl7:CX.5='MR'][1]/hl7:CX.4/hl7:HD.1)"/>
        <xsl:variable name="visitId"   select="(hl7:PV1/hl7:PV1.19/hl7:CX.1, '')[1]"/>

        <Bundle xmlns="http://hl7.org/fhir">
            <id value="{$messageId}"/>
            <type value="transaction"/>

            <!-- Encounter — mark as cancelled; no Patient entry -->
            <entry>
                <fullUrl value="urn:uuid:encounter-{$visitId}"/>
                <resource>
                    <Encounter xmlns="http://hl7.org/fhir">
                        <identifier>
                            <use value="official"/>
                            <value value="{$visitId}"/>
                        </identifier>

                        <!-- A11 = the original admission event is retracted -->
                        <status value="cancelled"/>

                        <class>
                            <system value="http://terminology.hl7.org/CodeSystem/v3-ActCode"/>
                            <code   value="{fn:toFhirEncounterClass(hl7:PV1/hl7:PV1.2)}"/>
                        </class>

                        <subject>
                            <reference value="Patient?identifier={$mrSystem}|{$patientId}"/>
                        </subject>
                    </Encounter>
                </resource>
                <request>
                    <method value="PUT"/>
                    <url value="Encounter?identifier={$visitId}"/>
                </request>
            </entry>

        </Bundle>
    </xsl:template>

</xsl:stylesheet>
