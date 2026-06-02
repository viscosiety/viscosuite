#!/usr/bin/env bash
# HTTP-SIU-S12-BookAppointment.sh — New Appointment Booking over HTTP
# Books Maria van den Berg (PAT-9901) for a CARDIO consultation (APT-2026-001)
# on 10 Jun 2026 09:00, 30 min. Expects an AA ACK in the response.
#
# Usage:
#   ./HTTP-SIU-S12-BookAppointment.sh [-h host] [-p port] [--no-validate]
#
# Defaults: host=localhost, port=80

source "$(dirname "$0")/_common_http.sh" "$@"

send "MSH|^~\&|SchedSystem|HospitalA|viscolink|viscosiety|${TS}||SIU^S12^SIU_S12|${MSG_ID}|P|2.5\r\
SCH|APT-2026-001^HospitalA|APT-2026-001^HospitalA||||CARDIO-CONS^Cardiology Consultation^L|WALKIN^Walk-in^HL70276||30\r\
PID|1||PAT-9901^^^HospitalA^MR||van den Berg^Maria^A||19750422|F|||Herengracht 789^^Amsterdam^NH^1017CB^NLD||+31687654321^CP^CP\r\
NK1|1|van den Berg^Peter^A|SPO^Spouse^HL70063\r\
PV1|1|O\r\
RGS|1|A\r\
AIS|1||CARDIO-CONS^Cardiology Consultation^L|20260610090000||||30\r\
AIP|1||DR001^de Boer^Erik^^^Dr.^MD|MD\r\
AIL|1||CARDIO^CARDIO-OPD-1^A^HospitalA"
