#!/usr/bin/env bash
# ADT-A11-CancelAdmit.sh — Cancel Admit / Visit Notification
# Sends an ADT^A11 message: the admission of John Smith (PAT-001, V-2026-001)
# is retracted — the encounter is set to cancelled.
#
# Depends on a prior ADT^A01 (ADT-A01-AdmitPatient.sh) to have created the encounter.
#
# Usage:
#   ./ADT-A11-CancelAdmit.sh [-h host] [-p port]
#
# Defaults: host=localhost, port=2575

source "$(dirname "$0")/_common.sh" "$@"

# MSG.3 = ADT_A09 (A11 shares the ADT_A09 structure in HL7 2.5)
send "MSH|^~\&|SendingApp|SendingFac|viscolink|viscosiety|${TS}||ADT^A11^ADT_A09|${MSG_ID}|P|2.5\r\
EVN|A11|${TS}\r\
PID|1||PAT-001^^^HospitalA^MR~123456782^^^2.16.840.1.113883.2.4.6.3^NNLD||de Vries^Jan^H||19800315|M||||+31612345678^CP^CP~j.devries@example.nl^NET^Internet\r\
PV1|1|I|CARDIO^101^A^HospitalA||||DR001^de Boer^Erik^^^Dr.^MD||||||||||DR001^de Boer^Erik^^^Dr.^MD|OP|V-2026-001"
