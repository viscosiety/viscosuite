#!/usr/bin/env bash
# ADT-A01.sh — Admit/Visit Notification
# Sends an ADT^A01 message to the viscolink MLLP listener.
#
# Usage:
#   ./ADT-A01.sh [-h host] [-p port]
#
# Defaults: host=localhost, port=2575

source "$(dirname "$0")/_common.sh" "$@"

send "MSH|^~\&|SendingApp|SendingFac|viscolink|viscosiety|${TS}||ADT^A01^ADT_A01|${MSG_ID}|P|2.5\r\
PID|1||PAT-001^^^HospitalA^MR||Smith^John^A||19800315|M|||123 Main St^^Springfield^IL^62701^USA|||||||ACC-2026-001\r\
PV1|1|I|CARDIO^101^A^HospitalA||||DR001^Johnson^Emily^^^Dr.^MD|DR002^Williams^Robert^^^Dr.^MD||E|||||||DR001^Johnson^Emily^^^Dr.^MD|INP|V-2026-001|||||||||||||||||||HospitalA|||||${TS}"
