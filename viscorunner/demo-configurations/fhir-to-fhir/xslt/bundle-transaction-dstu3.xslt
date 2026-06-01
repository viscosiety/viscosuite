<?xml version="1.0" encoding="UTF-8"?>
<!--
    FHIR DSTU3 Bundle transaction transform.

    Context document : a FHIR DSTU3 Bundle serialised as XML (xmlns="http://hl7.org/fhir")
    $fhirVersion     : "DSTU3" (injected by FhirOperationListener)
    $fhirOperation   : "bundle-transaction" (injected by FhirOperationListener)

    Demo transformation: copies the input Bundle and synthesises a linked Encounter
    entry for every Patient entry in the transaction.
    DSTU3: Encounter uses Encounter.class (Coding, not CodeableConcept) and
           Encounter.period instead of actualPeriod, participant.individual instead of actor.
-->
<xsl:stylesheet version="3.0"
    xmlns:xsl  ="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs   ="http://www.w3.org/2001/XMLSchema"
    xmlns:fhir ="http://hl7.org/fhir"
    exclude-result-prefixes="xs fhir">

    <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

    <xsl:param name="fhirVersion"   as="xs:string?"/>
    <xsl:param name="fhirOperation" as="xs:string?"/>

    <xsl:mode on-no-match="shallow-copy"/>

    <xsl:template match="fhir:entry[fhir:resource/fhir:Patient]">
        <xsl:variable name="patientUrl" select="(fhir:fullUrl/@value, 'urn:uuid:unknown')[1]"/>
        <xsl:copy>
            <xsl:apply-templates select="@* | node()"/>
        </xsl:copy>
        <entry xmlns="http://hl7.org/fhir">
            <fullUrl value="urn:uuid:enc-{generate-id()}"/>
            <resource>
                <Encounter>
                    <meta>
                        <tag>
                            <system value="https://ig.viscosiety.com/CodeSystem/data-zone"/>
                            <code value="synthetic"/>
                            <display value="Synthesised by fhir-to-fhir/bundle-transaction-dstu3.xslt"/>
                        </tag>
                    </meta>
                    <status value="finished"/>
                    <class>
                        <system value="http://hl7.org/fhir/v3/ActCode"/>
                        <code value="AMB"/>
                        <display value="ambulatory"/>
                    </class>
                    <type>
                        <coding>
                            <system value="http://snomed.info/sct"/>
                            <code value="11429006"/>
                            <display value="Consultation"/>
                        </coding>
                    </type>
                    <subject>
                        <reference value="{$patientUrl}"/>
                    </subject>
                    <participant>
                        <individual>
                            <reference value="Practitioner/DR001"/>
                            <display value="Dr. Erik de Boer, MD"/>
                        </individual>
                    </participant>
                    <period>
                        <start value="2026-06-01T09:00:00+02:00"/>
                        <end   value="2026-06-01T09:30:00+02:00"/>
                    </period>
                    <serviceProvider>
                        <reference value="Organization/HospitalA"/>
                        <display value="HospitalA"/>
                    </serviceProvider>
                </Encounter>
            </resource>
            <request>
                <method value="POST"/>
                <url value="Encounter"/>
            </request>
        </entry>
    </xsl:template>

</xsl:stylesheet>
