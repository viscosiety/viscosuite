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

    <!-- ============================================================
         DATE / DATETIME FUNCTIONS
         ============================================================ -->

    <!-- HL7 date (YYYYMMDD) → FHIR date (YYYY-MM-DD) -->
    <xsl:function name="fn:toFhirDate" as="xs:string">
        <xsl:param name="hl7date" as="xs:string"/>
        <xsl:value-of select="concat(
      substring($hl7date,1,4), '-',
      substring($hl7date,5,2), '-',
      substring($hl7date,7,2))"/>
    </xsl:function>

    <!-- HL7 datetime (YYYYMMDD[HHMMSS]) → FHIR dateTime -->
    <xsl:function name="fn:toFhirDateTime" as="xs:string">
        <xsl:param name="hl7datetime" as="xs:string"/>
        <xsl:choose>
            <xsl:when test="string-length($hl7datetime) >= 14">
                <xsl:value-of select="concat(
          substring($hl7datetime,1,4),  '-',
          substring($hl7datetime,5,2),  '-',
          substring($hl7datetime,7,2),  'T',
          substring($hl7datetime,9,2),  ':',
          substring($hl7datetime,11,2), ':',
          substring($hl7datetime,13,2), '+00:00')"/>
            </xsl:when>
            <xsl:otherwise>
                <xsl:value-of select="concat(fn:toFhirDate($hl7datetime), 'T00:00:00+00:00')"/>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:function>

    <!-- ============================================================
         CODE TRANSLATION FUNCTIONS
         ============================================================ -->

    <!-- HL7 gender code (PID.8) → FHIR gender -->
    <xsl:function name="fn:toFhirGender" as="xs:string">
        <xsl:param name="hl7gender" as="xs:string"/>
        <xsl:variable name="map" select="map{
      'M': 'male',
      'F': 'female',
      'O': 'other',
      'U': 'unknown'
    }"/>
        <xsl:value-of select="if (map:contains($map, $hl7gender))
                          then map:get($map, $hl7gender)
                          else 'unknown'"/>
    </xsl:function>

    <!-- HL7 patient class (PV1.2) → FHIR encounter class code -->
    <xsl:function name="fn:toFhirEncounterClass" as="xs:string">
        <xsl:param name="hl7class" as="xs:string"/>
        <xsl:variable name="map" select="map{
      'I': 'IMP',
      'O': 'AMB',
      'E': 'EMER',
      'P': 'PRENC',
      'R': 'AMB',
      'B': 'EMER'
    }"/>
        <xsl:value-of select="if (map:contains($map, $hl7class))
                          then map:get($map, $hl7class)
                          else 'UNKNOWN'"/>
    </xsl:function>

    <!-- HL7 telecom type (XTN.2 / XTN.3) → FHIR telecom use -->
    <xsl:function name="fn:toFhirTelecomUse" as="xs:string">
        <xsl:param name="hl7use" as="xs:string"/>
        <xsl:variable name="map" select="map{
      'PRN': 'home',
      'WPN': 'work',
      'CP':  'mobile',
      'ORN': 'old',
      'EMR': 'home'
    }"/>
        <xsl:value-of select="if (map:contains($map, $hl7use))
                          then map:get($map, $hl7use)
                          else 'home'"/>
    </xsl:function>

    <!-- HL7 telecom equipment type (XTN.3) → FHIR telecom system -->
    <xsl:function name="fn:toFhirTelecomSystem" as="xs:string">
        <xsl:param name="hl7equipment" as="xs:string"/>
        <xsl:variable name="map" select="map{
      'PH':    'phone',
      'CP':    'phone',
      'FX':    'fax',
      'Internet': 'email',
      'X.400': 'email',
      'BP':    'pager',
      'SAT':   'phone'
    }"/>
        <xsl:value-of select="if (map:contains($map, $hl7equipment))
                          then map:get($map, $hl7equipment)
                          else 'phone'"/>
    </xsl:function>

    <!-- HL7 name type code (XPN.7) → FHIR name use -->
    <xsl:function name="fn:toFhirNameUse" as="xs:string">
        <xsl:param name="hl7nameType" as="xs:string"/>
        <xsl:variable name="map" select="map{
      'L': 'official',
      'D': 'usual',
      'M': 'maiden',
      'N': 'nickname',
      'T': 'temp',
      'S': 'anonymous'
    }"/>
        <xsl:value-of select="if (map:contains($map, $hl7nameType))
                          then map:get($map, $hl7nameType)
                          else 'usual'"/>
    </xsl:function>

    <!-- ============================================================
         SHARED FHIR RESOURCE TEMPLATES
         These produce FHIR resources that appear in many message types.
         Message-type-specific stylesheets call these via apply-templates.
         ============================================================ -->

    <!-- MessageHeader — driven by MSH segment -->
    <xsl:template match="hl7:MSH" mode="MessageHeader">
        <xsl:param name="eventCode"    as="xs:string"/>
        <xsl:param name="eventDisplay" as="xs:string"/>
        <xsl:param name="focusRef"     as="xs:string"/>

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
        <xsl:param name="patientId" as="xs:string"/>

        <Patient xmlns="http://hl7.org/fhir">
            <id value="patient-{$patientId}"/>

            <identifier>
                <use value="usual"/>
                <type>
                    <coding>
                        <system value="http://terminology.hl7.org/CodeSystem/v2-0203"/>
                        <code value="MR"/>
                        <display value="Medical record number"/>
                    </coding>
                </type>
                <system value="urn:oid:2.16.840.1.113883.2.4.6.3"/>
                <value value="{$patientId}"/>
            </identifier>

            <xsl:for-each select="hl7:PID.5">
                <name>
                    <use value="{fn:toFhirNameUse(hl7:XPN.7)}"/>
                    <family value="{hl7:XPN.1/hl7:FN.1}"/>
                    <xsl:if test="hl7:XPN.2"><given value="{hl7:XPN.2}"/></xsl:if>
                    <xsl:if test="hl7:XPN.3"><given value="{hl7:XPN.3}"/></xsl:if>
                </name>
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

            <xsl:for-each select="hl7:PID.13[hl7:XTN.1]">
                <telecom>
                    <system value="{fn:toFhirTelecomSystem(hl7:XTN.3)}"/>
                    <value  value="{hl7:XTN.1}"/>
                    <use    value="{fn:toFhirTelecomUse(hl7:XTN.2)}"/>
                </telecom>
            </xsl:for-each>

        </Patient>
    </xsl:template>

</xsl:stylesheet>
