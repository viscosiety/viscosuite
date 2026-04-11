#!/usr/bin/env bash
# ADT-A01-ortho.sh — Admit/Visit Notification from the ORTHO department.
# This message is intentionally rejected by the HL7v2-over-MLLP adapter,
# which only accepts admissions from CARDIO.
#
# Usage:
#   ./ADT-A01-ortho.sh [-h host] [-p port]
#
# Defaults: host=localhost, port=2575

source "$(dirname "$0")/_common.sh" "$@"

send "MSH|^~\&|SendingApp|SendingFac|viscolink|viscosiety|${TS}||ADT^A01^ADT_A01|${MSG_ID}|P|2.5\r\
EVN|A01|${TS}\r\
PID|1||PAT-002^^^HospitalA^MR||Jones^Sarah^B||19750822|F|||456 Elm St^^Springfield^IL^62702^USA|||||||ACC-2026-002\r\
PV1|1|I|ORTHO^201^B^HospitalA||||DR003^Peters^Anna^^^Dr.^MD|DR004^Brown^Kevin^^^Dr.^MD||E|||||||DR003^Peters^Anna^^^Dr.^MD|OP|V-2026-002|||||||||||||||||||HospitalA|||||${TS}"
