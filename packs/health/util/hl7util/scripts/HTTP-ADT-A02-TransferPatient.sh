#!/usr/bin/env bash
# HTTP-ADT-A02-TransferPatient.sh — Transfer a Patient over HTTP
# Sends an ADT^A02 message: Jan de Vries (PAT-001) is transferred from
# CARDIO ward 101 bed A to CARDIO ward 102 bed B.
#
# Depends on a prior ADT^A01 (HTTP-ADT-A01-AdmitPatient.sh) to have created the encounter.
#
# Usage:
#   ./HTTP-ADT-A02-TransferPatient.sh [-h host] [-p port] [--no-validate]
#
# Defaults: host=localhost, port=80

source "$(dirname "$0")/_common_http.sh" "$@"

send "MSH|^~\&|SendingApp|SendingFac|viscolink|viscosiety|${TS}||ADT^A02^ADT_A02|${MSG_ID}|P|2.5\r\
EVN|A02|${TS}\r\
PID|1||PAT-001^^^HospitalA^MR~123456782^^^2.16.840.1.113883.2.4.6.3^NNLD||de Vries^Jan^H||19800315|M|||Keizersgracht 123^^Amsterdam^NH^1015CJ^NLD||+31612345678^CP^CP~j.devries@example.nl^NET^Internet\r\
PV1|1|I|CARDIO^102^B^HospitalA|||CARDIO^101^A^HospitalA|DR001^de Boer^Erik^^^Dr.^MD||||||||||DR001^de Boer^Erik^^^Dr.^MD|OP|V-2026-001|||||||||||||||||||||||||20260301080000"
