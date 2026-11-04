#!/bin/sh
# Collects what CI publishes into viscorunner/target/publish/ with version-free names, the module
# poms (already carrying this pipeline's version through versions:set) and a SHA256SUMS file. Runs in
# the package job after `./mvnw install -pl viscolink,packs/health,viscostore` so the files are the
# installed bytes; publish.sh uploads exactly these and the fat images fetch exactly these.
set -eu
VERSION="${1:?usage: stage-publish.sh <version>}"
out=viscorunner/target/publish
rm -rf "$out"; mkdir -p "$out"
cp "viscolink/target/viscolink-${VERSION}.war"                       "$out/viscolink.war"
cp "viscolink/target/viscolink-${VERSION}-classes.jar"               "$out/viscolink-classes.jar"
cp viscolink/pom.xml                                                 "$out/viscolink.pom"
cp "packs/health/target/viscolink-pack-health-${VERSION}-overlay.zip" "$out/viscolink-pack-health-overlay.zip"
cp packs/health/pom.xml                                              "$out/viscolink-pack-health.pom"
cp viscostore/target/viscostore.war                                  "$out/viscostore.war"
cp viscostore/pom.xml                                                "$out/viscostore.pom"
for f in viscolink.war viscolink-classes.jar viscolink-pack-health-overlay.zip viscostore.war; do
  size=$(stat -c%s "$out/$f" 2>/dev/null || stat -f%z "$out/$f")
  [ "$size" -gt 1000000 ] || [ "$f" = viscolink-classes.jar ] || { echo "stage-publish: $f is $size bytes: a stale stub (run install first)" >&2; exit 1; }
done
( cd "$out" && sha256sum viscolink.war viscolink-classes.jar viscolink-pack-health-overlay.zip viscostore.war > SHA256SUMS )
echo "$VERSION" > "$out/VERSION"
cat "$out/SHA256SUMS"
