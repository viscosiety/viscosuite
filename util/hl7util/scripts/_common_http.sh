#!/usr/bin/env bash
# _common_http.sh — sourced by every hl7util HTTP send script.
# Provides: HOST, PORT, VALIDATE, MSG_ID, TS, and send().

set -euo pipefail

# --- defaults ---
HOST="localhost"
PORT=80
VALIDATE="true"

# --- parse options ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--host)        HOST="$2";     shift 2 ;;
        -p|--port)        PORT="$2";     shift 2 ;;
        --no-validate)    VALIDATE="false"; shift ;;
        *) echo "Unknown option: $1  (supported: -h/--host, -p/--port, --no-validate)" >&2; exit 2 ;;
    esac
done

# --- generated values ---
TS=$(date +"%Y%m%d%H%M%S")
MSG_ID=$(date +"%Y%m%d%H%M%S%3N")

ENDPOINT="http://${HOST}:${PORT}/viscolink/api/hl7v2?validateMessage=${VALIDATE}"

# --- send helper ---
# Expands \r escape sequences to CR (0x0D) for HL7v2 segment termination,
# then POSTs the message and prints the response.
send() {
    local message="$1"
    printf '%b' "$message" | curl --fail-with-body -s -X POST \
        -H "Content-Type: x-application/hl7-v2+er7" \
        --data-binary @- \
        "$ENDPOINT"
    echo
}
