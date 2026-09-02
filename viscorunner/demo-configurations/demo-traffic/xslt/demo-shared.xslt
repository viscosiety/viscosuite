<?xml version="1.0" encoding="UTF-8"?>
<!--
    Shared building blocks for the demo traffic stylesheets (included by
    build-demo-hl7v2.xslt and build-demo-fhir.xslt — declares no xsl:output,
    the including stylesheet owns that).

    No state: the clock drives all rotation. The minute of the hour picks the
    patient (roster of five) and the ADT trigger event (A01/A02/A03), and the
    full timestamp makes generated ids unique.
-->
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

    <xsl:variable name="cr" select="'&#13;'"/>
    <xsl:variable name="now" select="current-dateTime()"/>
    <xsl:variable name="ts" select="format-dateTime($now, '[Y0001][M01][D01][H01][m01][s01]')"/>
    <xsl:variable name="minute" select="minutes-from-dateTime($now)"/>

    <!-- Stable demo roster: the same five patients recur, so filters and
         journeys show recognisable identities instead of random noise.
         Names are HL7-style family^given. -->
    <xsl:variable name="roster">
        <p id="99001" name="Janssen^Emma"  dob="19840312" sex="F"/>
        <p id="99002" name="de Vries^Lucas" dob="19770825" sex="M"/>
        <p id="99003" name="Bakker^Sophie" dob="19910604" sex="F"/>
        <p id="99004" name="Visser^Daan"   dob="19620117" sex="M"/>
        <p id="99005" name="Smit^Julia"    dob="20010930" sex="F"/>
    </xsl:variable>
    <xsl:variable name="pat" select="$roster/p[($minute mod 5) + 1]"/>
    <xsl:variable name="adtEvent" select="('A01','A02','A03')[($minute mod 3) + 1]"/>
    <!-- Demo studies: patients are enrolled by roster id, so each study recurs stably -->
    <xsl:variable name="study" select="if (number($pat/@id) mod 2 = 1) then 'VISTA-2' else 'MERIDIAN-1'"/>

</xsl:stylesheet>
