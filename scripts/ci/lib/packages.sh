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

# token_cfg_init: a 0600 curl config holding the job token header, so the token never appears on a command
# line (ps). Sets $_VF_TOKEN_CFG in the CURRENT shell (do not call it in $(...)); callers pass
# --config "$_VF_TOKEN_CFG". Idempotent; the file is removed when the shell exits.
token_cfg_init() {
  [ -z "${_VF_TOKEN_CFG:-}" ] || return 0
  _VF_TOKEN_CFG=$(mktemp) || return 1
  trap 'rm -f "$_VF_TOKEN_CFG"' EXIT
  chmod 600 "$_VF_TOKEN_CFG"
  printf 'header = "JOB-TOKEN: %s"\n' "${CI_JOB_TOKEN:?}" > "$_VF_TOKEN_CFG"
}

# GitLab 19.4 stores md5/sha1 only for Maven files (file_sha256 is null in the Packages API). The sha256 the
# BOM pins is that of the SERVED bytes, so when the API has none we compute it from a download of the file
# (job token, no redirects), after checking the byte count against the record's size.
published_sha() {
  _api="${CI_API_V4_URL:?}/projects/${CI_PROJECT_ID:?}"
  token_cfg_init || return 1
  _t=$(mktemp) || { echo "published_sha: cannot create a temp file" >&2; return 1; }
  curl --fail --silent --show-error --config "$_VF_TOKEN_CFG" --output "$_t" \
      "$_api/packages?package_type=maven&package_name=$1&package_version=${VERSION:?}&per_page=100" \
    || { rm -f "$_t"; echo "published_sha: cannot list packages for $1" >&2; return 1; }
  [ -s "$_t" ] || { rm -f "$_t"; echo "published_sha: empty package listing for $1" >&2; return 1; }
  _pid=$(jq -rn --arg n "$1" --arg v "$VERSION" 'input | if type != "array" then error("not a JSON array") else [.[] | select(.name == $n and .version == $v) | .id] | .[0] // empty end' "$_t") \
    || { rm -f "$_t"; echo "published_sha: cannot read the package listing for $1" >&2; return 1; }
  [ -n "$_pid" ] || { rm -f "$_t"; return 0; }
  curl --fail --silent --show-error --config "$_VF_TOKEN_CFG" --output "$_t" "$_api/packages/$_pid/package_files?per_page=100" \
    || { rm -f "$_t"; echo "published_sha: cannot list files of $1" >&2; return 1; }
  [ -s "$_t" ] || { rm -f "$_t"; echo "published_sha: empty file listing for $1" >&2; return 1; }
  _rec=$(jq -cn --arg f "$2" 'input | if type != "array" then error("not a JSON array") else [.[] | select(.file_name == $f)] | .[0] // empty end' "$_t") \
    || { rm -f "$_t"; echo "published_sha: cannot read the file listing for $1" >&2; return 1; }
  rm -f "$_t"
  [ -n "$_rec" ] || return 0
  _sha=$(printf '%s' "$_rec" | jq -r '.file_sha256 // empty')
  if [ -n "$_sha" ]; then printf '%s\n' "$_sha"; return 0; fi
  _size=$(printf '%s' "$_rec" | jq -r '.size')
  _tmp=$(mktemp) || return 1
  if ! curl --fail --silent --show-error --config "$_VF_TOKEN_CFG" --output "$_tmp" \
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
