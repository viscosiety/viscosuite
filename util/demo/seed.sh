#!/usr/bin/env bash
# seed.sh — POST FHIR fixture bundles directly to the viscostore CDR.
#
# Each fixture is a FHIR R4 transaction Bundle (PUT entries with stable IDs)
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
    local ext name content_type created updated errors
    ext="${file##*.}"
    name="$(basename "$file" ".${ext}")"

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

# Resolve the list of fixture files to load
if [[ ${#SETS[@]} -eq 0 ]]; then
    FILES=()
    while IFS= read -r -d '' f; do
        FILES+=("$f")
    done < <(find "$FIXTURES_DIR" \( -name "*.xml" -o -name "*.json" \) -print0 | sort -z)
else
    FILES=()
    for set_name in "${SETS[@]}"; do
        match=$(find "$FIXTURES_DIR" \( -name "${set_name}.xml" -o -name "${set_name}.json" \) | head -1)
        if [[ -z "$match" ]]; then
            echo "No fixture found for set: ${set_name}" >&2
            echo "Available sets:" >&2
            find "$FIXTURES_DIR" \( -name "*.xml" -o -name "*.json" \) \
                | sed 's|.*/||;s|\.\(xml\|json\)$||' | sort >&2 | sed 's/^/  /'
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
