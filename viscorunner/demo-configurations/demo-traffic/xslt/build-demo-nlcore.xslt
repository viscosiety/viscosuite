<?xml version="1.0" encoding="UTF-8"?>
<!--
    Builds one FHIR R4 nl-core Patient (Nictiz profile) for the demo traffic
    generator — the "Dutch hospital sends R4" scenario. The nl-core intake
    validates it as R4, tags it into the inbound zone, and queues it for
    delivery.

    Input:  <variant>nl-core-patient | nl-core-patient-invalid | nl-core-patient-nonconformant</variant>
    Output: FHIR R4 Patient XML claiming the nl-core-Patient profile.

    Two seeded failure flavours, both classic real-world mistakes:
    - nl-core-patient-invalid carries gender="onbekend" — a Dutch label where
      FHIR requires a code — refused at PARSE level (422).
    - nl-core-patient-nonconformant is valid base R4 but omits the ISO 21090
      name-qualifier extension nl-core requires on every given name — refused
      by PROFILE validation (422) when the intake runs with the Nictiz
      packages loaded (nlcore.validation.*), and accepted otherwise. The
      difference demonstrates exactly what package-backed validation adds.

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
                <text value="{concat(substring-after($pat/@name, '^'), ' ', substring-before($pat/@name, '^'))}"/>
                <family value="{substring-before($pat/@name, '^')}"/>
                <given value="{substring-after($pat/@name, '^')}">
                    <!-- nl-core requires the name-qualifier on every given (BR = full
                         given name); the nonconformant variant omits it on purpose -->
                    <xsl:if test=". != 'nl-core-patient-nonconformant'">
                        <extension url="http://hl7.org/fhir/StructureDefinition/iso21090-EN-qualifier">
                            <valueCode value="BR"/>
                        </extension>
                    </xsl:if>
                </given>
            </name>
            <gender value="{$gender}"/>
            <birthDate value="{$dob}"/>
        </Patient>
    </xsl:template>

</xsl:stylesheet>
