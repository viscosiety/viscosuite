#!/usr/bin/env bash
# HTTP-OMS-O05-Invalid.sh — Send an intentionally invalid HL7v2 message over HTTP
# Message is an OMS^O05 (General Order Message) which is not supported by the
# hl7v2-to-fhir configuration. Expects an AE (Application Error) NACK response.
#
# Usage:
#   ./HTTP-OMS-O05-Invalid.sh [-h host] [-p port] [--no-validate]
#
# Defaults: host=localhost, port=80

source "$(dirname "$0")/_common_http.sh" "$@"

# Each segment is on a single line, separated by \r (HL7v2 segment terminator).
# The original message had line-wrapped segments (word wrap artefact); joined here.
send "MSH|^~\&|Epic||Inventory_System||20050401101352|5471|OMS^O05|233|D|2.6\r\
PID|1||5513^^^EPIC^EPIC~T924^^^TEST^CR||DHILLON^HARMEET^SINGH^^MR.^||19751115|M|DHILON^HARMEET^SINGH^^^||||(608)271-9000|||||6001~7199~900000053~153028|23492-3035\r\
ORC|NW|6417^EPC|||||||20050305|351^SIMPSON^SALLY^^^^\r\
RQD|1|41|45452^SECOND ONE|5500^^SID|2|Box|10000^MAIN||10000^MAIN|20050305\r\
ZQD|SN1234|LN5678|20211221||||1||||||1|0PR1|2|||LAP APPENDECTOMIE|20210202141500"
