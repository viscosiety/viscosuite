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

# GitLab 19.4 stores md5/sha1 only for Maven files (file_sha256 is null in the Packages API). The sha256 the
# BOM pins is that of the SERVED bytes, so when the API has none we compute it from a download of the file
# (job token, no redirects), after checking the byte count against the record's size.
published_sha() {
  _api="${CI_API_V4_URL:?}/projects/${CI_PROJECT_ID:?}"
  _auth="JOB-TOKEN: ${CI_JOB_TOKEN:?}"
  _pid=$(curl --fail --silent --show-error --header "$_auth" \
      "$_api/packages?package_type=maven&package_name=$1&package_version=${VERSION:?}&per_page=100" \
    | jq -r --arg n "$1" --arg v "$VERSION" '.[] | select(.name == $n and .version == $v) | .id' | head -1) || return 1
  [ -n "$_pid" ] || return 0
  _rec=$(curl --fail --silent --show-error --header "$_auth" "$_api/packages/$_pid/package_files?per_page=100" \
    | jq -c --arg f "$2" '[.[] | select(.file_name == $f)] | .[0] // empty') || return 1
  [ -n "$_rec" ] || return 0
  _sha=$(printf '%s' "$_rec" | jq -r '.file_sha256 // empty')
  if [ -n "$_sha" ]; then printf '%s\n' "$_sha"; return 0; fi
  _size=$(printf '%s' "$_rec" | jq -r '.size')
  _tmp=$(mktemp) || return 1
  if ! curl --fail --silent --show-error --header "$_auth" --output "$_tmp" \
      "$_api/packages/maven/$1/$VERSION/$2"; then
    rm -f "$_tmp"; echo "published_sha: cannot download $1 $2 $VERSION" >&2; return 1
  fi
  _got=$(wc -c < "$_tmp" | tr -d ' ')
  if [ "$_got" != "$_size" ]; then
    rm -f "$_tmp"; echo "published_sha: $2 served $_got bytes, the registry records $_size" >&2; return 1
  fi
  sha256sum "$_tmp" | cut -d' ' -f1
  rm -f "$_tmp"
}
