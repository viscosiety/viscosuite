#!/usr/bin/env bash
# ADT-A01-AdmitPatient.sh — Admit / Visit Notification
# Sends an ADT^A01 message to the viscolink MLLP listener.
#
# Usage:
#   ./ADT-A01-AdmitPatient.sh [-h host] [-p port]
#
# Defaults: host=localhost, port=2575

source "$(dirname "$0")/_common.sh" "$@"

send "MSH|^~\&|SendingApp|SendingFac|viscolink|viscosiety|${TS}||ADT^A01^ADT_A01|${MSG_ID}|P|2.5\r\
EVN|A01|${TS}\r\
PID|1||PAT-001^^^HospitalA^MR~123456782^^^2.16.840.1.113883.2.4.6.3^NNLD||Smith^John^A||19800315|M|||123 Main St^^Springfield^IL^62701^USA||+31612345678^CP^CP~j.smith@example.nl^NET^Internet|||||ACC-2026-001\r\
NK1|1|Smith^Jane^A|SPO^Spouse^HL70063|123 Main St^^Springfield^IL^62701^USA|(555)987-6543^PRN^PH|(555)987-6543^WPN^PH|EC^Emergency Contact^HL70131\r\
PV1|1|I|CARDIO^101^A^HospitalA||||DR001^Johnson^Emily^^^Dr.^MD|DR002^Williams^Robert^^^Dr.^MD||E|||||||DR001^Johnson^Emily^^^Dr.^MD|OP|V-2026-001|||||||||||||||||||||||||${TS}\r\
PV2\r\
OBX|1|NM|29463-7^^LN||75|kg|||||F\r\
AL1|1|DA|NKA^No Known Allergies^HL70132"
