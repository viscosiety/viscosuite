#!/bin/sh
# check-changelog.sh [file]: the shape of CHANGELOG.md, which is published with every release as its notes
# (viscosuite-release-<version>-notes.md) and shown to users by the ViscoFoundry portal.
#   always       every "## " heading is "## [Unreleased]" or "## [A.B.C] — YYYY-MM-DD" (em dash or hyphen)
#   on a vA.B.C tag (CI_COMMIT_TAG)
#                a section "## [A.B.C]" exists, its first "### " heading is "### In short" with text under
#                it (the plain-language summary the portal shows), and "## [Unreleased]" has no content
# Exit 0 = fine, 1 = a problem (one line on stderr). Run from the repository root.
set -eu
file="${1:-CHANGELOG.md}"
[ -f "$file" ] || { echo "check-changelog: $file not found" >&2; exit 1; }

bad=$(grep -n '^## ' "$file" | grep -Ev '^[0-9]+:## \[(Unreleased|[0-9]+\.[0-9]+\.[0-9]+)\]( (—|-) [0-9]{4}-[0-9]{2}-[0-9]{2})?[[:space:]]*$' || true)
if [ -n "$bad" ]; then
  echo "check-changelog: version heading is not '## [Unreleased]' or '## [A.B.C] — YYYY-MM-DD': $bad" >&2
  exit 1
fi

# Only a release tag (exactly vA.B.C, what the release jobs publish) gets the strict check.
version=$(printf '%s' "${CI_COMMIT_TAG:-}" | sed -n 's/^v\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)$/\1/p')
[ -n "$version" ] || { echo "check-changelog: $file is well-formed"; exit 0; }

problem=$(awk -v v="$version" '
  /^## / {
    section = ""; sub_kind = ""
    if (index($0, "## [" v "]") == 1) { section = "version"; found = 1 }
    else if (index($0, "## [Unreleased]") == 1) section = "unreleased"
    next
  }
  /^### / {
    if (section == "unreleased") unreleased = 1
    if (section == "version") {
      is_short = ($0 ~ /^### In short[ \t]*$/)
      if (!seen_sub) { seen_sub = 1; first_is_short = is_short }
      sub_kind = is_short ? "short" : "other"
    }
    next
  }
  /[^ \t]/ {
    if (section == "unreleased") unreleased = 1
    if (section == "version" && sub_kind == "short") summary = 1
    if (section == "version" && !seen_sub) loose = 1
  }
  END {
    if (!found) { print "no section ## [" v "] for this tag"; exit }
    if (!first_is_short || loose) { print "section [" v "] must start with ### In short (the plain summary the portal shows)"; exit }
    if (!summary) { print "### In short of [" v "] is empty"; exit }
    if (unreleased) { print "## [Unreleased] still has content; move it into [" v "] or release it later" }
  }' "$file")
if [ -n "$problem" ]; then
  echo "check-changelog: $problem" >&2
  exit 1
fi
echo "check-changelog: $file is ready for release $version"
