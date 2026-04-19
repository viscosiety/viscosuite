#!/usr/bin/env bash
# codify-lab.sh — Ingest an inbound-zone Calcium Observation and verify FML codification.
#
# What this script does:
#   1. Upserts a demo Patient and Specimen so the Observation reference targets exist.
#   2. POSTs an inbound-zone Observation tagged data-zone|inbound, carrying the
#      ext-fml-map-ref extension that points to the StructureMap loaded by seed.sh.
#   3. The CodificationInterceptor fires after the write commits and executes
#      StructureMapUtilities.transform() to produce a codified Observation with
#      LOINC 17861-6 tagged data-zone|codified.
#   4. Waits briefly, then queries ViscoStore for the codified result using the
#      inbound-source-ref SearchParameter.
#   5. Prints the codified Observation XML and any codification-failure Task resources.
#
# Prerequisites:
#   - ViscoStore running (docker compose up from viscorunner/)
#   - fixtures/codification/labObservationCodification.xml already seeded:
#       ./seed.sh labObservationCodification
#     (loads the SearchParameter and StructureMap that the interceptor depends on)
#
# Usage:
#   ./codify-lab.sh [-h host] [-p port] [--wait seconds]
#
# Options:
#   -h / --host   ViscoStore host  (default: localhost)
#   -p / --port   ViscoStore port  (default: 8180)
#   --wait        Seconds to wait for async codification before polling (default: 5)

set -euo pipefail

HOST="localhost"
PORT=8180
WAIT=5

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--host) HOST="$2"; shift 2 ;;
        -p|--port) PORT="$2"; shift 2 ;;
        --wait)    WAIT="$2"; shift 2 ;;
        *) echo "Unknown option: $1  (supported: -h, -p, --wait)" >&2; exit 2 ;;
    esac
done

CDR="http://${HOST}:${PORT}/viscostore/fhir"

# ── Helpers ──────────────────────────────────────────────────────────────────

ok()   { printf '\033[32m✓\033[0m %s\n' "$*"; }
fail() { printf '\033[31m✗\033[0m %s\n' "$*" >&2; }
info() { printf '  %s\n' "$*"; }

post_xml() {
    local url="$1" body="$2"
    curl --fail-with-body --silent --show-error \
        -X POST "$url" \
        -H "Content-Type: application/fhir+xml" \
        -H "Accept: application/fhir+xml" \
        --data-binary "$body"
}

put_xml() {
    local url="$1" body="$2"
    curl --fail-with-body --silent --show-error \
        -X PUT "$url" \
        -H "Content-Type: application/fhir+xml" \
        -H "Accept: application/fhir+xml" \
        --data-binary "$body"
}

get_xml() {
    curl --fail-with-body --silent --show-error \
        -H "Accept: application/fhir+xml" \
        "$1"
}

# Extract the value attribute of the first matching FHIR element.
# Works for simple elements like <id value="abc"/> or <total value="3"/>.
xml_attr() {
    local xml="$1" element="$2"
    echo "$xml" | grep -o "<${element} value=\"[^\"]*\"" | head -1 \
        | sed 's/.*value="\([^"]*\)".*/\1/'
}

pretty_xml() {
    if command -v xmllint &>/dev/null; then
        xmllint --format - 2>/dev/null || cat
    elif command -v python3 &>/dev/null; then
        python3 -c "
import sys, xml.dom.minidom
try:
    raw = sys.stdin.read()
    print(xml.dom.minidom.parseString(raw).toprettyxml(indent='  '))
except Exception:
    sys.stdout.write(raw)
" 2>/dev/null || cat
    else
        cat
    fi
}

# ── Step 1: Upsert demo Patient ───────────────────────────────────────────────

echo "[$CDR]"
echo "---"
echo "Step 1/4  Upserting demo Patient..."

PATIENT_XML='<?xml version="1.0" encoding="UTF-8"?>
<Patient xmlns="http://hl7.org/fhir">
  <id value="pat-codify-demo-001"/>
  <meta>
    <tag>
      <system value="http://terminology.viscosiety.com/fixture"/>
      <code value="codify-lab-demo"/>
    </tag>
  </meta>
  <identifier>
    <system value="http://example.nl/fhir/NamingSystem/bsn"/>
    <value value="999900099"/>
  </identifier>
  <name>
    <family value="Jansen"/>
    <given value="Pieter"/>
  </name>
  <gender value="male"/>
  <birthDate value="1975-08-14"/>
</Patient>'

put_xml "${CDR}/Patient/pat-codify-demo-001" "$PATIENT_XML" > /dev/null \
    && ok "Patient/pat-codify-demo-001 upserted" \
    || { fail "Patient upsert failed"; exit 1; }

# ── Step 2: Upsert demo Specimen ─────────────────────────────────────────────

echo "Step 2/4  Upserting demo Specimen (Lithium Heparine Plasma)..."

SPECIMEN_XML='<?xml version="1.0" encoding="UTF-8"?>
<Specimen xmlns="http://hl7.org/fhir">
  <id value="spec-codify-plasma-001"/>
  <meta>
    <tag>
      <system value="http://terminology.viscosiety.com/fixture"/>
      <code value="codify-lab-demo"/>
    </tag>
  </meta>
  <type>
    <coding>
      <system value="http://snomed.info/sct"/>
      <code value="119361006"/>
      <display value="Plasma specimen"/>
    </coding>
    <text value="Lithium Heparine Plasma"/>
  </type>
  <subject>
    <reference value="Patient/pat-codify-demo-001"/>
  </subject>
  <collection>
    <collectedDateTime value="2026-04-07T09:15:00+02:00"/>
  </collection>
</Specimen>'

put_xml "${CDR}/Specimen/spec-codify-plasma-001" "$SPECIMEN_XML" > /dev/null \
    && ok "Specimen/spec-codify-plasma-001 upserted" \
    || { fail "Specimen upsert failed"; exit 1; }

# ── Step 3: POST inbound-zone Observation ────────────────────────────────────

echo "Step 3/4  POSTing inbound-zone Calcium Observation..."
echo ""
info "  meta.tag       : https://ig.viscosiety.com/CodeSystem/data-zone | inbound"
info "  ext-fml-map-ref: https://ig.viscosiety.com/StructureMap/InboundLabObservation-to-Observation"
info "  code           : NullFlavor/OTH  text='Calcium'"
info "  valueQuantity  : 2.41 mmol/L"
echo ""

INBOUND_OBS_XML='<?xml version="1.0" encoding="UTF-8"?>
<Observation xmlns="http://hl7.org/fhir">
  <meta>
    <tag>
      <system value="https://ig.viscosiety.com/CodeSystem/data-zone"/>
      <code value="inbound"/>
      <display value="Inbound Zone"/>
    </tag>
  </meta>
  <extension url="https://ig.viscosiety.com/StructureDefinition/ext-fml-map-ref">
    <valueCanonical value="https://ig.viscosiety.com/StructureMap/InboundLabObservation-to-Observation"/>
  </extension>
  <status value="final"/>
  <category>
    <coding>
      <system value="http://terminology.hl7.org/CodeSystem/observation-category"/>
      <code value="laboratory"/>
      <display value="Laboratory"/>
    </coding>
  </category>
  <code>
    <coding>
      <system value="http://terminology.hl7.org/CodeSystem/v3-NullFlavor"/>
      <code value="OTH"/>
      <display value="Other"/>
    </coding>
    <text value="Calcium"/>
  </code>
  <subject>
    <reference value="Patient/pat-codify-demo-001"/>
  </subject>
  <effectiveDateTime value="2026-04-07T09:15:00+02:00"/>
  <valueQuantity>
    <value value="2.41"/>
    <unit value="mmol/L"/>
    <system value="http://unitsofmeasure.org"/>
    <code value="mmol/L"/>
  </valueQuantity>
  <specimen>
    <reference value="Specimen/spec-codify-plasma-001"/>
  </specimen>
  <referenceRange>
    <low>
      <value value="2.15"/>
      <unit value="mmol/L"/>
      <system value="http://unitsofmeasure.org"/>
      <code value="mmol/L"/>
    </low>
    <high>
      <value value="2.55"/>
      <unit value="mmol/L"/>
      <system value="http://unitsofmeasure.org"/>
      <code value="mmol/L"/>
    </high>
  </referenceRange>
</Observation>'

INBOUND_RESPONSE=$(post_xml "${CDR}/Observation" "$INBOUND_OBS_XML") || {
    fail "Failed to POST inbound Observation"
    echo "$INBOUND_RESPONSE" >&2
    exit 1
}

INBOUND_ID=$(xml_attr "$INBOUND_RESPONSE" "id")
if [[ -z "$INBOUND_ID" ]]; then
    fail "Could not extract Observation ID from response"
    echo "$INBOUND_RESPONSE" >&2
    exit 1
fi

ok "Inbound Observation created: Observation/${INBOUND_ID}"
info "CodificationInterceptor will fire afterCommit() → StructureMapUtilities.transform()"

# ── Step 4: Poll for codified result ─────────────────────────────────────────

echo ""
echo "Step 4/4  Waiting ${WAIT}s for FML codification..."
sleep "$WAIT"

CODIFIED_SEARCH="${CDR}/Observation?inbound-source-ref=Observation/${INBOUND_ID}&_tag=https://ig.viscosiety.com/CodeSystem/data-zone%7Ccodified"
CODIFIED_BUNDLE=$(get_xml "$CODIFIED_SEARCH") || {
    fail "Search for codified Observation failed"
    exit 1
}

TOTAL=$(xml_attr "$CODIFIED_BUNDLE" "total")

echo ""
if [[ -z "$TOTAL" ]] || [[ "$TOTAL" -eq 0 ]] 2>/dev/null; then
    fail "No codified Observation found for Observation/${INBOUND_ID}"
    echo ""
    echo "  Query: $CODIFIED_SEARCH"
    echo ""
    echo "  Checking for codification-failure Task resources..."
    TASK_BUNDLE=$(get_xml "${CDR}/Task?code=codification-failure&focus=Observation/${INBOUND_ID}" 2>/dev/null || true)
    TASK_TOTAL=$(xml_attr "$TASK_BUNDLE" "total")
    if [[ -n "$TASK_TOTAL" ]] && [[ "$TASK_TOTAL" -gt 0 ]] 2>/dev/null; then
        echo "$TASK_BUNDLE" | pretty_xml
    else
        echo "  No Task resources found either."
        echo "  Check the ViscoStore application log for WARN/ERROR from CodificationInterceptor."
        echo "  Common cause: SearchParameter or StructureMap not yet seeded — run:"
        echo "    ./seed.sh labObservationCodification"
    fi
    exit 1
fi

ok "Codified Observation found  (total: ${TOTAL})"
echo ""
echo "─── Codified Observation ────────────────────────────────────────────────────"
# Extract the first entry's resource element, then format it.
# python3 strips the element out; xmllint re-indents cleanly from scratch.
EXTRACTED=$(echo "$CODIFIED_BUNDLE" | python3 -c "
import sys, xml.etree.ElementTree as ET
ns = 'http://hl7.org/fhir'
root = ET.fromstring(sys.stdin.read())
resource_el = root.find(f'{{{ns}}}entry/{{{ns}}}resource')
if resource_el is not None and len(resource_el):
    ET.register_namespace('', ns)
    print(ET.tostring(resource_el[0], encoding='unicode', xml_declaration=False))
" 2>/dev/null)

if [[ -n "$EXTRACTED" ]]; then
    if command -v xmllint &>/dev/null; then
        echo "$EXTRACTED" | xmllint --format - 2>/dev/null || echo "$EXTRACTED"
    else
        echo "$EXTRACTED" | pretty_xml
    fi
else
    echo "$CODIFIED_BUNDLE" | pretty_xml
fi
echo "─────────────────────────────────────────────────────────────────────────────"
echo ""
echo "Inbound  : ${CDR}/Observation/${INBOUND_ID}"
echo "Codified : ${CDR}/Observation?inbound-source-ref=Observation/${INBOUND_ID}&_tag=https://ig.viscosiety.com/CodeSystem/data-zone%7Ccodified"
echo ""
ok "Done."
