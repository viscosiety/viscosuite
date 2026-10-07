#!/usr/bin/env bash
# PatientRead-R5.sh — GET a FHIR R5 Patient by ID from the fhir-to-fhir facade.
#
# Usage:
#   ./PatientRead-R5.sh [--id patient-id] [-h host] [-p port]
#
# Defaults: host=localhost, port=8180, id=PAT-R5-001

source "$(dirname "$0")/_common.sh" "$@"

get_patient "r5" "fhir-to-fhir" "${PATIENT_ID:-PAT-R5-001}"
