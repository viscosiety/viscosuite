<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:hl7="urn:hl7-org:v2xml"
                xmlns:fn="urn:hl7v2-fhir-functions"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:map="http://www.w3.org/2005/xpath-functions/map"
                exclude-result-prefixes="hl7 fn xs map">

    <!--
      hl7v2-fhir-functions.xslt
      =========================
      Shared functions and templates for HL7v2 to FHIR mapping.
      Import this file from message-type-specific stylesheets.
      Do not use this file directly as a transformation entry point.

      Contents:
        - Date/datetime conversion
        - Code translations
        - Reusable FHIR resource templates
    -->

    <!--
      Configurable base URL used when the MR assigning authority CX.4 carries
      only a text name HD.1 rather than an OID HD.2.
      Override by passing mrSystemBase as an XSLT parameter from the pipeline.
    -->
    <xsl:param name="mrSystemBase" as="xs:string" select="'https://example.nl/fhir/NamingSystem/'"/>

    <!-- ============================================================
         DATE / DATETIME FUNCTIONS
         ============================================================ -->

    <!-- HL7 date YYYYMMDD to FHIR date YYYY-MM-DD -->
    <xsl:function name="fn:toFhirDate" as="xs:string">
        <xsl:param name="hl7date" as="xs:string?"/>
        <xsl:variable name="d" select="($hl7date, '')[1]"/>
        <xsl:value-of select="if (string-length($d) >= 8)
                              then concat(substring($d,1,4), '-', substring($d,5,2), '-', substring($d,7,2))
                              else $d"/>
    </xsl:function>

    <!--
      Derives a valid UUID-shaped transaction placeholder from:
        - a transaction UUID supplied by the caller
        - a resource type, e.g. Patient, Encounter
        - a logical source id, e.g. PAT-001, V-2026-001

      This is intended for FHIR Bundle.fullUrl placeholders:
        urn:uuid:{derived-placeholder}

      It is deterministic within a transaction:
        same transactionUuid + resourceType + sourceId => same placeholder

      It is not intended as a persistent business identifier.
    -->
    <xsl:function name="fn:derivePlaceholderUuid" as="xs:string">
        <xsl:param name="transactionUuid" as="xs:string?"/>
        <xsl:param name="resourceType" as="xs:string"/>
        <xsl:param name="sourceId" as="xs:string?"/>

        <!-- Keep only hex chars from the supplied transaction UUID -->
        <xsl:variable name="baseHex"
                      select="lower-case(replace(($transactionUuid, '')[1], '[^0-9a-fA-F]', ''))"/>

        <!-- Fallback base if caller forgot to pass a transaction UUID -->
        <xsl:variable name="safeBaseHex"
                      select="if (string-length($baseHex) ge 32)
                          then substring($baseHex, 1, 32)
                          else '00000000000040008000000000000000'"/>

        <!-- A small deterministic numeric salt from resource type + source id -->
        <xsl:variable name="saltSource"
                      select="concat($resourceType, '|', ($sourceId, '')[1])"/>

        <xsl:variable name="salt"
                      select="sum(string-to-codepoints($saltSource)) mod 1000000000000"/>

        <xsl:variable name="salt12"
                      select="format-number($salt, '000000000000')"/>

        <!--
          Construct UUID-shaped value.

          Positions:
          - version nibble forced to 4
          - variant nibble forced to 8
          - final 12 digits replaced by deterministic per-resource salt
        -->
        <xsl:value-of select="concat(
        substring($safeBaseHex, 1, 8), '-',
        substring($safeBaseHex, 9, 4), '-',
        '4', substring($safeBaseHex, 14, 3), '-',
        '8', substring($safeBaseHex, 18, 3), '-',
        $salt12
    )"/>
    </xsl:function>

    <!-- HL7 datetime YYYYMMDD[HHMMSS] to FHIR dateTime -->
    <xsl:function name="fn:toFhirDateTime" as="xs:string">
        <xsl:param name="hl7datetime" as="xs:string?"/>
        <xsl:variable name="dt" select="($hl7datetime, '')[1]"/>
        <xsl:choose>
            <xsl:when test="string-length($dt) >= 14">
                <xsl:value-of select="concat(
                    substring($dt,1,4),  '-',
                    substring($dt,5,2),  '-',
                    substring($dt,7,2),  'T',
                    substring($dt,9,2),  ':',
                    substring($dt,11,2), ':',
                    substring($dt,13,2), '+00:00')"/>
            </xsl:when>
            <xsl:when test="string-length($dt) >= 8">
                <xsl:value-of select="concat(fn:toFhirDate($dt), 'T00:00:00+00:00')"/>
            </xsl:when>
            <xsl:otherwise>
                <xsl:value-of select="$dt"/>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:function>

    <!-- ============================================================
         CODE TRANSLATION FUNCTIONS
         ============================================================ -->

    <!-- HL7 gender code PID.8 to FHIR gender -->
    <xsl:function name="fn:toFhirGender" as="xs:string">
        <xsl:param name="hl7gender" as="xs:string?"/>
        <xsl:variable name="code" select="($hl7gender, '')[1]"/>
        <xsl:variable name="map" select="map{
            'M': 'male',
            'F': 'female',
            'O': 'other',
            'U': 'unknown'
        }"/>
        <xsl:value-of select="if (map:contains($map, $code))
                              then map:get($map, $code)
                              else 'unknown'"/>
    </xsl:function>

    <!-- HL7 patient class PV1.2 to FHIR Encounter.class code -->
    <xsl:function name="fn:toFhirEncounterClass" as="xs:string">
        <xsl:param name="hl7class" as="xs:string?"/>
        <xsl:variable name="code" select="($hl7class, '')[1]"/>
        <xsl:variable name="map" select="map{
            'I': 'IMP',
            'O': 'AMB',
            'E': 'EMER',
            'P': 'PRENC',
            'R': 'AMB',
            'B': 'EMER'
        }"/>
        <xsl:value-of select="if (map:contains($map, $code))
                              then map:get($map, $code)
                              else 'AMB'"/>
    </xsl:function>

    <!-- HL7 telecom type XTN.2 / XTN.3 to FHIR telecom use -->
    <xsl:function name="fn:toFhirTelecomUse" as="xs:string">
        <xsl:param name="hl7use" as="xs:string?"/>
        <xsl:variable name="code" select="($hl7use, '')[1]"/>
        <xsl:variable name="map" select="map{
            'PRN': 'home',
            'WPN': 'work',
            'CP':  'mobile',
            'ORN': 'old',
            'EMR': 'home'
        }"/>
        <xsl:value-of select="if (map:contains($map, $code))
                              then map:get($map, $code)
                              else 'home'"/>
    </xsl:function>

    <!-- HL7 telecom equipment type XTN.3 to FHIR telecom system -->
    <xsl:function name="fn:toFhirTelecomSystem" as="xs:string">
        <xsl:param name="hl7equipment" as="xs:string?"/>
        <xsl:variable name="code" select="($hl7equipment, '')[1]"/>
        <xsl:variable name="map" select="map{
            'PH':       'phone',
            'CP':       'phone',
            'FX':       'fax',
            'Internet': 'email',
            'X.400':    'email',
            'BP':       'pager',
            'SAT':      'phone'
        }"/>
        <xsl:value-of select="if (map:contains($map, $code))
                              then map:get($map, $code)
                              else 'phone'"/>
    </xsl:function>

    <!-- HL7 name type code XPN.7 to FHIR name use -->
    <xsl:function name="fn:toFhirNameUse" as="xs:string">
        <xsl:param name="hl7nameType" as="xs:string?"/>
        <xsl:variable name="code" select="($hl7nameType, '')[1]"/>
        <xsl:variable name="map" select="map{
            'L': 'official',
            'D': 'usual',
            'M': 'maiden',
            'N': 'nickname',
            'T': 'temp',
            'S': 'anonymous'
        }"/>
        <xsl:value-of select="if (map:contains($map, $code))
                              then map:get($map, $code)
                              else 'usual'"/>
    </xsl:function>

    <!-- HL7 discharge disposition PV1.36, table 0112, to FHIR discharge-disposition code -->
    <xsl:function name="fn:toFhirDischargeDisposition" as="xs:string">
        <xsl:param name="hl7code" as="xs:string?"/>
        <xsl:variable name="code" select="($hl7code, '')[1]"/>
        <xsl:variable name="map" select="map{
            '01': 'home',
            '02': 'alt-home',
            '03': 'other-hcf',
            '04': 'hosp',
            '05': 'long',
            '06': 'aadvice',
            '07': 'exp',
            '20': 'psy',
            '21': 'rehab',
            '30': 'oth'
        }"/>
        <xsl:value-of select="if (map:contains($map, $code))
                              then map:get($map, $code)
                              else 'oth'"/>
    </xsl:function>

    <!-- ============================================================
         SHARED FHIR RESOURCE TEMPLATES
         ============================================================ -->

    <!-- MessageHeader, driven by MSH segment -->
    <xsl:template match="hl7:MSH" mode="MessageHeader">
        <xsl:param name="eventCode" as="xs:string?"/>
        <xsl:param name="eventDisplay" as="xs:string?"/>
        <xsl:param name="focusRef" as="xs:string?"/>

        <MessageHeader xmlns="http://hl7.org/fhir">
            <text>
                <status value="generated"/>
                <div xmlns="http://www.w3.org/1999/xhtml">
                    <p>Generated MessageHeader resource from HL7v2 MSH segment.</p>
                </div>
            </text>
            <id value="messageheader-{hl7:MSH.10}"/>
            <eventCoding>
                <system value="http://terminology.hl7.org/CodeSystem/v2-0003"/>
                <code value="{$eventCode}"/>
                <display value="{$eventDisplay}"/>
            </eventCoding>
            <source>
                <name value="{hl7:MSH.3/hl7:HD.1}"/>
            </source>
            <destination>
                <name value="{hl7:MSH.5/hl7:HD.1}"/>
            </destination>
            <focus>
                <reference value="{$focusRef}"/>
            </focus>
        </MessageHeader>
    </xsl:template>

    <!-- Patient, driven by PID segment -->
    <xsl:template match="hl7:PID" mode="Patient">
        <xsl:param name="patientId" as="xs:string?"/>
        <xsl:param name="nk1Segments" as="element()*" select="()"/>

        <Patient xmlns="http://hl7.org/fhir">
            <text>
                <status value="generated"/>
                <div xmlns="http://www.w3.org/1999/xhtml">
                    <p>Generated Patient resource from HL7v2 PID segment.</p>
                </div>
            </text>

            <!-- Identifiers -->

            <!-- PID.3: MR and NNLD BSN identifiers, one entry per repetition -->
            <xsl:for-each select="hl7:PID.3">
                <xsl:choose>
                    <xsl:when test="hl7:CX.5 = 'MR'">
                        <identifier>
                            <use value="usual"/>
                            <type>
                                <coding>
                                    <system value="http://terminology.hl7.org/CodeSystem/v2-0203"/>
                                    <code value="MR"/>
                                    <display value="Medical record number"/>
                                </coding>
                            </type>
                            <system value="{if (hl7:CX.4/hl7:HD.2)
                                            then concat('urn:oid:', hl7:CX.4/hl7:HD.2)
                                            else concat($mrSystemBase, hl7:CX.4/hl7:HD.1)}"/>
                            <value value="{hl7:CX.1}"/>
                        </identifier>
                    </xsl:when>
                    <xsl:when test="hl7:CX.5 = 'NNLD'">
                        <identifier>
                            <use value="official"/>
                            <system value="http://fhir.nl/fhir/NamingSystem/bsn"/>
                            <value value="{hl7:CX.1}"/>
                        </identifier>
                    </xsl:when>
                </xsl:choose>
            </xsl:for-each>

            <!--
              PID.18: Patient account number.

              Easy validation-safe fix:
              do not emit v2-0203#AN as Identifier.type, because the base FHIR
              IdentifierType value set does not accept it cleanly. Preserve the
              account number as an identifier with a custom NamingSystem URL.
            -->
            <xsl:if test="hl7:PID.18/hl7:CX.1">
                <identifier>
                    <use value="usual"/>
                    <system value="https://ig.viscosiety.com/fhir/NamingSystem/patient-account-number"/>
                    <value value="{hl7:PID.18/hl7:CX.1}"/>
                </identifier>
            </xsl:if>

            <!-- Names -->

            <!-- PID.5: Patient names, all repetitions -->
            <xsl:for-each select="hl7:PID.5">
                <name>
                    <use value="{fn:toFhirNameUse(hl7:XPN.7)}"/>
                    <family value="{hl7:XPN.1/hl7:FN.1}"/>
                    <xsl:if test="hl7:XPN.2">
                        <given value="{hl7:XPN.2}"/>
                    </xsl:if>
                    <xsl:if test="hl7:XPN.3">
                        <given value="{hl7:XPN.3}"/>
                    </xsl:if>
                </name>
            </xsl:for-each>

            <!-- PID.6: Mother's maiden name -->
            <xsl:if test="hl7:PID.6/hl7:XPN.1/hl7:FN.1">
                <name>
                    <use value="maiden"/>
                    <family value="{hl7:PID.6/hl7:XPN.1/hl7:FN.1}"/>
                    <xsl:if test="hl7:PID.6/hl7:XPN.2">
                        <given value="{hl7:PID.6/hl7:XPN.2}"/>
                    </xsl:if>
                </name>
            </xsl:if>

            <!-- Telecom -->

            <!-- PID.13: Home/personal phone numbers -->
            <xsl:for-each select="hl7:PID.13[hl7:XTN.1]">
                <telecom>
                    <system value="{fn:toFhirTelecomSystem(hl7:XTN.3)}"/>
                    <value value="{hl7:XTN.1}"/>
                    <use value="{fn:toFhirTelecomUse(hl7:XTN.2)}"/>
                </telecom>
            </xsl:for-each>

            <!-- PID.14: Business phone numbers -->
            <xsl:for-each select="hl7:PID.14[hl7:XTN.1]">
                <telecom>
                    <system value="{fn:toFhirTelecomSystem(hl7:XTN.3)}"/>
                    <value value="{hl7:XTN.1}"/>
                    <use value="work"/>
                </telecom>
            </xsl:for-each>

            <!-- Demographics -->

            <!-- PID.8: Administrative sex -->
            <xsl:if test="hl7:PID.8">
                <gender value="{fn:toFhirGender(hl7:PID.8)}"/>
            </xsl:if>

            <!-- PID.7: Date of birth -->
            <xsl:if test="hl7:PID.7/hl7:TS.1">
                <birthDate value="{fn:toFhirDate(hl7:PID.7/hl7:TS.1)}"/>
            </xsl:if>

            <!-- PID.29/PID.30: Deceased -->
            <xsl:choose>
                <xsl:when test="hl7:PID.29/hl7:TS.1">
                    <deceasedDateTime value="{fn:toFhirDateTime(hl7:PID.29/hl7:TS.1)}"/>
                </xsl:when>
                <xsl:when test="hl7:PID.30 = 'Y'">
                    <deceasedBoolean value="true"/>
                </xsl:when>
            </xsl:choose>

            <!-- Address -->

            <!-- PID.11: Patient addresses -->
            <xsl:for-each select="hl7:PID.11">
                <address>
                    <use value="home"/>
                    <xsl:if test="hl7:XAD.1/hl7:SAD.1">
                        <line value="{hl7:XAD.1/hl7:SAD.1}"/>
                    </xsl:if>
                    <xsl:if test="hl7:XAD.3">
                        <city value="{hl7:XAD.3}"/>
                    </xsl:if>
                    <xsl:if test="hl7:XAD.5">
                        <postalCode value="{hl7:XAD.5}"/>
                    </xsl:if>
                    <xsl:if test="hl7:XAD.6">
                        <country value="{hl7:XAD.6}"/>
                    </xsl:if>
                </address>
            </xsl:for-each>

            <!-- Marital status -->

            <!-- PID.16: Marital status -->
            <xsl:if test="hl7:PID.16/hl7:CE.1">
                <maritalStatus>
                    <coding>
                        <system value="http://terminology.hl7.org/CodeSystem/v3-MaritalStatus"/>
                        <code value="{hl7:PID.16/hl7:CE.1}"/>
                    </coding>
                </maritalStatus>
            </xsl:if>

            <!-- Multiple birth -->

            <!-- PID.25 birth order takes precedence over PID.24 Y/N indicator -->
            <xsl:choose>
                <xsl:when test="hl7:PID.25 castable as xs:integer">
                    <multipleBirthInteger value="{hl7:PID.25}"/>
                </xsl:when>
                <xsl:when test="hl7:PID.24 = 'Y'">
                    <multipleBirthBoolean value="true"/>
                </xsl:when>
            </xsl:choose>

            <!-- Next of kin to Patient.contact -->

            <xsl:for-each select="$nk1Segments">
                <contact>
                    <!-- NK1.3 relationship: emit v2-0063 coding (extensible binding accepts it) + text -->
                    <xsl:if test="hl7:NK1.3/hl7:CE.1 or hl7:NK1.3/hl7:CE.2">
                        <relationship>
                            <xsl:if test="hl7:NK1.3/hl7:CE.1">
                                <coding>
                                    <system value="http://terminology.hl7.org/CodeSystem/v2-0063"/>
                                    <code value="{hl7:NK1.3/hl7:CE.1}"/>
                                    <xsl:if test="hl7:NK1.3/hl7:CE.2">
                                        <display value="{hl7:NK1.3/hl7:CE.2}"/>
                                    </xsl:if>
                                </coding>
                            </xsl:if>
                            <text value="{if (hl7:NK1.3/hl7:CE.2)
                                          then hl7:NK1.3/hl7:CE.2
                                          else hl7:NK1.3/hl7:CE.1}"/>
                        </relationship>
                    </xsl:if>

                    <!-- NK1.2: Name -->
                    <xsl:if test="hl7:NK1.2/hl7:XPN.1/hl7:FN.1">
                        <name>
                            <family value="{hl7:NK1.2/hl7:XPN.1/hl7:FN.1}"/>
                            <xsl:if test="hl7:NK1.2/hl7:XPN.2">
                                <given value="{hl7:NK1.2/hl7:XPN.2}"/>
                            </xsl:if>
                        </name>
                    </xsl:if>

                    <!-- NK1.5: Phone numbers -->
                    <xsl:for-each select="hl7:NK1.5[hl7:XTN.1]">
                        <telecom>
                            <system value="{fn:toFhirTelecomSystem(hl7:XTN.3)}"/>
                            <value value="{hl7:XTN.1}"/>
                            <use value="{fn:toFhirTelecomUse(hl7:XTN.2)}"/>
                        </telecom>
                    </xsl:for-each>

                    <!-- NK1.4: Address -->
                    <xsl:if test="hl7:NK1.4/hl7:XAD.1/hl7:SAD.1 or hl7:NK1.4/hl7:XAD.3">
                        <address>
                            <xsl:if test="hl7:NK1.4/hl7:XAD.1/hl7:SAD.1">
                                <line value="{hl7:NK1.4/hl7:XAD.1/hl7:SAD.1}"/>
                            </xsl:if>
                            <xsl:if test="hl7:NK1.4/hl7:XAD.3">
                                <city value="{hl7:NK1.4/hl7:XAD.3}"/>
                            </xsl:if>
                            <xsl:if test="hl7:NK1.4/hl7:XAD.5">
                                <postalCode value="{hl7:NK1.4/hl7:XAD.5}"/>
                            </xsl:if>
                            <xsl:if test="hl7:NK1.4/hl7:XAD.6">
                                <country value="{hl7:NK1.4/hl7:XAD.6}"/>
                            </xsl:if>
                        </address>
                    </xsl:if>
                </contact>
            </xsl:for-each>

            <!-- Communication -->

            <!-- PID.15: Primary language -->
            <xsl:if test="hl7:PID.15/hl7:CE.1">
                <communication>
                    <language>
                        <coding>
                            <system value="urn:ietf:bcp:47"/>
                            <code value="{hl7:PID.15/hl7:CE.1}"/>
                        </coding>
                    </language>
                    <preferred value="true"/>
                </communication>
            </xsl:if>

        </Patient>
    </xsl:template>


</xsl:stylesheet>