#!/bin/sh
# write-release-bom.sh <version>: fills viscorunner/release/viscosuite-release.template.json with this
# pipeline's facts and uploads it as com.visco:viscosuite-release:<version> (one JSON file) to the
# project's Maven registry. Runs in the release job (image docker:27 + curl + jq) after the manifests:
#   SHA_*            from the Packages API (package_files[].file_sha256 of the published files), so the BOM
#                    pins what the registry really serves, not what we uploaded
#   BASE_DIGEST      the multi-arch manifest digest of viscobase:<version> (container registry API)
#   POSTGRES_DIGEST  docker buildx imagetools inspect of postgres:16 at release time
# Then proves anonymous pull (a public project; the Standard edition fetches without a token): one
# package file is fetched WITHOUT a token and must answer 200 with the pinned sha256.
set -eu
VERSION="${1:?usage: write-release-bom.sh <version>}"
API="${CI_API_V4_URL:?}/projects/${CI_PROJECT_ID:?}"
auth="JOB-TOKEN: ${CI_JOB_TOKEN:?}"
out="viscorunner/target/viscosuite-release-${VERSION}.json"
. "$(dirname "$0")/lib/packages.sh"

api() { curl --fail --silent --show-error --header "$auth" "$@"; }

package_id() { # <package_name> -> id of the package at $VERSION
  api "$API/packages?package_type=maven&package_name=$1&package_version=$VERSION&per_page=100" \
    | jq -r --arg n "$1" --arg v "$VERSION" '.[] | select(.name == $n and .version == $v) | .id' | head -1
}
file_sha() { # <package_name> <file_name>
  pid=$(package_id "$1"); [ -n "$pid" ] || { echo "write-release-bom: no package $1 $VERSION" >&2; exit 1; }
  api "$API/packages/$pid/package_files?per_page=100" | jq -r --arg f "$2" '.[] | select(.file_name == $f) | .file_sha256' | head -1
}

SHA_STORE=$(file_sha com/visco/viscostore "viscostore-${VERSION}.war")
SHA_PACK_HEALTH=$(file_sha com/visco/viscolink-pack-health "viscolink-pack-health-${VERSION}-overlay.zip")
[ -n "$SHA_STORE" ] && [ -n "$SHA_PACK_HEALTH" ] || { echo "write-release-bom: a package file sha256 is missing" >&2; exit 1; }

BASE_IMAGE="${CI_REGISTRY_IMAGE:?}/viscobase"
repo_id=$(api "$API/registry/repositories" | jq -r '.[] | select(.name == "viscobase") | .id')
BASE_DIGEST=$(api "$API/registry/repositories/$repo_id/tags/$VERSION" | jq -r '.digest')
case "$BASE_DIGEST" in sha256:*) ;; *) echo "write-release-bom: no digest for viscobase:$VERSION" >&2; exit 1 ;; esac
POSTGRES_DIGEST=$(docker buildx imagetools inspect docker.io/library/postgres:16 --format '{{json .Manifest.Digest}}' | tr -d '"')
case "$POSTGRES_DIGEST" in sha256:*) ;; *) echo "write-release-bom: no digest for postgres:16" >&2; exit 1 ;; esac

jq --arg v "$VERSION" --arg img "$BASE_IMAGE" --arg bd "$BASE_DIGEST" --arg ss "$SHA_STORE" --arg sp "$SHA_PACK_HEALTH" --arg pd "$POSTGRES_DIGEST" '
  walk(if type == "string" then
         gsub("\\$\\{VERSION\\}"; $v) | gsub("\\$\\{BASE_IMAGE\\}"; $img) | gsub("\\$\\{BASE_DIGEST\\}"; $bd)
         | gsub("\\$\\{SHA_STORE\\}"; $ss) | gsub("\\$\\{SHA_PACK_HEALTH\\}"; $sp) | gsub("\\$\\{POSTGRES_DIGEST\\}"; $pd)
       else . end)' viscorunner/release/viscosuite-release.template.json > "$out"
grep -q '\${' "$out" && { echo "write-release-bom: unfilled placeholder in $out" >&2; grep -n '\${' "$out" >&2; exit 1; }

url="$API/packages/maven/com/visco/viscosuite-release/$VERSION/viscosuite-release-$VERSION.json"
# idempotent: a re-run of the release pipeline finds the BOM already there (same bytes: skip, other bytes: stop)
bom_have=$(published_sha com/visco/viscosuite-release "viscosuite-release-$VERSION.json") || { echo "write-release-bom: cannot ask the registry about the BOM" >&2; exit 1; }
case $(upload_decision "$bom_have" "$(sha256sum "$out" | cut -d' ' -f1)") in
  skip) echo "write-release-bom: $url already published with the same sha256, skipping the upload" ;;
  conflict) echo "write-release-bom: viscosuite-release-$VERSION.json is already published with another content; delete it first" >&2; exit 1 ;;
  *)
    curl --fail --silent --show-error --header "$auth" --upload-file "$out" "$url" > /dev/null
    echo "write-release-bom: uploaded $url" ;;
esac

# Anonymous pull proof (no token at all).
anon="$API/packages/maven/com/visco/viscostore/$VERSION/viscostore-$VERSION.war"
tmp=$(mktemp)
curl --fail --silent --show-error --location --output "$tmp" "$anon" || { echo "write-release-bom: anonymous pull of $anon failed: enable 'allow anyone to pull' on the package registry" >&2; exit 1; }
got=$(sha256sum "$tmp" | cut -d' ' -f1); rm -f "$tmp"
[ "$got" = "$SHA_STORE" ] || { echo "write-release-bom: anonymous pull served sha256 $got, the registry reports $SHA_STORE" >&2; exit 1; }
echo "write-release-bom: anonymous pull OK ($anon)"
cat "$out"
