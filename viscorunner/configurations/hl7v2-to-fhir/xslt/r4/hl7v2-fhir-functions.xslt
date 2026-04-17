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
      =======================
      Shared functions and templates for HL7v2 to FHIR mapping.
      Import this file from message-type-specific stylesheets.
      Do NOT use this file directly as a transformation entry point.

      Contents:
        - Date/datetime conversion
        - Code translations (gender, encounter class, telecom)
        - Reusable FHIR resource templates (Patient, MessageHeader)
    -->

    <!--
      Configurable base URL used when the MR assigning authority (CX.4) carries
      only a text name (HD.1) rather than an OID (HD.2).
      Override by passing mrSystemBase as an XSLT parameter from the pipeline.
      Default matches the value of mr.system.base in DeploymentSpecifics.properties.
    -->
    <xsl:param name="mrSystemBase" as="xs:string" select="'https://example.nl/fhir/NamingSystem/'"/>

    <!-- ============================================================
         DATE / DATETIME FUNCTIONS
         ============================================================ -->

    <!-- HL7 date (YYYYMMDD) → FHIR date (YYYY-MM-DD) -->
    <xsl:function name="fn:toFhirDate" as="xs:string">
        <xsl:param name="hl7date" as="xs:string?"/>
        <xsl:variable name="d" select="($hl7date, '')[1]"/>
        <xsl:value-of select="if (string-length($d) >= 8)
                              then concat(substring($d,1,4), '-', substring($d,5,2), '-', substring($d,7,2))
                              else $d"/>
    </xsl:function>

    <!-- HL7 datetime (YYYYMMDD[HHMMSS]) → FHIR dateTime -->
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
         All parameters are xs:string? (optional) — absent HL7v2 fields
         yield an empty sequence which is coerced to '' via ($param,'')[1],
         causing the map lookup to miss and return the documented default.
         ============================================================ -->

    <!-- HL7 gender code (PID.8) → FHIR gender -->
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

    <!-- HL7 patient class (PV1.2) → FHIR encounter class code -->
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
                          else 'UNKNOWN'"/>
    </xsl:function>

    <!-- HL7 telecom type (XTN.2 / XTN.3) → FHIR telecom use -->
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

    <!-- HL7 telecom equipment type (XTN.3) → FHIR telecom system -->
    <xsl:function name="fn:toFhirTelecomSystem" as="xs:string">
        <xsl:param name="hl7equipment" as="xs:string?"/>
        <xsl:variable name="code" select="($hl7equipment, '')[1]"/>
        <xsl:variable name="map" select="map{
      'PH':    'phone',
      'CP':    'phone',
      'FX':    'fax',
      'Internet': 'email',
      'X.400': 'email',
      'BP':    'pager',
      'SAT':   'phone'
    }"/>
        <xsl:value-of select="if (map:contains($map, $code))
                          then map:get($map, $code)
                          else 'phone'"/>
    </xsl:function>

    <!-- HL7 name type code (XPN.7) → FHIR name use -->
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

    <!-- HL7 discharge disposition (PV1.36, table 0112) → FHIR discharge-disposition code -->
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
         These produce FHIR resources that appear in many message types.
         Message-type-specific stylesheets call these via apply-templates.
         ============================================================ -->

    <!-- MessageHeader — driven by MSH segment -->
    <xsl:template match="hl7:MSH" mode="MessageHeader">
        <xsl:param name="eventCode"    as="xs:string?"/>
        <xsl:param name="eventDisplay" as="xs:string?"/>
        <xsl:param name="focusRef"     as="xs:string?"/>

        <MessageHeader xmlns="http://hl7.org/fhir">
            <id value="messageheader-{hl7:MSH.10}"/>
            <eventCoding>
                <system value="http://terminology.hl7.org/CodeSystem/v2-0003"/>
                <code    value="{$eventCode}"/>
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

    <!-- Patient — driven by PID segment -->
    <xsl:template match="hl7:PID" mode="Patient">
        <xsl:param name="patientId" as="xs:string?"/>

        <Patient xmlns="http://hl7.org/fhir">
            <!-- Emit one <identifier> per PID.3 repetition, typed by CX.5 -->
            <xsl:for-each select="hl7:PID.3">
                <xsl:choose>
                    <xsl:when test="hl7:CX.5 = 'MR'">
                        <!-- MR system derived from assigning authority (CX.4):
                             HD.2 (OID) takes precedence; fall back to a URL built from HD.1. -->
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
                                            else concat('https://example.nl/fhir/NamingSystem/', hl7:CX.4/hl7:HD.1)}"/>
                            <value value="{hl7:CX.1}"/>
                        </identifier>
                    </xsl:when>
                    <xsl:when test="hl7:CX.5 = 'NNLD'">
                        <!-- BSN: system URL alone identifies this as a Dutch BSN; no type needed -->
                        <identifier>
                            <use value="official"/>
                            <system value="http://fhir.nl/fhir/NamingSystem/bsn"/>
                            <value value="{hl7:CX.1}"/>
                        </identifier>
                    </xsl:when>
                </xsl:choose>
            </xsl:for-each>

            <xsl:for-each select="hl7:PID.5">
                <name>
                    <use value="{fn:toFhirNameUse(hl7:XPN.7)}"/>
                    <family value="{hl7:XPN.1/hl7:FN.1}"/>
                    <xsl:if test="hl7:XPN.2"><given value="{hl7:XPN.2}"/></xsl:if>
                    <xsl:if test="hl7:XPN.3"><given value="{hl7:XPN.3}"/></xsl:if>
                </name>
            </xsl:for-each>

            <xsl:for-each select="hl7:PID.13[hl7:XTN.1]">
                <telecom>
                    <system value="{fn:toFhirTelecomSystem(hl7:XTN.3)}"/>
                    <value  value="{hl7:XTN.1}"/>
                    <use    value="{fn:toFhirTelecomUse(hl7:XTN.2)}"/>
                </telecom>
            </xsl:for-each>

            <xsl:if test="hl7:PID.8">
                <gender value="{fn:toFhirGender(hl7:PID.8)}"/>
            </xsl:if>

            <xsl:if test="hl7:PID.7/hl7:TS.1">
                <birthDate value="{fn:toFhirDate(hl7:PID.7/hl7:TS.1)}"/>
            </xsl:if>

            <xsl:for-each select="hl7:PID.11">
                <address>
                    <use value="home"/>
                    <xsl:if test="hl7:XAD.1/hl7:SAD.1"><line value="{hl7:XAD.1/hl7:SAD.1}"/></xsl:if>
                    <xsl:if test="hl7:XAD.3"><city value="{hl7:XAD.3}"/></xsl:if>
                    <xsl:if test="hl7:XAD.5"><postalCode value="{hl7:XAD.5}"/></xsl:if>
                    <xsl:if test="hl7:XAD.6"><country value="{hl7:XAD.6}"/></xsl:if>
                </address>
            </xsl:for-each>

        </Patient>
    </xsl:template>

</xsl:stylesheet>
