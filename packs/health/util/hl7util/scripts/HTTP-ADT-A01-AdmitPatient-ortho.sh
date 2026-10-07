#!/usr/bin/env bash
# HTTP-ADT-A01-AdmitPatient-ortho.sh — Admit / Visit Notification from the ORTHO department.
# This message is intentionally rejected by the HL7v2-over-HTTP adapter,
# which only accepts admissions from CARDIO. Expects an AR NACK in the response.
#
# Usage:
#   ./HTTP-ADT-A01-AdmitPatient-ortho.sh [-h host] [-p port] [--no-validate]
#
# Defaults: host=localhost, port=80

source "$(dirname "$0")/_common_http.sh" "$@"

send "MSH|^~\&|SendingApp|SendingFac|viscolink|viscosiety|${TS}||ADT^A01^ADT_A01|${MSG_ID}|P|2.5\r\
EVN|A01|${TS}\r\
PID|1||PAT-002^^^HospitalA^MR~111222333^^^2.16.840.1.113883.2.4.6.3^NNLD||Bakker^Sara^A||19750822|F|||Prinsengracht 456^^Amsterdam^NH^1016GZ^NLD||+31687654321^CP^CP~s.bakker@example.nl^NET^Internet|||||ACC-2026-002\r\
NK1|1|Bakker^Pieter^A|SPO^Spouse^HL70063|Prinsengracht 456^^Amsterdam^NH^1016GZ^NLD|+31201234568^PRN^PH|+31201234568^WPN^PH|EC^Emergency Contact^HL70131\r\
PV1|1|I|ORTHO^201^B^HospitalA||||DR003^van den Berg^Anna^^^Dr.^MD|DR004^Visser^Kevin^^^Dr.^MD||E|||||||DR003^van den Berg^Anna^^^Dr.^MD|OP|V-2026-003|||||||||||||||||||||||||${TS}\r\
PV2\r\
OBX|1|NM|29463-7^^LN||68|kg|||||F\r\
AL1|1|DA|NKA^No Known Allergies^HL70132"
