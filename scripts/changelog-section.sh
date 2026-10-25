#!/usr/bin/env bash
# Print one section of CHANGELOG.md.
#   scripts/changelog-section.sh 0.4.0        -> the "## 0.4.0" section
#   scripts/changelog-section.sh Unreleased   -> the "## Unreleased" section
#   scripts/changelog-section.sh 0.4.0 sv     -> only the "### sv" part of it (Play "what's new")
set -euo pipefail
version="${1:?version}"
lang="${2:-}"
file="$(dirname "$0")/../CHANGELOG.md"

section=$(awk -v v="$version" '
  /^## / { on = ($2 == v) ; next }
  on { print }
' "$file")

if [ -z "$lang" ]; then
  printf '%s\n' "$section" | sed -e '/./,$!d'
else
  printf '%s\n' "$section" | awk -v l="$lang" '
    /^### / { on = ($2 == l) ; next }
    on { print }
  ' | sed -e '/./,$!d' -e :a -e '/^\n*$/{$d;N;ba' -e '}'
fi
