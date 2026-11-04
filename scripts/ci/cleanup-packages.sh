#!/bin/sh
# Deletes old main-commit package versions (A.B.C-<pipelineId>) from the project's Maven registry,
# keeping every release (a version without '-') and the newest KEEP (10) others per package name.
# Runs in the cleanup job with the job token. Nothing else is ever deleted.
# CLEANUP_DECIDE_ONLY=<file>: read "created_at<TAB>version<TAB>id" lines (newest first) from the file,
# print the decisions and exit (the unit test).
set -eu
KEEP="${KEEP:-10}"

decide() { # stdin: created_at \t version \t id, newest first
  awk -F'\t' -v keep="$KEEP" '
    { v = $2; id = $3 }
    v !~ /-/ { print "keep " id " release"; next }
    { n++; if (n <= keep) print "keep " id " " v; else print "delete " id " " v }'
}

if [ -n "${CLEANUP_DECIDE_ONLY:-}" ]; then
  decide < "$CLEANUP_DECIDE_ONLY"
  exit 0
fi

API="${CI_API_V4_URL:?}/projects/${CI_PROJECT_ID:?}"
auth="JOB-TOKEN: ${CI_JOB_TOKEN:?}"
for name in com/visco/viscolink com/visco/viscolink-pack-health com/visco/viscostore com/visco/viscosuite-release; do
  curl --fail --silent --show-error --header "$auth" \
    "$API/packages?package_type=maven&package_name=$name&order_by=created_at&sort=desc&per_page=100" \
    | jq -r --arg n "$name" '.[] | select(.name == $n) | [.created_at, .version, .id] | @tsv' \
    | decide \
    | while read -r action id version; do
        echo "$name $version: $action"
        if [ "$action" = delete ]; then
          curl --fail --silent --show-error --header "$auth" --request DELETE "$API/packages/$id" > /dev/null
        fi
      done
done
