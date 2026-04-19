<?xml version="1.0" encoding="UTF-8"?>
<!--
    EMR → FHIR R4 transaction Bundle

    Context document : XML resultset from emr.sp_diagnoses_patient
    $patient-xml     : XML resultset string from emr.sp_patient_gegevens (parsed with parse-xml())
    $pat-nr          : The raw patient number, e.g. PAT-2020001

    F!F XML resultset format (outputFormat="XML"):
        <result>
          <fielddefinition>
            <field name="COLUMN_NAME" type="..." .../>
          </fielddefinition>
          <rowset>
            <row>
              <COLUMN_NAME>value</COLUMN_NAME>
              ...
            </row>
          </rowset>
        </result>
    Column values are elements whose tag name equals the (uppercased) column name.

    Produces a transaction Bundle with:
      - one Patient entry       (conditional PUT on urn:emr:pat-nr identifier)
      - one Coverage entry      (conditional PUT on policy number; only when UZOVI_CODE is present)
      - N Condition entries     (conditional PUT on urn:emr:dgn-id identifier, one per diagnosis row)

    NAMESPACE NOTE: literal result elements carry xmlns="http://hl7.org/fhir", which Saxon
    applies as the XPath default namespace inside AVTs on those elements.  All field lookups
    are therefore bound to xsl:variables BEFORE the first FHIR LRE so the select expressions
    run in a namespace-clean XSLT context (no default namespace).
-->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:emr="urn:viscosiety:emr-to-fhir"
    exclude-result-prefixes="xs emr">

    <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

    <!-- ═══════════════════════════════════════════════════════════════ -->
    <!-- PARAMETERS                                                       -->
    <!-- ═══════════════════════════════════════════════════════════════ -->

    <xsl:param name="patient-xml" as="xs:string"/>
    <xsl:param name="pat-nr"      as="xs:string"/>

    <!-- Parse the patient resultset string into a navigable node tree.
         Evaluated at stylesheet level: no FHIR default namespace in scope.
         Row data uses element names as field tags: <PAT_NR>value</PAT_NR> -->
    <xsl:variable name="pat" select="parse-xml($patient-xml)//rowset/row[1]"/>

    <!-- ═══════════════════════════════════════════════════════════════ -->
    <!-- ROOT                                                             -->
    <!-- ═══════════════════════════════════════════════════════════════ -->

    <xsl:template match="/">
        <Bundle xmlns="http://hl7.org/fhir">
            <type>
                <value value="transaction"/>
            </type>

            <xsl:call-template name="patient-entry"/>

            <xsl:if test="normalize-space($pat/UZOVI_CODE) != ''">
                <xsl:call-template name="coverage-entry"/>
            </xsl:if>

            <xsl:apply-templates select="//rowset/row" mode="condition-entry"/>
        </Bundle>
    </xsl:template>

    <!-- ═══════════════════════════════════════════════════════════════ -->
    <!-- PATIENT  (sp_patient_gegevens → Patient)                        -->
    <!-- ═══════════════════════════════════════════════════════════════ -->

    <xsl:template name="patient-entry">
        <xsl:variable name="full-url"
            select="concat('urn:uuid:patient-', translate($pat-nr, '-', ''))"/>

        <!-- Bind all patient fields before entering FHIR namespace scope.
             Row data format: <COL_NAME>value</COL_NAME> -->
        <xsl:variable name="v-pat-nr"          select="$pat/PAT_NR"/>
        <xsl:variable name="v-bsn"             select="$pat/BSN"/>
        <xsl:variable name="v-actief"          select="$pat/PAT_ACTIEF_VLAG"/>
        <xsl:variable name="v-naam-volledig"   select="$pat/NAAM_VOLLEDIG"/>
        <xsl:variable name="v-achternaam"      select="$pat/ACHTERNAAM"/>
        <xsl:variable name="v-roepnaam"        select="$pat/ROEPNAAM"/>
        <xsl:variable name="v-voorletters"     select="$pat/VOORLETTERS"/>
        <xsl:variable name="v-email"           select="$pat/EMAIL"/>
        <xsl:variable name="v-tel-mobiel"      select="$pat/TEL_MOBIEL"/>
        <xsl:variable name="v-tel-vast"        select="$pat/TEL_VAST"/>
        <xsl:variable name="v-geslacht-code"   select="$pat/GESLACHT_CODE"/>
        <xsl:variable name="v-geboortedatum"   select="$pat/GEBOORTEDATUM"/>
        <xsl:variable name="v-straat"          select="$pat/STRAAT"/>
        <xsl:variable name="v-adres-volledig"  select="$pat/ADRES_VOLLEDIG"/>
        <xsl:variable name="v-huisnummer"      select="$pat/HUISNUMMER"/>
        <xsl:variable name="v-huisnummer-toev" select="$pat/HUISNUMMER_TOEV"/>
        <xsl:variable name="v-woonplaats"      select="$pat/WOONPLAATS"/>
        <xsl:variable name="v-postcode"        select="$pat/POSTCODE"/>
        <xsl:variable name="v-land"            select="$pat/LAND"/>
        <xsl:variable name="v-huisarts-agb"    select="$pat/HUISARTS_AGB"/>
        <xsl:variable name="v-huisarts-naam"   select="$pat/HUISARTS_NAAM"/>
        <xsl:variable name="v-samengevoegd"    select="$pat/SAMENGEVOEGD_MET"/>

        <entry xmlns="http://hl7.org/fhir">
            <fullUrl value="{$full-url}"/>
            <resource>
                <Patient>
                    <identifier>
                        <system value="urn:emr:pat-nr"/>
                        <value value="{$v-pat-nr}"/>
                    </identifier>

                    <xsl:if test="normalize-space($v-bsn) != ''">
                        <identifier>
                            <system value="http://fhir.nl/fhir/NamingSystem/bsn"/>
                            <value value="{$v-bsn}"/>
                        </identifier>
                    </xsl:if>

                    <active value="{if ($v-actief = '1') then 'true' else 'false'}"/>

                    <name>
                        <text   value="{$v-naam-volledig}"/>
                        <family value="{$v-achternaam}"/>
                        <xsl:if test="normalize-space($v-roepnaam) != ''">
                            <given value="{$v-roepnaam}"/>
                        </xsl:if>
                        <xsl:if test="normalize-space($v-voorletters) != ''">
                            <given value="{$v-voorletters}"/>
                        </xsl:if>
                    </name>

                    <xsl:if test="normalize-space($v-email) != ''">
                        <telecom>
                            <system value="email"/>
                            <value value="{$v-email}"/>
                        </telecom>
                    </xsl:if>
                    <xsl:if test="normalize-space($v-tel-mobiel) != ''">
                        <telecom>
                            <system value="phone"/>
                            <use   value="mobile"/>
                            <value value="{$v-tel-mobiel}"/>
                        </telecom>
                    </xsl:if>
                    <xsl:if test="normalize-space($v-tel-vast) != ''">
                        <telecom>
                            <system value="phone"/>
                            <use   value="home"/>
                            <value value="{$v-tel-vast}"/>
                        </telecom>
                    </xsl:if>

                    <gender value="{emr:gender($v-geslacht-code)}"/>

                    <xsl:if test="normalize-space($v-geboortedatum) != ''">
                        <birthDate value="{$v-geboortedatum}"/>
                    </xsl:if>

                    <xsl:if test="normalize-space($v-straat) != ''">
                        <address>
                            <text value="{$v-adres-volledig}"/>
                            <line value="{string-join(
                                ($v-straat, $v-huisnummer, $v-huisnummer-toev)
                                    [normalize-space(.) != ''], ' ')}"/>
                            <city       value="{$v-woonplaats}"/>
                            <postalCode value="{$v-postcode}"/>
                            <xsl:if test="normalize-space($v-land) != ''">
                                <country value="{$v-land}"/>
                            </xsl:if>
                        </address>
                    </xsl:if>

                    <xsl:if test="normalize-space($v-huisarts-agb) != ''">
                        <generalPractitioner>
                            <identifier>
                                <system value="http://fhir.nl/fhir/NamingSystem/agb-z"/>
                                <value  value="{$v-huisarts-agb}"/>
                            </identifier>
                            <display value="{$v-huisarts-naam}"/>
                        </generalPractitioner>
                    </xsl:if>

                    <xsl:if test="normalize-space($v-samengevoegd) != ''">
                        <link>
                            <other>
                                <identifier>
                                    <system value="urn:emr:pat-nr"/>
                                    <value  value="{$v-samengevoegd}"/>
                                </identifier>
                            </other>
                            <type value="replaced-by"/>
                        </link>
                    </xsl:if>
                </Patient>
            </resource>
            <request>
                <method value="PUT"/>
                <url    value="Patient?identifier=urn:emr:pat-nr|{$pat-nr}"/>
            </request>
        </entry>
    </xsl:template>

    <!-- ═══════════════════════════════════════════════════════════════ -->
    <!-- COVERAGE  (sp_patient_gegevens → Coverage)                      -->
    <!-- ═══════════════════════════════════════════════════════════════ -->

    <xsl:template name="coverage-entry">
        <xsl:variable name="full-url"
            select="concat('urn:uuid:coverage-', translate($pat-nr, '-', ''))"/>
        <xsl:variable name="patient-ref"
            select="concat('urn:uuid:patient-', translate($pat-nr, '-', ''))"/>

        <!-- Bind coverage fields before entering FHIR namespace scope -->
        <xsl:variable name="v-polisnummer"        select="$pat/POLISNUMMER"/>
        <xsl:variable name="v-verzekering-geldig"  select="$pat/VERZEKERING_GELDIG"/>
        <xsl:variable name="v-uzovi-code"         select="$pat/UZOVI_CODE"/>
        <xsl:variable name="v-verzekeraar"        select="$pat/VERZEKERAAR"/>

        <entry xmlns="http://hl7.org/fhir">
            <fullUrl value="{$full-url}"/>
            <resource>
                <Coverage>
                    <xsl:if test="normalize-space($v-polisnummer) != ''">
                        <identifier>
                            <value value="{$v-polisnummer}"/>
                        </identifier>
                    </xsl:if>

                    <status value="{if ($v-verzekering-geldig = 'true') then 'active' else 'cancelled'}"/>

                    <beneficiary>
                        <reference value="{$patient-ref}"/>
                    </beneficiary>

                    <payor>
                        <identifier>
                            <system value="http://fhir.nl/fhir/NamingSystem/uzovi"/>
                            <value  value="{$v-uzovi-code}"/>
                        </identifier>
                        <xsl:if test="normalize-space($v-verzekeraar) != ''">
                            <display value="{$v-verzekeraar}"/>
                        </xsl:if>
                    </payor>
                </Coverage>
            </resource>
            <request>
                <method value="PUT"/>
                <url value="{
                    if (normalize-space($v-polisnummer) != '')
                    then concat('Coverage?identifier=', $v-polisnummer)
                    else concat('Coverage?beneficiary.identifier=urn:emr:pat-nr|', $pat-nr)
                }"/>
            </request>
        </entry>
    </xsl:template>

    <!-- ═══════════════════════════════════════════════════════════════ -->
    <!-- CONDITION  (sp_diagnoses_patient → Condition)                   -->
    <!-- ═══════════════════════════════════════════════════════════════ -->

    <xsl:template match="row" mode="condition-entry">
        <xsl:variable name="patient-ref"
            select="concat('urn:uuid:patient-', translate($pat-nr, '-', ''))"/>

        <!-- Bind all condition fields before entering FHIR namespace scope.
             Row data format: <COL_NAME>value</COL_NAME> -->
        <xsl:variable name="v-dgn-id"           select="DGN_ID"/>
        <xsl:variable name="v-dgn-status-code"  select="DGN_STATUS_CODE"/>
        <xsl:variable name="v-icd10-versie"     select="ICD10_VERSIE"/>
        <xsl:variable name="v-icd10-code"       select="ICD10_CODE"/>
        <xsl:variable name="v-dgn-omschrijving" select="DGN_OMSCHRIJVING"/>
        <xsl:variable name="v-dgn-datum"        select="DGN_DATUM"/>
        <xsl:variable name="v-dgn-eind-datum"   select="DGN_EIND_DATUM"/>
        <xsl:variable name="v-zvl-agb"          select="ZVL_AGB"/>
        <xsl:variable name="v-behandelaar-naam" select="BEHANDELAAR_NAAM"/>

        <entry xmlns="http://hl7.org/fhir">
            <fullUrl value="urn:uuid:condition-{$v-dgn-id}"/>
            <resource>
                <Condition>
                    <identifier>
                        <system value="urn:emr:dgn-id"/>
                        <value  value="{$v-dgn-id}"/>
                    </identifier>

                    <clinicalStatus>
                        <coding>
                            <system value="http://terminology.hl7.org/CodeSystem/condition-clinical"/>
                            <code   value="{if ($v-dgn-status-code = '1') then 'active' else 'resolved'}"/>
                        </coding>
                    </clinicalStatus>

                    <code>
                        <coding>
                            <system  value="http://hl7.org/fhir/sid/icd-10-nl"/>
                            <xsl:if test="normalize-space($v-icd10-versie) != ''">
                                <version value="{$v-icd10-versie}"/>
                            </xsl:if>
                            <code    value="{$v-icd10-code}"/>
                            <display value="{$v-dgn-omschrijving}"/>
                        </coding>
                        <text value="{$v-dgn-omschrijving}"/>
                    </code>

                    <subject>
                        <reference value="{$patient-ref}"/>
                    </subject>

                    <xsl:if test="normalize-space($v-dgn-datum) != ''">
                        <onsetDateTime value="{$v-dgn-datum}"/>
                    </xsl:if>

                    <xsl:if test="normalize-space($v-dgn-eind-datum) != ''">
                        <abatementDateTime value="{$v-dgn-eind-datum}"/>
                    </xsl:if>

                    <xsl:if test="normalize-space($v-zvl-agb) != ''">
                        <asserter>
                            <identifier>
                                <system value="http://fhir.nl/fhir/NamingSystem/agb-z"/>
                                <value  value="{$v-zvl-agb}"/>
                            </identifier>
                            <display value="{$v-behandelaar-naam}"/>
                        </asserter>
                    </xsl:if>
                </Condition>
            </resource>
            <request>
                <method value="PUT"/>
                <url    value="Condition?identifier=urn:emr:dgn-id|{$v-dgn-id}"/>
            </request>
        </entry>
    </xsl:template>

    <!-- ═══════════════════════════════════════════════════════════════ -->
    <!-- HELPER: gender code → FHIR gender value (§2.9)                  -->
    <!-- ═══════════════════════════════════════════════════════════════ -->

    <xsl:function name="emr:gender" as="xs:string">
        <xsl:param name="code" as="xs:string?"/>
        <xsl:sequence select="
            if      ($code = '1') then 'male'
            else if ($code = '2') then 'female'
            else if ($code = '3') then 'other'
            else                       'unknown'"/>
    </xsl:function>

</xsl:stylesheet>
