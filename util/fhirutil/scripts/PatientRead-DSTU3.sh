#!/usr/bin/env bash
# PatientRead-DSTU3.sh — GET a FHIR DSTU3 (STU3) Patient by ID from the fhir-to-fhir facade.
#
# Usage:
#   ./PatientRead-DSTU3.sh [--id patient-id] [-h host] [-p port]
#
# Defaults: host=localhost, port=8180, id=PAT-DSTU3-001

source "$(dirname "$0")/_common.sh" "$@"

get_patient "dstu3" "fhir-to-fhir" "${PATIENT_ID:-PAT-DSTU3-001}"
