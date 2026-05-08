#!/usr/bin/env bash
# _common.sh — sourced by every fhirutil script.
# Provides: HOST, PORT, BASE_URL, PATIENT_ID, TS_FHIR, post_bundle(), get_patient().

set -euo pipefail

# --- defaults ---
HOST="localhost"
PORT=8180
FHIR_USER="ADMIN"
FHIR_PASS="PASSWORD1234"

# --- parse -h / -p / --id / -u / -P from any script's args ---
PATIENT_ID=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--host)      HOST="$2";       shift 2 ;;
        -p|--port)      PORT="$2";       shift 2 ;;
        --id)           PATIENT_ID="$2"; shift 2 ;;
        -u|--user)      FHIR_USER="$2";  shift 2 ;;
        -P|--password)  FHIR_PASS="$2";  shift 2 ;;
        *) echo "Unknown option: $1  (supported: -h/--host, -p/--port, --id, -u/--user, -P/--password)" >&2; exit 2 ;;
    esac
done

CURL_AUTH=(-u "${FHIR_USER}:${FHIR_PASS}")

BASE_URL="http://${HOST}:${PORT}/viscolink/fhir"

# --- generated values ---
# FHIR instant: YYYY-MM-DDThh:mm:ssZ
TS_FHIR=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# --- helpers ---

# post_bundle <version> <facade> <xml_body>
#   version  — e.g. r4, r5, dstu3
#   facade   — e.g. fhir-to-fhir
#   xml_body — the full FHIR Bundle XML
post_bundle() {
    local version="$1"
    local facade="$2"
    local body="$3"
    local url="${BASE_URL}/${version}/${facade}"

    echo "POST ${url}"
    echo "---"
    curl --fail-with-body --silent --show-error \
        "${CURL_AUTH[@]}" \
        -X POST "$url" \
        -H "Content-Type: application/fhir+xml" \
        -H "Accept: application/fhir+xml" \
        -d "$body"
    echo
}

# get_patient <version> <facade> <id>
#   version — e.g. r4, r5, dstu3
#   facade  — e.g. fhir-to-fhir
#   id      — Patient resource ID
get_patient() {
    local version="$1"
    local facade="$2"
    local id="$3"
    local url="${BASE_URL}/${version}/${facade}/Patient/${id}"

    echo "GET ${url}"
    echo "---"
    curl --fail-with-body --silent --show-error \
        "${CURL_AUTH[@]}" \
        -X GET "$url" \
        -H "Accept: application/fhir+xml"
    echo
}
