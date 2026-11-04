# Shared by publish.sh and write-release-bom.sh (sourced, POSIX sh): idempotent uploads to the project's
# Maven package registry. A re-run of a tag pipeline (or a retry of a partly failed publish) uploads the
# same version again; with Maven duplicates off the registry answers 4xx, so each upload first asks what
# is already there.
#   published_sha <package_name> <file_name>      sha256 of that file at $VERSION, or nothing
#   upload_decision <published_sha> <staged_sha>  prints skip (same bytes), upload (nothing there) or
#                                                 conflict (other bytes: never overwrite silently)
# Needs CI_API_V4_URL, CI_PROJECT_ID, CI_JOB_TOKEN, VERSION, curl and jq.

upload_decision() {
  if [ -z "$1" ]; then echo upload
  elif [ "$1" = "$2" ]; then echo skip
  else echo conflict; fi
}

published_sha() {
  _api="${CI_API_V4_URL:?}/projects/${CI_PROJECT_ID:?}"
  _auth="JOB-TOKEN: ${CI_JOB_TOKEN:?}"
  _pid=$(curl --fail --silent --show-error --header "$_auth" \
      "$_api/packages?package_type=maven&package_name=$1&package_version=${VERSION:?}&per_page=100" \
    | jq -r --arg n "$1" --arg v "$VERSION" '.[] | select(.name == $n and .version == $v) | .id' | head -1) || return 1
  [ -n "$_pid" ] || return 0
  curl --fail --silent --show-error --header "$_auth" "$_api/packages/$_pid/package_files?per_page=100" \
    | jq -r --arg f "$2" '.[] | select(.file_name == $f) | .file_sha256' | head -1
}
