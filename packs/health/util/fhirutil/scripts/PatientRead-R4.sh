#!/usr/bin/env bash
# PatientRead-R4.sh — GET a FHIR R4 Patient by ID from the fhir-to-fhir facade.
#
# Usage:
#   ./PatientRead-R4.sh [--id patient-id] [-h host] [-p port]
#
# Defaults: host=localhost, port=8180, id=PAT-R4-001

source "$(dirname "$0")/_common.sh" "$@"

get_patient "r4" "fhir-to-fhir" "${PATIENT_ID:-PAT-R4-001}"
