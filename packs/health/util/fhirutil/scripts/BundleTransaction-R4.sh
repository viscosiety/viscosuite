#!/usr/bin/env bash
# BundleTransaction-R4.sh — POST a FHIR R4 transaction Bundle to the fhir-to-fhir facade.
#
# Usage:
#   ./BundleTransaction-R4.sh [-h host] [-p port]
#
# Defaults: host=localhost, port=8180

source "$(dirname "$0")/_common.sh" "$@"

post_bundle "r4" "fhir-to-fhir" '<?xml version="1.0" encoding="UTF-8"?>
<Bundle xmlns="http://hl7.org/fhir">
  <id value="bundle-tx-r4-demo"/>
  <type value="transaction"/>
  <timestamp value="'"${TS_FHIR}"'"/>
  <entry>
    <fullUrl value="urn:uuid:pat-r4-demo"/>
    <resource>
      <Patient>
        <identifier>
          <system value="http://example.org/mrn"/>
          <value value="PAT-R4-001"/>
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
