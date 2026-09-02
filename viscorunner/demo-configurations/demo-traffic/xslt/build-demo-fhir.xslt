<?xml version="1.0" encoding="UTF-8"?>
<!--
    Builds one FHIR (R4) transaction Bundle in FHIR XML for the demo traffic
    generator. The async delivery flow converts it to whatever FHIR mimetype
    the destination is configured for (FhirFormatPipe) — the generator itself
    stays format-agnostic and simply emits canonical XML.

    Input:  <variant>fhir-bundle | fhir-bundle-invalid</variant>
    Output: FHIR XML Bundle. The invalid variant carries an unknown resource
            type (PatientRecord), so the delivery flow's FHIR parse fails and
            the message parks in the error store even when ViscoStore is healthy.

    Roster and clock rotation come from demo-shared.xslt.
-->
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns="http://hl7.org/fhir">
    <xsl:include href="demo-shared.xslt"/>
    <xsl:output method="xml" encoding="UTF-8" omit-xml-declaration="yes" indent="yes"/>

    <xsl:template match="/variant">
        <xsl:variable name="resourceType" select="if (. = 'fhir-bundle') then 'Patient' else 'PatientRecord'"/>
        <xsl:variable name="gender" select="if ($pat/@sex = 'F') then 'female' else 'male'"/>
        <xsl:variable name="dob" select="concat(substring($pat/@dob,1,4),'-',substring($pat/@dob,5,2),'-',substring($pat/@dob,7,2))"/>
        <Bundle>
            <meta>
                <!-- the study tag is what downstream facet extraction keys on -->
                <tag>
                    <system value="urn:visco:study"/>
                    <code value="{$study}"/>
                </tag>
            </meta>
            <type value="transaction"/>
            <entry>
                <fullUrl value="urn:uuid:demo-{$ts}-{$pat/@id}"/>
                <resource>
                    <xsl:element name="{$resourceType}" namespace="http://hl7.org/fhir">
                        <identifier>
                            <system value="urn:visco:mrn"/>
                            <value value="{$pat/@id}"/>
                        </identifier>
                        <name>
                            <family value="{substring-before($pat/@name, '^')}"/>
                            <given value="{substring-after($pat/@name, '^')}"/>
                        </name>
                        <gender value="{$gender}"/>
                        <birthDate value="{$dob}"/>
                    </xsl:element>
                </resource>
                <request>
                    <method value="PUT"/>
                    <url value="Patient?identifier=urn:visco:mrn%7C{$pat/@id}"/>
                </request>
            </entry>
        </Bundle>
    </xsl:template>

</xsl:stylesheet>
