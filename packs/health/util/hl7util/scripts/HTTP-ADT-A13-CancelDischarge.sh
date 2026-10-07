#!/usr/bin/env bash
# HTTP-ADT-A13-CancelDischarge.sh — Cancel Discharge / End Visit over HTTP
# Sends an ADT^A13 message: the discharge of Jan de Vries (PAT-001, V-2026-001)
# is retracted — the encounter reverts to in-progress with no period.end.
#
# Depends on a prior ADT^A03 (HTTP-ADT-A03-DischargePatient.sh) to have closed the encounter.
#
# Usage:
#   ./HTTP-ADT-A13-CancelDischarge.sh [-h host] [-p port] [--no-validate]
#
# Defaults: host=localhost, port=80

source "$(dirname "$0")/_common_http.sh" "$@"

send "MSH|^~\&|SendingApp|SendingFac|viscolink|viscosiety|${TS}||ADT^A13^ADT_A01|${MSG_ID}|P|2.5\r\
EVN|A13|${TS}\r\
PID|1||PAT-001^^^HospitalA^MR~123456782^^^2.16.840.1.113883.2.4.6.3^NNLD||de Vries^Jan^H||19800315|M|||Keizersgracht 123^^Amsterdam^NH^1015CJ^NLD||+31612345678^CP^CP~j.devries@example.nl^NET^Internet\r\
NK1|1|de Vries^Maria^H|SPO^Spouse^HL70063|Keizersgracht 123^^Amsterdam^NH^1015CJ^NLD|+31201234567^PRN^PH|+31201234567^WPN^PH|EC^Emergency Contact^HL70131\r\
PV1|1|I|CARDIO^102^B^HospitalA||||DR001^de Boer^Erik^^^Dr.^MD||||||||||DR001^de Boer^Erik^^^Dr.^MD|OP|V-2026-001|||||||||||||||||||||||||20260301080000\r\
PV2\r\
OBX|1|NM|29463-7^^LN||82|kg|||||F\r\
AL1|1|DA|NKA^No Known Allergies^HL70132"
