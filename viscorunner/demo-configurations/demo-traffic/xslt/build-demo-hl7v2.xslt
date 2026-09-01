<?xml version="1.0" encoding="UTF-8"?>
<!--
    Builds one pipe-delimited (ER7) HL7v2 message for the demo traffic generator.

    Input:  <variant>adt-cardio | hl7-to-xml | adt-other-department | unsupported-event | malformed</variant>
    Output: ER7 text, segments separated by CR (&#13;), ready to POST as text/plain.

    No state: the clock drives all rotation. The minute of the hour picks the
    patient (roster of five) and the ADT trigger event (A01/A02/A03), and the
    full timestamp makes MSH-10 control ids unique.
-->
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
    <xsl:output method="text" encoding="UTF-8"/>

    <xsl:variable name="cr" select="'&#13;'"/>
    <xsl:variable name="now" select="current-dateTime()"/>
    <xsl:variable name="ts" select="format-dateTime($now, '[Y0001][M01][D01][H01][m01][s01]')"/>
    <xsl:variable name="minute" select="minutes-from-dateTime($now)"/>

    <!-- Stable demo roster: the same five patients recur, so filters and
         journeys show recognisable identities instead of random noise. -->
    <xsl:variable name="roster">
        <p id="99001" name="Janssen^Emma"  dob="19840312" sex="F"/>
        <p id="99002" name="de Vries^Lucas" dob="19770825" sex="M"/>
        <p id="99003" name="Bakker^Sophie" dob="19910604" sex="F"/>
        <p id="99004" name="Visser^Daan"   dob="19620117" sex="M"/>
        <p id="99005" name="Smit^Julia"    dob="20010930" sex="F"/>
    </xsl:variable>
    <xsl:variable name="pat" select="$roster/p[($minute mod 5) + 1]"/>
    <xsl:variable name="adtEvent" select="('A01','A02','A03')[($minute mod 3) + 1]"/>

    <xsl:template name="adt">
        <xsl:param name="event"/>
        <xsl:param name="department"/>
        <xsl:value-of select="concat(
            'MSH|^~\&amp;|DEMOGEN|VISCODEMO|VISCOLINK|HOSPITAL|', $ts, '||ADT^', $event, '|DEMO-', $ts, '-', $pat/@id, '|P|2.5', $cr,
            'EVN|', $event, '|', $ts, $cr,
            'PID|1||', $pat/@id, '^^^Hospital^MR||', $pat/@name, '||', $pat/@dob, '|', $pat/@sex, $cr,
            'PV1|1|I|', $department, '^1^', ($minute mod 8) + 1, '^Hospital|||||||||||||||V-', $pat/@id, '-', $ts)"/>
    </xsl:template>

    <xsl:template match="/variant">
        <xsl:choose>

            <!-- Valid cardiology ADT — accepted end-to-end (also used for the XML-transform intake) -->
            <xsl:when test=". = ('adt-cardio', 'hl7-to-xml')">
                <xsl:call-template name="adt">
                    <xsl:with-param name="event" select="$adtEvent"/>
                    <xsl:with-param name="department" select="'CARDIO'"/>
                </xsl:call-template>
            </xsl:when>

            <!-- Valid message, wrong department — the CARDIO-only filter answers with an AR NACK -->
            <xsl:when test=". = 'adt-other-department'">
                <xsl:call-template name="adt">
                    <xsl:with-param name="event" select="'A01'"/>
                    <xsl:with-param name="department" select="'ONCO'"/>
                </xsl:call-template>
            </xsl:when>

            <!-- Lab result on the ADT intake — the event-type check answers with an AE NACK -->
            <xsl:when test=". = 'unsupported-event'">
                <xsl:value-of select="concat(
                    'MSH|^~\&amp;|DEMOLAB|VISCODEMO|VISCOLINK|HOSPITAL|', $ts, '||ORU^R01|DEMO-', $ts, '-LAB|P|2.5', $cr,
                    'PID|1||', $pat/@id, '^^^Hospital^MR||', $pat/@name, '||', $pat/@dob, '|', $pat/@sex, $cr,
                    'OBR|1||LAB-', $ts, '|2093-3^Cholesterol^LN', $cr,
                    'OBX|1|NM|2093-3^Cholesterol^LN||', 150 + ($minute * 2), '|mg/dL|&lt;200|N|||F')"/>
            </xsl:when>

            <!-- FHIR transaction bundle for the async delivery queue. The invalid
                 variant carries an unknown resource type, which ViscoStore rejects
                 with 400 — so it parks in the error store even when the store is
                 healthy. -->
            <xsl:when test=". = ('fhir-bundle', 'fhir-bundle-invalid')">
                <xsl:variable name="resourceType" select="if (. = 'fhir-bundle') then 'Patient' else 'PatientRecord'"/>
                <xsl:variable name="gender" select="if ($pat/@sex = 'F') then 'female' else 'male'"/>
                <xsl:variable name="dob" select="concat(substring($pat/@dob,1,4),'-',substring($pat/@dob,5,2),'-',substring($pat/@dob,7,2))"/>
                <xsl:value-of select="concat(
                    '{&quot;resourceType&quot;:&quot;Bundle&quot;,&quot;type&quot;:&quot;transaction&quot;,&quot;entry&quot;:[',
                    '{&quot;fullUrl&quot;:&quot;urn:uuid:demo-', $ts, '-', $pat/@id, '&quot;,',
                    '&quot;resource&quot;:{&quot;resourceType&quot;:&quot;', $resourceType, '&quot;,',
                    '&quot;identifier&quot;:[{&quot;system&quot;:&quot;urn:visco:mrn&quot;,&quot;value&quot;:&quot;', $pat/@id, '&quot;}],',
                    '&quot;name&quot;:[{&quot;family&quot;:&quot;', substring-after($pat/@name, '^'), '&quot;,&quot;given&quot;:[&quot;', substring-before($pat/@name, '^'), '&quot;]}],',
                    '&quot;gender&quot;:&quot;', $gender, '&quot;,&quot;birthDate&quot;:&quot;', $dob, '&quot;},',
                    '&quot;request&quot;:{&quot;method&quot;:&quot;PUT&quot;,&quot;url&quot;:&quot;Patient?identifier=urn:visco:mrn%7C', $pat/@id, '&quot;}}]}')"/>
            </xsl:when>

            <!-- Structurally broken ER7 — fails HL7v2 parsing, lands on the intake's ERROR exit -->
            <xsl:when test=". = 'malformed'">
                <xsl:value-of select="concat(
                    'MSH|^~\&amp;|DEMOGEN|VISCODEMO|BROKEN', $cr,
                    'ZZZ|seeded parse failure — demo traffic generator')"/>
            </xsl:when>

            <xsl:otherwise>
                <xsl:message terminate="yes">unknown demo traffic variant: <xsl:value-of select="."/></xsl:message>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

</xsl:stylesheet>
