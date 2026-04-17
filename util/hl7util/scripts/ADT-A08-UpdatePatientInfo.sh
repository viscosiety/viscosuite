#!/usr/bin/env bash
# ADT-A08-UpdatePatientInfo.sh — Update Patient Information
# Sends an ADT^A08 message: John Smith (PAT-001) has updated his address
# and home phone number. No encounter change.
#
# Usage:
#   ./ADT-A08-UpdatePatientInfo.sh [-h host] [-p port]
#
# Defaults: host=localhost, port=2575

source "$(dirname "$0")/_common.sh" "$@"

# Updated address: moved to 456 Oak Ave, Chicago IL 60601
# Updated home phone: PID.13
send "MSH|^~\&|SendingApp|SendingFac|viscolink|viscosiety|${TS}||ADT^A08^ADT_A01|${MSG_ID}|P|2.5\r\
EVN|A08|${TS}\r\
PID|1||PAT-001^^^HospitalA^MR~123456782^^^2.16.840.1.113883.2.4.6.3^NNLD||de Vries^Jan^H||19800315|M|||Herengracht 456^^Amsterdam^NH^1017CA^NLD||+31612345678^CP^CP~j.devries@example.nl^NET^Internet\r\
NK1|1|de Vries^Maria^H|SPO^Spouse^HL70063|Keizersgracht 123^^Amsterdam^NH^1015CJ^NLD|+31201234567^PRN^PH|+31201234567^WPN^PH|EC^Emergency Contact^HL70131\r\
PV1|1|I|CARDIO^101^A^HospitalA||||DR001^de Boer^Erik^^^Dr.^MD||||||||||DR001^de Boer^Erik^^^Dr.^MD|OP|V-2026-001\r\
PV2\r\
OBX|1|NM|29463-7^^LN||82|kg|||||F\r\
AL1|1|DA|NKA^No Known Allergies^HL70132"
