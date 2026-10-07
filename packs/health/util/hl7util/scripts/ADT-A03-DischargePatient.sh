#!/usr/bin/env bash
# ADT-A03-DischargePatient.sh — Discharge / End Visit
# Sends an ADT^A03 message: John Smith (PAT-001) is discharged home (01)
# from CARDIO ward 102 bed B.
#
# Depends on a prior ADT^A01 (ADT-A01-AdmitPatient.sh) to have created the encounter.
#
# Usage:
#   ./ADT-A03-DischargePatient.sh [-h host] [-p port]
#
# Defaults: host=localhost, port=2575

source "$(dirname "$0")/_common.sh" "$@"

# PV1.36 = 01 (discharged to home)      — 17 pipes after V-2026-001 (PV1.19)
# PV1.44 = admit date/time (from A01)   —  8 pipes after PV1.36
# PV1.45 = discharge date/time (now)    —  1 pipe  after PV1.44
send "MSH|^~\&|SendingApp|SendingFac|viscolink|viscosiety|${TS}||ADT^A03^ADT_A03|${MSG_ID}|P|2.5\r\
EVN|A03|${TS}\r\
PID|1||PAT-001^^^HospitalA^MR~123456782^^^2.16.840.1.113883.2.4.6.3^NNLD||de Vries^Jan^H||19800315|M|||Keizersgracht 123^^Amsterdam^NH^1015CJ^NLD||+31612345678^CP^CP~j.devries@example.nl^NET^Internet\r\
PV1|1|I|CARDIO^102^B^HospitalA||||DR001^de Boer^Erik^^^Dr.^MD||||||||||DR001^de Boer^Erik^^^Dr.^MD|OP|V-2026-001|||||||||||||||||01||||||||20260301080000|${TS}"
