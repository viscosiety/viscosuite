<?xml version="1.0" encoding="UTF-8"?>
<!--
    Builds one FHIR R4 nl-core Patient (Nictiz profile) for the demo traffic
    generator — the "Dutch hospital sends R4" scenario. The nl-core intake
    validates it as R4, tags it into the inbound zone, and queues it for
    delivery.

    Input:  <variant>nl-core-patient | nl-core-patient-invalid</variant>
    Output: FHIR R4 Patient XML claiming the nl-core-Patient profile.

    The invalid variant carries gender="onbekend" — a Dutch label where FHIR
    requires a code from the administrative-gender ValueSet — so R4 validation
    refuses it at the intake with an OperationOutcome (HTTP 422). A classic
    real-world mapping mistake, on purpose.

    BSNs are fictitious (999-range test numbers), derived from the roster id.
    Roster and clock rotation come from demo-shared.xslt.
-->
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns="http://hl7.org/fhir">
    <xsl:include href="demo-shared.xslt"/>
    <xsl:output method="xml" encoding="UTF-8" omit-xml-declaration="yes" indent="yes"/>

    <xsl:template match="/variant">
        <xsl:variable name="gender" select="if (. = 'nl-core-patient-invalid') then 'onbekend'
                                            else if ($pat/@sex = 'F') then 'female' else 'male'"/>
        <xsl:variable name="dob" select="concat(substring($pat/@dob,1,4),'-',substring($pat/@dob,5,2),'-',substring($pat/@dob,7,2))"/>
        <Patient>
            <meta>
                <profile value="http://nictiz.nl/fhir/StructureDefinition/nl-core-Patient"/>
            </meta>
            <identifier>
                <!-- fictitious test-BSN (999 range); never a real citizen number -->
                <system value="http://fhir.nl/fhir/NamingSystem/bsn"/>
                <value value="9999{$pat/@id}"/>
            </identifier>
            <name>
                <use value="official"/>
                <family value="{substring-before($pat/@name, '^')}"/>
                <given value="{substring-after($pat/@name, '^')}"/>
            </name>
            <gender value="{$gender}"/>
            <birthDate value="{$dob}"/>
        </Patient>
    </xsl:template>

</xsl:stylesheet>
