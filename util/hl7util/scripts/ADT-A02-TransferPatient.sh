#!/usr/bin/env bash
# ADT-A02-TransferPatient.sh — Transfer a Patient
# Sends an ADT^A02 message: John Smith (PAT-001) is transferred from
# CARDIO ward 101 bed A to CARDIO ward 102 bed B.
#
# Depends on a prior ADT^A01 (ADT-A01-AdmitPatient.sh) to have created the encounter.
#
# Usage:
#   ./ADT-A02-TransferPatient.sh [-h host] [-p port]
#
# Defaults: host=localhost, port=2575

source "$(dirname "$0")/_common.sh" "$@"

# PV1.3  = new (assigned) location: CARDIO ward 102 bed B
# PV1.6  = prior patient location:  CARDIO ward 101 bed A
# PV1.44 = admit date/time (echoed from A01)
send "MSH|^~\&|SendingApp|SendingFac|viscolink|viscosiety|${TS}||ADT^A02^ADT_A02|${MSG_ID}|P|2.5\r\
EVN|A02|${TS}\r\
PID|1||PAT-001^^^HospitalA^MR~123456782^^^2.16.840.1.113883.2.4.6.3^NNLD||Smith^John^A||19800315|M|||123 Main St^^Springfield^IL^62701^USA||+31612345678^CP^CP~j.smith@example.nl^NET^Internet\r\
PV1|1|I|CARDIO^102^B^HospitalA|||CARDIO^101^A^HospitalA|DR001^Johnson^Emily^^^Dr.^MD||||||||||DR001^Johnson^Emily^^^Dr.^MD|OP|V-2026-001|||||||||||||||||||||||||20260301080000"
