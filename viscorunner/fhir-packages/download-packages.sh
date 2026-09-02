#!/bin/sh
# Downloads the FHIR NPM packages the nl-core intake validates against.
# Versions are pinned; both packages are published by Nictiz under CC0-1.0.
# The .tgz files are gitignored — run this script after cloning (CI and the
# runner image build run it too).
set -eu
cd "$(dirname "$0")"

REGISTRY="https://packages.simplifier.net"
NL_CORE_VERSION="0.12.1-beta.1"
ZIB2020_VERSION="0.12.1-beta.1"

fetch() {
    name="$1"; version="$2"
    file="${name}-${version}.tgz"
    if [ -s "$file" ]; then
        echo "already present: $file"
    else
        echo "downloading $file"
        curl -fsSL -o "$file" "${REGISTRY}/${name}/${version}"
    fi
}

fetch nictiz.fhir.nl.r4.nl-core  "$NL_CORE_VERSION"
fetch nictiz.fhir.nl.r4.zib2020  "$ZIB2020_VERSION"
echo "done — $(ls -1 ./*.tgz | wc -l) package(s) in $(pwd)"
