#!/usr/bin/env bash
# ADT-A13-CancelDischarge.sh — Cancel Discharge / End Visit
# Sends an ADT^A13 message: the discharge of John Smith (PAT-001, V-2026-001)
# is retracted — the encounter reverts to in-progress with no period.end.
#
# Depends on a prior ADT^A03 (ADT-A03-DischargePatient.sh) to have closed the encounter.
#
# Usage:
#   ./ADT-A13-CancelDischarge.sh [-h host] [-p port]
#
# Defaults: host=localhost, port=2575

source "$(dirname "$0")/_common.sh" "$@"

# MSG.3 = ADT_A01 (A13 shares the ADT_A01 structure in HL7 2.5)
# PV1.44 = original admit date; PV1.45 absent so period.end is cleared by the PUT
send "MSH|^~\&|SendingApp|SendingFac|viscolink|viscosiety|${TS}||ADT^A13^ADT_A01|${MSG_ID}|P|2.5\r\
EVN|A13|${TS}\r\
PID|1||PAT-001^^^HospitalA^MR||Smith^John^A||19800315|M|||123 Main St^^Springfield^IL^62701^USA\r\
NK1|1|Smith^Jane^A|SPO^Spouse^HL70063|123 Main St^^Springfield^IL^62701^USA|(555)987-6543^PRN^PH|(555)987-6543^WPN^PH|EC^Emergency Contact^HL70131\r\
PV1|1|I|CARDIO^102^B^HospitalA||||DR001^Johnson^Emily^^^Dr.^MD||||||||||DR001^Johnson^Emily^^^Dr.^MD|OP|V-2026-001|||||||||||||||||||||||||20260301080000\r\
PV2\r\
OBX|1|NM|29463-7^^LN||75|kg|||||F\r\
AL1|1|DA|NKA^No Known Allergies^HL70132"
