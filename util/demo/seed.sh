#!/usr/bin/env bash
# seed.sh — POST FHIR fixture bundles directly to the viscostore CDR.
#
# Each fixture is a FHIR R5 transaction Bundle (PUT entries with stable IDs)
# so the script is safe to re-run — existing resources are updated in place.
#
# Usage:
#   ./seed.sh [-h host] [-p port] [set-name ...]
#
# Arguments:
#   -h / --host   Target host        (default: localhost)
#   -p / --port   Target port        (default: 8180)
#   set-name      One or more fixture set names to load (filename without .xml).
#                 If omitted, all fixtures under fixtures/ are loaded in
#                 alphabetical order.
#
# Examples:
#   ./seed.sh                                          # load everything
#   ./seed.sh ungroupedBloodObservations               # one set
#   ./seed.sh ungroupedBloodObservations labObservationsWithoutCodes

set -euo pipefail

HOST="localhost"
PORT=8180
SETS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--host) HOST="$2"; shift 2 ;;
        -p|--port) PORT="$2"; shift 2 ;;
        *) SETS+=("$1"); shift ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURES_DIR="${SCRIPT_DIR}/fixtures"
CDR_URL="http://${HOST}:${PORT}/viscostore/fhir"

# post_fixture <file>
post_fixture() {
    local file="$1"
    local ext name
    ext="${file##*.}"
    name="$(basename "$file" ".${ext}")"

    if [[ "$ext" == "map" ]]; then
        post_fml_fixture "$file" "$name"
    else
        post_bundle_fixture "$file" "$name" "$ext"
    fi
}

# post_bundle_fixture <file> <name> <ext>
# POSTs an XML or JSON FHIR transaction Bundle to the CDR root.
post_bundle_fixture() {
    local file="$1" name="$2" ext="$3"
    local content_type created updated errors response

    case "$ext" in
        json) content_type="application/fhir+json" ;;
        *)    content_type="application/fhir+xml"  ;;
    esac

    printf '→ Seeding %-45s ' "[${name}]"

    response=$(curl --fail-with-body --silent --show-error \
        -X POST "${CDR_URL}/" \
        -H "Content-Type: ${content_type}" \
        -H "Accept: ${content_type}" \
        --data-binary "@${file}" 2>&1) || {
        echo "FAILED"
        echo "   ${response}" >&2
        return 1
    }

    # Count outcomes from the transaction-response Bundle (works for both XML and JSON)
    created=$(echo "$response" | grep -cE '"201 |<status value="201' || true)
    updated=$(echo "$response" | grep -cE '"200 |<status value="200' || true)
    errors=$(echo  "$response" | grep -cE '"[45][0-9]{2} |<status value="[45]' || true)

    if [[ $errors -gt 0 ]]; then
        echo "PARTIAL (${created} created, ${updated} updated, ${errors} errors)"
        echo "$response" | grep -E '"[45][0-9]{2} |<status value="[45]' | sed 's/^/   /' >&2
    else
        echo "OK      (${created} created, ${updated} updated, $(( created + updated )) total)"
    fi
}

# post_fml_fixture <file> <name>
# Compiles a .map FML file via POST /fhir/StructureMap/$compile (Content-Type: text/fhir-mapping).
# The endpoint is provided by FmlCompileFilter in ViscoStore.
post_fml_fixture() {
    local file="$1" name="$2"
    local http_code response

    printf '→ Compiling %-43s ' "[${name}]"

    # Write response body to a temp file so we can capture both body and HTTP status.
    local tmp
    tmp=$(mktemp)

    http_code=$(curl --silent --show-error \
        -o "$tmp" \
        -w "%{http_code}" \
        -X POST "${CDR_URL}/StructureMap/\$compile" \
        -H "Content-Type: text/fhir-mapping" \
        -H "Accept: application/fhir+xml" \
        --data-binary "@${file}" 2>&1) || {
        echo "CURL ERROR"
        cat "$tmp" >&2
        rm -f "$tmp"
        return 1
    }

    response=$(cat "$tmp")
    rm -f "$tmp"

    case "$http_code" in
        201) echo "OK      (created)" ;;
        200) echo "OK      (updated)" ;;
        *)
            echo "FAILED  (HTTP ${http_code})"
            echo "   ${response}" >&2
            return 1
            ;;
    esac
}

# Resolve the list of fixture files to load
if [[ ${#SETS[@]} -eq 0 ]]; then
    FILES=()
    while IFS= read -r -d '' f; do
        FILES+=("$f")
    done < <(find "$FIXTURES_DIR" \( -name "*.xml" -o -name "*.json" -o -name "*.map" \) -print0 | sort -z)
else
    FILES=()
    for set_name in "${SETS[@]}"; do
        match=$(find "$FIXTURES_DIR" \( -name "${set_name}.xml" -o -name "${set_name}.json" -o -name "${set_name}.map" \) | head -1)
        if [[ -z "$match" ]]; then
            echo "No fixture found for set: ${set_name}" >&2
            echo "Available sets:" >&2
            find "$FIXTURES_DIR" \( -name "*.xml" -o -name "*.json" -o -name "*.map" \) \
                | sed 's|.*/||;s|\.\(xml\|json\|map\)$||' | sort >&2 | sed 's/^/  /'
            exit 1
        fi
        FILES+=("$match")
    done
fi

if [[ ${#FILES[@]} -eq 0 ]]; then
    echo "No fixture files found under ${FIXTURES_DIR}" >&2
    exit 1
fi

echo "CDR: ${CDR_URL}/"
echo "---"
for file in "${FILES[@]}"; do
    post_fixture "$file"
done
echo "---"
echo "Done."
