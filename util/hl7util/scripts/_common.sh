#!/usr/bin/env bash
# _common.sh — sourced by every hl7util send script.
# Provides: HOST, PORT, MSG_ID, TS (HL7 timestamp), TS_FHIR, and send().

set -euo pipefail

# --- defaults ---
HOST="localhost"
PORT=2575

# --- parse -h / -p from any script's args ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--host) HOST="$2"; shift 2 ;;
        -p|--port) PORT="$2"; shift 2 ;;
        *) echo "Unknown option: $1  (supported: -h/--host, -p/--port)" >&2; exit 2 ;;
    esac
done

# --- generated values ---
# HL7v2 timestamp: YYYYMMDDHHmmss
TS=$(date +"%Y%m%d%H%M%S")
# Message control ID: script-basename + timestamp, e.g. ADT-A01-20260308143022
SCRIPT_BASE=$(basename "${BASH_SOURCE[1]}" .sh)
MSG_ID="${SCRIPT_BASE}-${TS}"

# --- JAR path (relative to this script's location) ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[1]}")" && pwd)"
JAR="${SCRIPT_DIR}/../target/hl7util.jar"

if [[ ! -f "$JAR" ]]; then
    echo "hl7util.jar not found at $JAR — run: mvn package -pl util/hl7util" >&2
    exit 2
fi

# --- send helper ---
send() {
    local message="$1"
    java -jar "$JAR" -h "$HOST" -p "$PORT" --verbose -m "$message"
}
