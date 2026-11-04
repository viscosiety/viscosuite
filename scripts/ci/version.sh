#!/bin/sh
# The version this pipeline publishes (Maven packages, image tags, the release BOM).
#   a tag vA.B.C   -> A.B.C, which must equal the poms' A.B.C-SNAPSHOT base
#   a main commit  -> A.B.C-<CI_PIPELINE_ID>
# Run from the repository root: it reads the aggregator pom.xml.
set -eu
base=$(sed -n 's/.*<version>\([0-9][0-9.]*\)-SNAPSHOT<\/version>.*/\1/p' pom.xml | head -1)
[ -n "$base" ] || { echo "version.sh: pom.xml has no A.B.C-SNAPSHOT version" >&2; exit 1; }
if [ -n "${CI_COMMIT_TAG:-}" ]; then
  case "$CI_COMMIT_TAG" in
    v[0-9]*.[0-9]*.[0-9]*) tag="${CI_COMMIT_TAG#v}" ;;
    *) echo "version.sh: tag $CI_COMMIT_TAG is not vA.B.C" >&2; exit 1 ;;
  esac
  [ "$tag" = "$base" ] || { echo "version.sh: tag $CI_COMMIT_TAG does not match the poms' ${base}-SNAPSHOT; set the poms first" >&2; exit 1; }
  echo "$tag"
  exit 0
fi
echo "${base}-${CI_PIPELINE_ID:?CI_PIPELINE_ID is unset}"
