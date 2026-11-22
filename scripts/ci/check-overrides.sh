#!/bin/sh
# check-overrides.sh: every Frank!Framework classpath override is registered in VISCO-OVERRIDES.md.
#
# ViscoLink shadows a handful of org.frankframework.* classes by staging a copy into the WAR's
# WEB-INF/classes (see VISCO-OVERRIDES.md). Each one is a temporary copy of an upstream file that has
# to be re-derived on an F!F bump and deleted once the fix is upstream, so an unregistered one is a
# copy nobody is tracking. This check fails the build on that.
#
# org/frankframework/visco/** is skipped: that is ViscoSuite's own code in a namespace of its own
# (the bearer-auth servlets, the Larva JSON runner), shadowing no framework class.
#
# Exit 0 = fine, 1 = a problem (one line per unregistered file on stderr). Run from the repository root.
set -eu

register="${1:-VISCO-OVERRIDES.md}"
tree="${2:-viscolink/src/main/java/org/frankframework}"

[ -f "$register" ] || { echo "check-overrides: $register not found" >&2; exit 1; }

if [ ! -d "$tree" ]; then
  echo "check-overrides: no overrides under $tree"
  exit 0
fi

missing=""
count=0
# -path pruning keeps the skip in find itself, so an empty visco/ subtree is not a special case.
for file in $(find "$tree" -name '*.java' -not -path "$tree/visco/*" | sort); do
  count=$((count + 1))
  # The register spells paths relative to viscolink/src/main/java/, as its table header says.
  relative=$(printf '%s' "$file" | sed 's|^viscolink/src/main/java/||')
  if ! grep -qF "$relative" "$register"; then
    missing="${missing}${relative}
"
  fi
done

if [ -n "$missing" ]; then
  echo "check-overrides: these Frank!Framework classpath overrides are not registered in $register:" >&2
  printf '%s' "$missing" | sed 's/^/  /' >&2
  echo "check-overrides: add a row per file (jar shadowed, version copied from, why, upstream issue/PR, removal condition)." >&2
  echo "check-overrides: if the file is NOT an override of a framework class, say so in the register's \"What counts as an override\" section." >&2
  exit 1
fi

echo "check-overrides: all $count override(s) under $tree are registered in $register"
