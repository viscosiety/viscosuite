<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:fhir="http://hl7.org/fhir"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                exclude-result-prefixes="fhir xs">

    <!--
      enrich-loinc.xslt
      =================
      Adds LOINC codings to FHIR R4 Observation resources that carry only a
      NullFlavor/OTH code with a code.text value.

      The stylesheet performs a specimen-aware lookup: it resolves the referenced
      Specimen resource within the same Bundle to find the specimen material type,
      then prefers a mapping row that matches both the observation text AND the
      specimen type. If no specimen-specific row is found it falls back to a row
      that has an empty specimen field (i.e. a text-only match).

      When no mapping row is found the Observation is left unchanged.
      Observations that already carry a LOINC coding are left unchanged.

      When a mapping IS found:
        - The NullFlavor/OTH placeholder coding is replaced by the LOINC coding.
        - A meta.tag is added to record that the facade appended the code
          (system: http://terminology.viscosiety.com/enrichment, code: loinc-enriched).
          If the Observation already has a <meta> element the tag is appended to
          it; otherwise a minimal <meta> is inserted as the first child.

      Input:   FHIR R4 Bundle XML (transaction or searchset)
      Params:
        $loincMapping — XML string produced by F!F FixedQuerySender for:
                        SELECT text, specimen, code, display FROM loinc_mapping
                        ORDER BY text, specimen
                        Root element: <result><rowset><row number="N">
                          <field name="text|TEXT">…</field>
                          <field name="specimen|SPECIMEN">…</field>
                          <field name="code|CODE">…</field>
                          <field name="display|DISPLAY">…</field>
                        </row></rowset></result>
                        Field names are matched case-insensitively to handle both
                        PostgreSQL (lowercase) and JDBC drivers that uppercase them.
      Output:  Bundle with LOINC <coding> elements injected into matching Observations
               and a meta.tag on every enriched Observation.

      Specimen URL resolution handles three reference forms:
        1. Exact fullUrl match   — "urn:uuid:…" or any absolute/relative URL
        2. Relative path match   — "Specimen/id" against "http://…/fhir/Specimen/id"
        3. Resource id match     — urn:uuid: prefix added to Specimen.id
    -->

    <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

    <!-- SQL result XML string from FixedQuerySender -->
    <xsl:param name="loincMapping" as="xs:string" select="''"/>

    <!-- Parse the SQL result into a navigable document once -->
    <xsl:variable name="mapping" as="document-node()?"
                  select="if (normalize-space($loincMapping) != '') then parse-xml($loincMapping) else ()"/>

    <!-- ── Identity transform ──────────────────────────────────────────────── -->
    <xsl:template match="@* | node()">
        <xsl:copy>
            <xsl:apply-templates select="@* | node()"/>
        </xsl:copy>
    </xsl:template>

    <!-- ── Observation: resolve LOINC row once, delegate to child templates ── -->
    <!--
        Matches Observations that carry a NullFlavor/OTH placeholder and no
        existing LOINC coding.  The resolved $loincRow is tunnelled to the
        fhir:meta and fhir:code child templates so the lookup is not repeated.
    -->
    <xsl:template match="fhir:Observation[
        fhir:code/fhir:coding[
            fhir:system/@value = 'http://terminology.hl7.org/CodeSystem/v3-NullFlavor'
            and fhir:code/@value = 'OTH'
        ]
        and normalize-space(fhir:code/fhir:text/@value) != ''
        and not(fhir:code/fhir:coding/fhir:system/@value = 'http://loinc.org')
    ]">
        <xsl:variable name="codeText"    select="fhir:code/fhir:text/@value"/>
        <xsl:variable name="specimenRef" select="fhir:specimen/fhir:reference/@value"/>

        <xsl:variable name="specimenEntry" as="element(fhir:entry)?"
                      select="(
                          root()/fhir:Bundle/fhir:entry[
                              fhir:fullUrl/@value = $specimenRef
                          ],
                          root()/fhir:Bundle/fhir:entry[
                              ends-with(fhir:fullUrl/@value, concat('/', $specimenRef))
                          ],
                          root()/fhir:Bundle/fhir:entry[
                              fhir:resource/fhir:Specimen/fhir:id/@value != ''
                              and concat('urn:uuid:', fhir:resource/fhir:Specimen/fhir:id/@value) = $specimenRef
                          ]
                      )[1]"/>

        <xsl:variable name="specimenText" as="xs:string"
                      select="($specimenEntry/fhir:resource/fhir:Specimen/fhir:type/fhir:text/@value, '')[1]"/>

        <xsl:variable name="loincRow" as="element(row)?"
                      select="if (exists($mapping)) then (
                          $mapping//row[
                              field[lower-case(@name) = 'text']        = $codeText
                              and field[lower-case(@name) = 'specimen'] = $specimenText
                          ],
                          $mapping//row[
                              field[lower-case(@name) = 'text']        = $codeText
                              and field[lower-case(@name) = 'specimen'] = ''
                          ]
                      )[1] else ()"/>

        <xsl:copy>
            <xsl:apply-templates select="@*"/>

            <!-- If no <meta> exists and a mapping was found, insert one as the first
                 child so the provenance tag precedes all clinical content. -->
            <xsl:if test="exists($loincRow) and not(fhir:meta)">
                <meta xmlns="http://hl7.org/fhir">
                    <tag>
                        <system  value="http://terminology.viscosiety.com/enrichment"/>
                        <code    value="loinc-enriched"/>
                        <display value="LOINC code appended by viscoSuite loinc-enriched facade"/>
                    </tag>
                </meta>
            </xsl:if>

            <!-- Process all children in document order. fhir:meta and fhir:code are
                 handled by the specialised templates below via the tunnel parameter. -->
            <xsl:apply-templates select="node()">
                <xsl:with-param name="loincRow" select="$loincRow" tunnel="yes"/>
            </xsl:apply-templates>
        </xsl:copy>
    </xsl:template>

    <!-- ── Append enrichment tag to an existing meta element ───────────────── -->
    <!--
        Fires for fhir:meta that is a direct child of any fhir:Observation.
        $loincRow arrives via tunnel from the Observation template above when the
        Observation is being enriched; it is absent (empty sequence) for all other
        Observations processed by the identity transform, in which case meta is
        copied unchanged.
    -->
    <xsl:template match="fhir:Observation/fhir:meta">
        <xsl:param name="loincRow" as="element(row)?" tunnel="yes" select="()"/>
        <xsl:copy>
            <xsl:apply-templates select="@* | node()"/>
            <xsl:if test="exists($loincRow)">
                <tag xmlns="http://hl7.org/fhir">
                    <system  value="http://terminology.viscosiety.com/enrichment"/>
                    <code    value="loinc-enriched"/>
                    <display value="LOINC code appended by viscoSuite loinc-enriched facade"/>
                </tag>
            </xsl:if>
        </xsl:copy>
    </xsl:template>

    <!-- ── Replace NullFlavor/OTH placeholder with the LOINC coding ────────── -->
    <!--
        $loincRow arrives via tunnel from the Observation template.
        When present: drops the NullFlavor/OTH coding and injects the LOINC one.
        When absent:  copies the code element unchanged (no mapping found).
    -->
    <xsl:template match="fhir:Observation/fhir:code[
        fhir:coding[
            fhir:system/@value = 'http://terminology.hl7.org/CodeSystem/v3-NullFlavor'
            and fhir:code/@value = 'OTH'
        ]
        and normalize-space(fhir:text/@value) != ''
        and not(fhir:coding/fhir:system/@value = 'http://loinc.org')
    ]">
        <xsl:param name="loincRow" as="element(row)?" tunnel="yes" select="()"/>
        <xsl:copy>
            <!-- Keep codings other than NullFlavor/OTH; drop it only when enriched. -->
            <xsl:apply-templates select="fhir:coding[
                not(exists($loincRow))
                or not(
                    fhir:system/@value = 'http://terminology.hl7.org/CodeSystem/v3-NullFlavor'
                    and fhir:code/@value = 'OTH'
                )
            ]"/>

            <xsl:if test="exists($loincRow)">
                <coding xmlns="http://hl7.org/fhir">
                    <system  value="http://loinc.org"/>
                    <code    value="{$loincRow/field[lower-case(@name) = 'code']}"/>
                    <display value="{$loincRow/field[lower-case(@name) = 'display']}"/>
                </coding>
            </xsl:if>

            <!-- Preserve text and any remaining non-coding children. -->
            <xsl:apply-templates select="node() except fhir:coding"/>
        </xsl:copy>
    </xsl:template>

</xsl:stylesheet>
