#!/usr/bin/env bash
# update-fhir-xsd.sh
#
# Downloads FHIR XSD schemas from HAPI FHIR validation-resources JARs whose version is
# declared in viscolink/pom.xml and writes the three schema files for each supported FHIR
# version (DSTU3, R4) to:
#   viscorunner/resources/fhir-xsd/{dstu3,r4}/fhir-single.xsd
#   viscorunner/resources/fhir-xsd/{dstu3,r4}/fhir-xhtml.xsd
#   viscorunner/resources/fhir-xsd/{dstu3,r4}/xml.xsd
#
# Note: R5 is intentionally excluded — FHIR R5 dropped XML Schema support; the
# hapi-fhir-validation-resources-r5 JAR ships only .tgz package bundles, no XSD files.
#
# The target directory is on Tomcat's shared.loader classpath (/opt/frank/resources/), making
# the schemas accessible to all F!F configurations as fhir-xsd/{version}/fhir-single.xsd.
#
# Run from any directory inside the repository:
#   ./viscorunner/scripts/update-fhir-xsd.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VISCORUNNER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$VISCORUNNER_DIR/.." && pwd)"
VISCOLINK_POM="$REPO_ROOT/viscolink/pom.xml"
TARGET_DIR="$VISCORUNNER_DIR/resources/fhir-xsd"

# ── 1. Read hapi.version from viscolink/pom.xml ───────────────────────────────

HAPI_VERSION=$(grep -m1 '<hapi\.version>' "$VISCOLINK_POM" \
               | sed 's/.*<hapi\.version>\(.*\)<\/hapi\.version>.*/\1/' \
               | tr -d '[:space:]')

if [[ -z "$HAPI_VERSION" ]]; then
    echo "ERROR: could not extract hapi.version from $VISCOLINK_POM" >&2
    exit 1
fi

echo "HAPI FHIR version : $HAPI_VERSION"

# ── 2. Process each FHIR version ─────────────────────────────────────────────

XSD_FILES=("fhir-single.xsd" "fhir-xhtml.xsd" "xml.xsd")

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

# R5 dropped XML Schema support — validation-resources-r5 contains only .tgz packages.
for FHIR_VER in dstu3 r4; do
    ARTIFACT="ca.uhn.hapi.fhir:hapi-fhir-validation-resources-${FHIR_VER}:${HAPI_VERSION}"
    echo ""
    echo "Downloading $ARTIFACT ..."

    JAR_DIR="$TMPDIR/$FHIR_VER"
    mkdir -p "$JAR_DIR"

    mvn --file "$VISCOLINK_POM" \
        dependency:copy \
        -Dartifact="$ARTIFACT" \
        -DoutputDirectory="$JAR_DIR" \
        --quiet

    JAR=$(ls "$JAR_DIR"/hapi-fhir-validation-resources-*.jar 2>/dev/null | head -1)
    if [[ -z "$JAR" ]]; then
        echo "ERROR: JAR not found in $JAR_DIR after download" >&2
        exit 1
    fi

    OUT_DIR="$TARGET_DIR/$FHIR_VER"
    mkdir -p "$OUT_DIR"

    INNER_PATH="org/hl7/fhir/${FHIR_VER}/model/schema"

    for XSD in "${XSD_FILES[@]}"; do
        ENTRY="$INNER_PATH/$XSD"
        if ! unzip -p "$JAR" "$ENTRY" > "$OUT_DIR/$XSD"; then
            echo "ERROR: $ENTRY not found in $JAR" >&2
            echo "Available XSD files in JAR:" >&2
            unzip -l "$JAR" | grep '\.xsd$' >&2
            exit 1
        fi
        echo "  Updated : $OUT_DIR/$XSD"
    done
done

echo ""
echo "Done."
