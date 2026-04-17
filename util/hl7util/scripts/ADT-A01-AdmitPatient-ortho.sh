#!/usr/bin/env bash
# ADT-A01-AdmitPatient-ortho.sh — Admit / Visit Notification from the ORTHO department.
# This message is intentionally rejected by the HL7v2-over-MLLP adapter,
# which only accepts admissions from CARDIO.
#
# Usage:
#   ./ADT-A01-AdmitPatient-ortho.sh [-h host] [-p port]
#
# Defaults: host=localhost, port=2575

source "$(dirname "$0")/_common.sh" "$@"

send "MSH|^~\&|SendingApp|SendingFac|viscolink|viscosiety|${TS}||ADT^A01^ADT_A01|${MSG_ID}|P|2.5\r\
EVN|A01|${TS}\r\
PID|1||PAT-002^^^HospitalA^MR~111222333^^^2.16.840.1.113883.2.4.6.3^NNLD||Jones^Sarah^B||19750822|F|||456 Elm St^^Springfield^IL^62702^USA||+31687654321^CP^CP~s.jones@example.nl^NET^Internet|||||ACC-2026-002\r\
NK1|1|Jones^Michael^B|SPO^Spouse^HL70063|456 Elm St^^Springfield^IL^62702^USA|(555)876-5432^PRN^PH|(555)876-5432^WPN^PH|EC^Emergency Contact^HL70131\r\
PV1|1|I|ORTHO^201^B^HospitalA||||DR003^Peters^Anna^^^Dr.^MD|DR004^Brown^Kevin^^^Dr.^MD||E|||||||DR003^Peters^Anna^^^Dr.^MD|OP|V-2026-002|||||||||||||||||||||||||${TS}\r\
PV2\r\
OBX|1|NM|29463-7^^LN||68|kg|||||F\r\
AL1|1|DA|NKA^No Known Allergies^HL70132"
