#!/usr/bin/env bash
# HTTP-SIU-S13-RescheduleAppointment.sh — Appointment Reschedule over HTTP
# Moves APT-2026-001 from 10 Jun 09:00 to 12 Jun 14:00.
# Run after HTTP-SIU-S12-BookAppointment.sh. Expects an AA ACK.
#
# Usage:
#   ./HTTP-SIU-S13-RescheduleAppointment.sh [-h host] [-p port] [--no-validate]
#
# Defaults: host=localhost, port=80

source "$(dirname "$0")/_common_http.sh" "$@"

send "MSH|^~\&|SchedSystem|HospitalA|viscolink|viscosiety|${TS}||SIU^S13^SIU_S12|${MSG_ID}|P|2.5\r\
SCH|APT-2026-001^HospitalA|APT-2026-001^HospitalA||||CARDIO-CONS^Cardiology Consultation^L|WALKIN^Walk-in^HL70276||30\r\
PID|1||PAT-9901^^^HospitalA^MR||van den Berg^Maria^A||19750422|F\r\
PV1|1|O\r\
RGS|1|A\r\
AIS|1||CARDIO-CONS^Cardiology Consultation^L|20260612140000||||30\r\
AIP|1||DR001^de Boer^Erik^^^Dr.^MD|MD\r\
AIL|1||CARDIO^CARDIO-OPD-1^A^HospitalA"
