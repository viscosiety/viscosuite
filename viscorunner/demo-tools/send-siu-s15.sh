#!/usr/bin/env bash
# send-siu-s15.sh
# SIU^S15 Appointment Cancellation — cancels APT-2026-001.
# Run after send-siu-s12.sh (or any S12/S13/S14 that created the appointment).
#
# Env vars:
#   VISCOLINK_BASE_URL  default: http://localhost:8180
#   VISCOLINK_USER      default: ADMIN
#   VISCOLINK_PASS      default: PASSWORD1234
#   VALIDATE            default: true

set -euo pipefail

BASE="${VISCOLINK_BASE_URL:-http://localhost:8180}"
USER="${VISCOLINK_USER:-ADMIN}"
PASS="${VISCOLINK_PASS:-PASSWORD1234}"
VALIDATE="${VALIDATE:-true}"

TS=$(date +%Y%m%d%H%M%S)
MSG_ID="${TS}$(date +%3N)"

MSG=$(printf '%s\r' \
  "MSH|^~\\&|SchedSystem|HospitalA|viscolink|viscosiety|${TS}||SIU^S15^SIU_S12|${MSG_ID}|P|2.5" \
  "SCH|APT-2026-001^HospitalA|APT-2026-001^HospitalA||||CARDIO-CONS^Cardiology Consultation^L|WALKIN^Walk-in^HL70276||30" \
  "PID|1||PAT-9901^^^HospitalA^MR||van den Berg^Maria^A||19750422|F" \
  "PV1|1|O" \
  "RGS|1|A" \
  "AIS|1||CARDIO-CONS^Cardiology Consultation^L|20260612140000||||30" \
  "AIP|1||DR001^de Boer^Erik^^^Dr.^MD|MD" \
  "AIL|1||CARDIO^CARDIO-OPD-1^A^HospitalA" \
)

curl -s \
  -u "${USER}:${PASS}" \
  -H 'Content-Type: x-application/hl7-v2+er7' \
  --data-binary "${MSG}" \
  "${BASE}/viscolink/api/hl7v2?validateMessage=${VALIDATE}"
echo
