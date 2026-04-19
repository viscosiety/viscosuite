#!/usr/bin/env bash
# update-frankconfig-xsd.sh
#
# Downloads FrankConfig.xsd from the frankframework-core JAR whose version is declared
# in viscolink/pom.xml and writes it to:
#   viscorunner/configurations/FrankConfig.xsd
#   viscorunner/demo-configurations/FrankConfig.xsd
#
# Run from any directory inside the repository:
#   ./viscorunner/scripts/update-frankconfig-xsd.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VISCORUNNER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$VISCORUNNER_DIR/.." && pwd)"
VISCOLINK_POM="$REPO_ROOT/viscolink/pom.xml"

# ── 1. Read frankframework.version from viscolink/pom.xml ──────────────────

FF_VERSION=$(grep -m1 '<frankframework.version>' "$VISCOLINK_POM" \
             | sed 's/.*<frankframework.version>\(.*\)<\/frankframework.version>.*/\1/' \
             | tr -d '[:space:]')

if [[ -z "$FF_VERSION" ]]; then
    echo "ERROR: could not extract frankframework.version from $VISCOLINK_POM" >&2
    exit 1
fi

echo "Frank!Framework version : $FF_VERSION"

# ── 2. Download frankframework-core JAR via Maven ─────────────────────────
# Run Maven from viscolink/ so it picks up the repositories configured in that pom.

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

echo "Downloading frankframework-core-${FF_VERSION}.jar ..."

mvn --file "$VISCOLINK_POM" \
    dependency:copy \
    -Dartifact="org.frankframework:frankframework-core:${FF_VERSION}" \
    -DoutputDirectory="$TMPDIR" \
    --quiet

JAR=$(ls "$TMPDIR"/frankframework-core-*.jar 2>/dev/null | head -1)
if [[ -z "$JAR" ]]; then
    echo "ERROR: frankframework-core JAR not found in $TMPDIR after download" >&2
    exit 1
fi

# ── 3. Extract FrankConfig.xsd ─────────────────────────────────────────────

XSD_PATH_IN_JAR="xml/xsd/FrankConfig.xsd"

if ! unzip -p "$JAR" "$XSD_PATH_IN_JAR" > "$TMPDIR/FrankConfig.xsd"; then
    echo "ERROR: $XSD_PATH_IN_JAR not found in $JAR" >&2
    echo "Available XSD files in JAR:"
    unzip -l "$JAR" | grep '\.xsd$' >&2
    exit 1
fi

# ── 4. Write to both configuration directories ─────────────────────────────

TARGETS=(
    "$VISCORUNNER_DIR/configurations/FrankConfig.xsd"
    "$VISCORUNNER_DIR/demo-configurations/FrankConfig.xsd"
)

for TARGET in "${TARGETS[@]}"; do
    cp "$TMPDIR/FrankConfig.xsd" "$TARGET"
    echo "Updated : $TARGET"
done

echo "Done."
