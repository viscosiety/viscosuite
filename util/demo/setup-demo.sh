#!/usr/bin/env bash
# setup-demo.sh — Seed a full demo environment in one step.
#
# Calls, in order:
#   1. load-loinc-mapping.sh  — loads loinc-mapping.csv into the viscolink PostgreSQL table
#   2. load-fhir-fixtures.sh  — POSTs FHIR fixture bundles to the viscostore CDR
#
# Usage:
#   ./setup-demo.sh [options]
#
# ViscoStore (FHIR CDR) options:
#   --store-host HOST    Viscostore hostname          (default: localhost)
#   --store-port PORT    Viscostore port              (default: 8180)
#
# ViscoLink database options:
#   --db-host HOST       PostgreSQL hostname          (default: localhost)
#   --db-port PORT       PostgreSQL port              (default: 5432)
#   --db-name NAME       Database name                (default: viscolink)
#   --db-user USER       Database user                (default: visco)
#   --db-pass PASS       Database password            (default: visco)
#   --docker SERVICE     Run psql via docker exec instead of a local client

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

STORE_HOST="localhost"
STORE_PORT=8180
DB_HOST="localhost"
DB_PORT=5432
DB_NAME="viscolink"
DB_USER="visco"
DB_PASS="visco"
DOCKER_SERVICE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --store-host) STORE_HOST="$2"; shift 2 ;;
        --store-port) STORE_PORT="$2"; shift 2 ;;
        --db-host)    DB_HOST="$2";    shift 2 ;;
        --db-port)    DB_PORT="$2";    shift 2 ;;
        --db-name)    DB_NAME="$2";    shift 2 ;;
        --db-user)    DB_USER="$2";    shift 2 ;;
        --db-pass)    DB_PASS="$2";    shift 2 ;;
        --docker)     DOCKER_SERVICE="$2"; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

LOINC_ARGS=(-h "$DB_HOST" -p "$DB_PORT" -d "$DB_NAME" -U "$DB_USER" -W "$DB_PASS")
[[ -n "$DOCKER_SERVICE" ]] && LOINC_ARGS+=(--docker "$DOCKER_SERVICE")

FHIR_ARGS=(-h "$STORE_HOST" -p "$STORE_PORT")

echo "=== Step 1/2: Loading LOINC mapping (${DB_NAME}@${DB_HOST}:${DB_PORT}) ==="
"$SCRIPT_DIR/load-loinc-mapping.sh" "${LOINC_ARGS[@]}"

echo ""
echo "=== Step 2/2: Loading FHIR fixtures (http://${STORE_HOST}:${STORE_PORT}/viscostore/fhir) ==="
"$SCRIPT_DIR/load-fhir-fixtures.sh" "${FHIR_ARGS[@]}"

echo ""
echo "Demo environment ready."
