#!/usr/bin/env bash
# emr-to-fhir-all.sh — Ingest all fake-EMR patients (PAT-2020001 … PAT-2020015).
#
# Delegates to emr-to-fhir.sh; all options are forwarded unchanged.
#
# Usage:
#   ./emr-to-fhir-all.sh [options]
#
# Options: same as emr-to-fhir.sh
#   -h / --host, -p / --port, --store-host, --store-port,
#   -u / --user, -P / --password, --no-verify, --raw

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PATIENTS=()
for i in $(seq 1 15); do
    PATIENTS+=("PAT-2020$(printf '%03d' "$i")")
done

exec "$SCRIPT_DIR/emr-to-fhir.sh" "$@" "${PATIENTS[@]}"
