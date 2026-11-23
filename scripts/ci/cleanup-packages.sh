#!/bin/sh
# Deletes package versions of this project's Maven registry that ViscoFoundry no longer supports and
# that are older than MIN_AGE_DAYS (30). "Supported" comes from the portal: GET
# $RELEASE_LIST_URL with "Authorization: Bearer $RELEASE_LIST_TOKEN" answers the release numbers it
# still needs (its allow-lists, the releases instances run, and every component version those
# releases use); this script keeps .keep["com.visco"]. It refuses to delete anything when the list
# cannot be read or is empty. Runs on the cleanup schedule with the job token (PACKAGE_CLEANUP=1);
# CLEANUP_DRY_RUN=1 prints the decisions without deleting.
#
# CLEANUP_DECIDE_ONLY=<file>: read "created_at<TAB>version<TAB>id" lines from the file, with the kept
# versions in KEEP_FILE and the cut-off in CUTOFF (ISO 8601, UTC), print the decisions and exit (the
# unit test).
set -eu
GROUP=com.visco
MIN_AGE_DAYS="${MIN_AGE_DAYS:-30}"
case "$MIN_AGE_DAYS" in ''|*[!0-9]*) echo "cleanup: MIN_AGE_DAYS must be a number" >&2; exit 2 ;; esac

decide() { # stdin: created_at \t version \t id; $1 = file of kept versions; $2 = cut-off
  awk -F'\t' -v cutoff="$2" '
    FNR == NR { if ($0 != "") keep[$0] = 1; next }
    { t = $1; v = $2; id = $3 }
    v in keep { print "keep " id " " v " (supported)"; next }
    t >= cutoff { print "keep " id " " v " (newer than the cut-off)"; next }
    { print "delete " id " " v }' "$1" -
}

if [ -n "${CLEANUP_DECIDE_ONLY:-}" ]; then
  decide "${KEEP_FILE:?}" "${CUTOFF:?}" < "$CLEANUP_DECIDE_ONLY"
  exit 0
fi

: "${RELEASE_LIST_TOKEN:?cleanup: RELEASE_LIST_TOKEN is not set}"
LIST_URL="${RELEASE_LIST_URL:-https://foundry.viscosiety.com/api/releases/supported}"
KEEP_FILE="$(mktemp)"
trap 'rm -f "$KEEP_FILE"' EXIT
AUTH_CFG="$(mktemp)"
trap 'rm -f "$KEEP_FILE" "$AUTH_CFG"' EXIT
chmod 600 "$AUTH_CFG"
printf 'header = "Authorization: Bearer %s"\n' "$RELEASE_LIST_TOKEN" > "$AUTH_CFG"
curl --fail --silent --show-error --config "$AUTH_CFG" "$LIST_URL" | jq -er --arg g "$GROUP" '.keep[$g] | if length > 0 then .[] else error("empty") end' > "$KEEP_FILE" \
  || { echo "cleanup: the supported-releases list could not be read or keeps nothing for $GROUP; nothing deleted" >&2; exit 1; }
echo "cleanup: ViscoFoundry supports $(wc -l < "$KEEP_FILE" | tr -d ' ') $GROUP version(s): $(tr '\n' ' ' < "$KEEP_FILE")"
CUTOFF="$(date -u -d "@$(( $(date +%s) - MIN_AGE_DAYS * 86400 ))" +%Y-%m-%dT%H:%M:%S)"

API="${CI_API_V4_URL:?}/projects/${CI_PROJECT_ID:?}"
. "$(dirname "$0")/lib/packages.sh"
token_cfg_init
for name in com/visco/viscolink com/visco/viscolink-pack-health com/visco/viscostore com/visco/viscosuite-release; do
  curl --fail --silent --show-error --config "$_VF_TOKEN_CFG" \
    "$API/packages?package_type=maven&package_name=$name&order_by=created_at&sort=desc&per_page=100" \
    | jq -r --arg n "$name" '.[] | select(.name == $n) | [.created_at, .version, .id] | @tsv' \
    | decide "$KEEP_FILE" "$CUTOFF" \
    | while read -r action id version _rest; do
        echo "$name $version: $action"
        if [ "$action" = delete ] && [ "${CLEANUP_DRY_RUN:-}" != 1 ]; then
          curl --fail --silent --show-error --config "$_VF_TOKEN_CFG" --request DELETE "$API/packages/$id" > /dev/null
        fi
      done
done
