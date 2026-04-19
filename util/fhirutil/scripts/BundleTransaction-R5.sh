#!/usr/bin/env bash
# BundleTransaction-R5.sh — POST a FHIR R5 transaction Bundle to the fhir-to-fhir facade.
#
# Usage:
#   ./BundleTransaction-R5.sh [-h host] [-p port]
#
# Defaults: host=localhost, port=8180

source "$(dirname "$0")/_common.sh" "$@"

post_bundle "r5" "fhir-to-fhir" '<?xml version="1.0" encoding="UTF-8"?>
<Bundle xmlns="http://hl7.org/fhir">
  <id value="bundle-tx-r5-demo"/>
  <type value="transaction"/>
  <timestamp value="'"${TS_FHIR}"'"/>
  <entry>
    <fullUrl value="urn:uuid:pat-r5-demo"/>
    <resource>
      <Patient>
        <identifier>
          <system value="http://example.org/mrn"/>
          <value value="PAT-R5-001"/>
        </identifier>
        <name>
          <family value="de Vries"/>
          <given value="Jan"/>
        </name>
        <gender value="male"/>
        <birthDate value="1980-03-15"/>
      </Patient>
    </resource>
    <request>
      <method value="POST"/>
      <url value="Patient"/>
    </request>
  </entry>
</Bundle>'
