#!/usr/bin/env bash
# emr-to-fhir.sh — Drive the fake-EMR → FHIR pipeline for one or more patients.
#
# Calls GET /viscolink/api/emr/patient/{patientId} for each supplied patient ID.
# ViscoLink queries the fake-EMR database (sp_patient_gegevens + sp_diagnoses_patient),
# transforms the result to a FHIR R4 transaction Bundle, and POSTs it to ViscoStore.
# The response (ViscoStore transaction-response Bundle) is printed with resource counts.
#
# Usage:
#   ./emr-to-fhir.sh [options] [patientId ...]
#
# Options:
#   -h / --host     ViscoLink host   (default: localhost)
#   -p / --port     ViscoLink port   (default: 8180)
#   --store-host    ViscoStore host  (default: same as --host)
#   --store-port    ViscoStore port  (default: same as --port)
#   -u / --user     ViscoStore user  (default: demo)
#   -P / --password ViscoStore pass  (default: demo)
#   --no-verify     Skip ViscoStore verification after ingestion
#   --raw           Print the raw transaction-response Bundle (no pretty-print)
#
# Examples:
#   ./emr-to-fhir.sh                              # ingest PAT-2020001 (demo default)
#   ./emr-to-fhir.sh PAT-2020001 PAT-2020002      # ingest two patients
#   ./emr-to-fhir.sh -h demo.example.com -p 443 PAT-2020001

set -euo pipefail

HOST="localhost"
PORT=8180
STORE_HOST=""
STORE_PORT=""
STORE_USER="demo"
STORE_PASS="demo"
NO_VERIFY=false
RAW=false
PATIENTS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--host)       HOST="$2";       shift 2 ;;
        -p|--port)       PORT="$2";       shift 2 ;;
        --store-host)    STORE_HOST="$2"; shift 2 ;;
        --store-port)    STORE_PORT="$2"; shift 2 ;;
        -u|--user)       STORE_USER="$2"; shift 2 ;;
        -P|--password)   STORE_PASS="$2"; shift 2 ;;
        --no-verify)     NO_VERIFY=true;  shift ;;
        --raw)           RAW=true;        shift ;;
        -*) echo "Unknown option: $1" >&2; exit 2 ;;
        *) PATIENTS+=("$1"); shift ;;
    esac
done

[[ ${#PATIENTS[@]} -eq 0 ]] && PATIENTS=("PAT-2020001")
[[ -z "$STORE_HOST" ]] && STORE_HOST="$HOST"
[[ -z "$STORE_PORT" ]] && STORE_PORT="$PORT"

LINK_BASE="http://${HOST}:${PORT}/viscolink"
CDR="http://${STORE_HOST}:${STORE_PORT}/viscostore/fhir"
CURL_CDR_AUTH=(-u "${STORE_USER}:${STORE_PASS}")

# ── Helpers ───────────────────────────────────────────────────────────────────

ok()   { printf '\033[32m✓\033[0m %s\n' "$*"; }
fail() { printf '\033[31m✗\033[0m %s\n' "$*" >&2; }
info() { printf '  %s\n' "$*"; }

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

xml_attr() {
    local xml="$1" element="$2"
    echo "$xml" | grep -o "<${element} value=\"[^\"]*\"" | head -1 \
        | sed 's/.*value="\([^"]*\)".*/\1/'
}

# Count entries by HTTP status prefix in a transaction-response Bundle.
count_status() {
    local xml="$1" prefix="$2"
    echo "$xml" | grep -cE "\"${prefix}[0-9]{2} |<status value=\"${prefix}" 2>/dev/null || true
}

# ── Per-patient ingest ────────────────────────────────────────────────────────

ingest_patient() {
    local pat_id="$1"
    local url="${LINK_BASE}/api/emr/patient/${pat_id}"

    printf '\n[%s]\n' "$pat_id"
    info "GET ${url}"

    local response http_code tmp
    tmp=$(mktemp)

    http_code=$(curl --silent --show-error \
        -o "$tmp" \
        -w "%{http_code}" \
        -X GET "$url" \
        -H "Accept: application/fhir+xml") || {
        fail "curl error for ${pat_id}"
        rm -f "$tmp"
        return 1
    }

    response=$(cat "$tmp")
    rm -f "$tmp"

    if [[ "$http_code" -lt 200 ]] || [[ "$http_code" -ge 300 ]]; then
        fail "HTTP ${http_code} for ${pat_id}"
        echo "$response" | pretty_xml >&2
        return 1
    fi

    # Count outcomes in the transaction-response Bundle
    local created updated errors
    created=$(count_status "$response" "201")
    updated=$(count_status "$response" "200")
    errors=$(count_status  "$response" "[45]")

    if [[ "$errors" -gt 0 ]]; then
        fail "HTTP ${http_code} — ${created} created, ${updated} updated, ${errors} errors"
        echo "$response" | pretty_xml >&2
        return 1
    fi

    ok "HTTP ${http_code} — ${created} created, ${updated} updated"

    if $RAW; then
        echo "$response"
    else
        echo "─── Transaction response ──────────────────────────────────────────────────"
        echo "$response" | pretty_xml
        echo "───────────────────────────────────────────────────────────────────────────"
    fi

    $NO_VERIFY && return 0

    # ── Verify in ViscoStore ──────────────────────────────────────────────────

    printf '\n  Verifying in ViscoStore...\n'

    # Patient — conditional-PUT identifier: urn:emr:pat-nr|{patientId}
    local pat_bundle
    pat_bundle=$(curl --fail-with-body --silent --show-error \
        "${CURL_CDR_AUTH[@]}" \
        -H "Accept: application/fhir+xml" \
        "${CDR}/Patient?identifier=urn%3Aemr%3Apat-nr%7C${pat_id}" 2>&1) || {
        fail "  ViscoStore Patient search failed"
        return 1
    }
    local pat_total
    pat_total=$(xml_attr "$pat_bundle" "total")
    if [[ "${pat_total:-0}" -gt 0 ]]; then
        ok "  Patient (urn:emr:pat-nr|${pat_id}): ${pat_total} found"
    else
        fail "  Patient not found in ViscoStore (identifier urn:emr:pat-nr|${pat_id})"
    fi

    # Conditions — conditional-PUT identifier: urn:emr:dgn-id
    local cond_bundle cond_total
    cond_bundle=$(curl --fail-with-body --silent --show-error \
        "${CURL_CDR_AUTH[@]}" \
        -H "Accept: application/fhir+xml" \
        "${CDR}/Condition?patient.identifier=urn%3Aemr%3Apat-nr%7C${pat_id}" 2>&1) || {
        fail "  ViscoStore Condition search failed"
        return 1
    }
    cond_total=$(xml_attr "$cond_bundle" "total")
    if [[ "${cond_total:-0}" -gt 0 ]]; then
        ok "  Conditions (patient=${pat_id}): ${cond_total} found"
    else
        info "  No Conditions found in ViscoStore for patient ${pat_id} (patient may have no diagnoses)"
    fi

    # Coverage — conditional-PUT; only present when UZOVI_CODE is set in the EMR
    local cov_bundle cov_total
    cov_bundle=$(curl --fail-with-body --silent --show-error \
        "${CURL_CDR_AUTH[@]}" \
        -H "Accept: application/fhir+xml" \
        "${CDR}/Coverage?beneficiary.identifier=urn%3Aemr%3Apat-nr%7C${pat_id}" 2>&1) || {
        fail "  ViscoStore Coverage search failed"
        return 1
    }
    cov_total=$(xml_attr "$cov_bundle" "total")
    if [[ "${cov_total:-0}" -gt 0 ]]; then
        ok "  Coverage (patient=${pat_id}): ${cov_total} found"
    else
        info "  No Coverage found (UZOVI_CODE absent for this patient — expected)"
    fi
}

# ── Main ──────────────────────────────────────────────────────────────────────

echo "ViscoLink : ${LINK_BASE}"
echo "ViscoStore: ${CDR}"
echo "Patients  : ${PATIENTS[*]}"

FAILED=0
for pat in "${PATIENTS[@]}"; do
    ingest_patient "$pat" || FAILED=$(( FAILED + 1 ))
done

echo ""
if [[ $FAILED -eq 0 ]]; then
    ok "Done — ${#PATIENTS[@]} patient(s) ingested."
else
    fail "Done — ${FAILED} of ${#PATIENTS[@]} patient(s) failed."
    exit 1
fi
