#!/usr/bin/env bash
# load-loinc-mapping.sh — Load loinc-mapping.csv into the viscolink PostgreSQL table.
#
# Creates the loinc_mapping table if it does not exist, then replaces its
# contents from the CSV.  Safe to re-run; existing rows are fully replaced.
#
# Usage:
#   ./load-loinc-mapping.sh [-h host] [-p port] [-d database] [-U user] [-W password]
#
# Defaults match the local docker-compose.yml settings.
#
# Note: for production use, consider promoting the CREATE TABLE statement to a
# Liquibase changeset in viscolink so the schema is managed at startup.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CSV="${SCRIPT_DIR}/../../viscorunner/demo-configurations/loinc-mapping-api/loinc-mapping.csv"

DB_HOST="localhost"
DB_PORT=5432
DB_NAME="viscolink"
DB_USER="visco"
DB_PASS="visco"
# When psql is not installed locally, set this to the postgres container name
# (e.g. "postgres") and the script will run psql via docker exec.
DOCKER_SERVICE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--host)     DB_HOST="$2";      shift 2 ;;
        -p|--port)     DB_PORT="$2";      shift 2 ;;
        -d|--database) DB_NAME="$2";      shift 2 ;;
        -U|--user)     DB_USER="$2";      shift 2 ;;
        -W|--password) DB_PASS="$2";      shift 2 ;;
        --docker)      DOCKER_SERVICE="$2"; shift 2 ;;
        *) echo "Unknown option: $1  (supported: -h, -p, -d, -U, -W, --docker <container>)" >&2; exit 2 ;;
    esac
done

if [[ ! -f "$CSV" ]]; then
    echo "CSV not found: ${CSV}" >&2
    exit 1
fi

# Build psql command — either direct or via docker exec
if [[ -n "$DOCKER_SERVICE" ]]; then
    PSQL=(docker exec -i "$DOCKER_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1)
elif command -v psql &>/dev/null; then
    export PGPASSWORD="$DB_PASS"
    PSQL=(psql -h "$DB_HOST" -p "$DB_PORT" -d "$DB_NAME" -U "$DB_USER" -v ON_ERROR_STOP=1)
else
    echo "psql not found. Either install postgresql-client or use --docker <service> to run via Docker." >&2
    echo "  Example: ./load-loinc-mapping.sh --docker postgres  (use the container name)" >&2
    exit 1
fi

# ── Create table ────────────────────────────────────────────────────────────
"${PSQL[@]}" <<'SQL'
CREATE TABLE IF NOT EXISTS loinc_mapping (
    text        TEXT NOT NULL,
    specimen    TEXT NOT NULL DEFAULT '',
    code        TEXT NOT NULL,
    display     TEXT NOT NULL,
    PRIMARY KEY (text, specimen)
);
SQL

# ── Strip comment and header lines ──────────────────────────────────────────
# Filter once into a variable; avoids temp-file path visibility issues when
# psql runs inside Docker (where the host /tmp is not accessible).
FILTERED=$(grep -v '^[[:space:]]*#' "$CSV" \
    | grep -v '^text;' \
    | grep -v '^[[:space:]]*$')

# ── Truncate and reload ──────────────────────────────────────────────────────
"${PSQL[@]}" -c "TRUNCATE loinc_mapping;"
printf '%s\n' "$FILTERED" \
    | "${PSQL[@]}" -c "\copy loinc_mapping (text, specimen, code, display) FROM STDIN DELIMITER ';'"

count=$("${PSQL[@]}" -t -c "SELECT COUNT(*) FROM loinc_mapping;" | tr -d ' \n')
echo "Loaded ${count} rows into loinc_mapping (${DB_NAME}@${DB_HOST}:${DB_PORT})"
