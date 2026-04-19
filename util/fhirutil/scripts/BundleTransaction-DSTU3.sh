#!/usr/bin/env bash
# BundleTransaction-DSTU3.sh — POST a FHIR DSTU3 (STU3) transaction Bundle to the fhir-to-fhir facade.
#
# Usage:
#   ./BundleTransaction-DSTU3.sh [-h host] [-p port]
#
# Defaults: host=localhost, port=8180

source "$(dirname "$0")/_common.sh" "$@"

post_bundle "dstu3" "fhir-to-fhir" '<?xml version="1.0" encoding="UTF-8"?>
<Bundle xmlns="http://hl7.org/fhir">
  <id value="bundle-tx-dstu3-demo"/>
  <type value="transaction"/>
  <entry>
    <fullUrl value="urn:uuid:pat-dstu3-demo"/>
    <resource>
      <Patient>
        <identifier>
          <system value="http://example.org/mrn"/>
          <value value="PAT-DSTU3-001"/>
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
