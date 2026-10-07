<?xml version="1.0" encoding="UTF-8"?>
<!--
    FHIR DSTU3 Patient read transform.

    Context document : <id value="{id}"/> — the requested patient ID
    $fhirVersion     : "DSTU3"
    $fhirOperation   : "patient-read"

    Returns a demo Patient (Jan de Vries, PAT-001) with realistic DSTU3 fields.
    DSTU3 uses Patient.animal (absent here), no Patient.contact.name.text,
    and communication.language is CodeableConcept rather than code.
-->
<xsl:stylesheet version="3.0"
    xmlns:xsl  ="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs   ="http://www.w3.org/2001/XMLSchema"
    exclude-result-prefixes="xs">

    <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

    <xsl:param name="fhirVersion"   as="xs:string?"/>
    <xsl:param name="fhirOperation" as="xs:string?"/>

    <xsl:template match="/id">
        <xsl:variable name="pid" select="@value"/>
        <Patient xmlns="http://hl7.org/fhir">
            <id value="{$pid}"/>
            <meta>
                <profile value="http://hl7.org/fhir/StructureDefinition/Patient"/>
                <source value="fhir-to-fhir/patient-read-dstu3.xslt"/>
            </meta>
            <text>
                <status value="generated"/>
                <div xmlns="http://www.w3.org/1999/xhtml">
                    <p><b>Jan H. de Vries</b> (<xsl:value-of select="$pid"/>)</p>
                    <p>M, 15 March 1980 — Keizersgracht 123, Amsterdam</p>
                </div>
            </text>
            <identifier>
                <use value="usual"/>
                <type>
                    <coding>
                        <system value="http://hl7.org/fhir/v2/0203"/>
                        <code value="MR"/>
                        <display value="Medical Record Number"/>
                    </coding>
                </type>
                <system value="urn:oid:2.16.840.1.113883.2.4.99.1"/>
                <value value="{$pid}"/>
                <assigner><display value="HospitalA"/></assigner>
            </identifier>
            <identifier>
                <use value="official"/>
                <type>
                    <coding>
                        <system value="http://hl7.org/fhir/v2/0203"/>
                        <code value="NNLD"/>
                        <display value="National Person Identifier Netherlands"/>
                    </coding>
                </type>
                <system value="urn:oid:2.16.840.1.113883.2.4.6.3"/>
                <value value="123456782"/>
            </identifier>
            <active value="true"/>
            <name>
                <use value="official"/>
                <family value="de Vries"/>
                <given value="Jan"/>
                <given value="H"/>
                <prefix value="dhr."/>
            </name>
            <telecom>
                <system value="phone"/>
                <value value="+31612345678"/>
                <use value="mobile"/>
            </telecom>
            <telecom>
                <system value="email"/>
                <value value="j.devries@example.nl"/>
                <use value="home"/>
            </telecom>
            <gender value="male"/>
            <birthDate value="1980-03-15"/>
            <address>
                <use value="home"/>
                <type value="both"/>
                <line value="Keizersgracht 123"/>
                <city value="Amsterdam"/>
                <district value="Noord-Holland"/>
                <postalCode value="1015CJ"/>
                <country value="NLD"/>
            </address>
            <maritalStatus>
                <coding>
                    <system value="http://hl7.org/fhir/v3/MaritalStatus"/>
                    <code value="M"/>
                    <display value="Married"/>
                </coding>
            </maritalStatus>
            <contact>
                <relationship>
                    <coding>
                        <system value="http://hl7.org/fhir/v2/0131"/>
                        <code value="N"/>
                        <display value="Next-of-Kin"/>
                    </coding>
                </relationship>
                <name>
                    <use value="official"/>
                    <family value="de Vries"/>
                    <given value="Maria"/>
                    <given value="H"/>
                </name>
                <telecom>
                    <system value="phone"/>
                    <value value="+31201234567"/>
                    <use value="home"/>
                </telecom>
            </contact>
            <communication>
                <language>
                    <coding>
                        <system value="urn:ietf:bcp:47"/>
                        <code value="nl"/>
                        <display value="Dutch"/>
                    </coding>
                </language>
                <preferred value="true"/>
            </communication>
            <generalPractitioner>
                <reference value="Practitioner/DR001"/>
                <display value="Dr. Erik de Boer, MD"/>
            </generalPractitioner>
            <managingOrganization>
                <reference value="Organization/HospitalA"/>
                <display value="HospitalA"/>
            </managingOrganization>
        </Patient>
    </xsl:template>

</xsl:stylesheet>
