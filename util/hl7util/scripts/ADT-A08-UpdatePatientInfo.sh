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
PID|1||PAT-001^^^HospitalA^MR~123456782^^^2.16.840.1.113883.2.4.6.3^NNLD||Smith^John^A||19800315|M|||456 Oak Ave^^Chicago^IL^60601^USA||+31612345678^CP^CP~j.smith@example.nl^NET^Internet\r\
NK1|1|Smith^Jane^A|SPO^Spouse^HL70063|123 Main St^^Springfield^IL^62701^USA|(555)987-6543^PRN^PH|(555)987-6543^WPN^PH|EC^Emergency Contact^HL70131\r\
PV1|1|I|CARDIO^101^A^HospitalA||||DR001^Johnson^Emily^^^Dr.^MD||||||||||DR001^Johnson^Emily^^^Dr.^MD|OP|V-2026-001\r\
PV2\r\
OBX|1|NM|29463-7^^LN||75|kg|||||F\r\
AL1|1|DA|NKA^No Known Allergies^HL70132"
