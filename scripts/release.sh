#!/usr/bin/env bash
# Cut a release: scripts/release.sh 0.4.0
#
# 1. Moves the "## Unreleased" notes in CHANGELOG.md under "## 0.4.0 – <date>".
# 2. Sets appVersionName in gradle.properties and MARKETING_VERSION in iosApp/project.yml.
# 3. Commits, tags v0.4.0 and pushes. The tag triggers .github/workflows/release.yml,
#    which builds the signed bundle, uploads it to Google Play (internal track) and
#    creates the GitHub release with the APK and these notes.
set -euo pipefail
cd "$(dirname "$0")/.."

version="${1:?usage: scripts/release.sh <semver, e.g. 0.4.0>}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "not a semver: $version"; exit 1; }

branch=$(git rev-parse --abbrev-ref HEAD)
[ "$branch" = "main" ] || { echo "release from main (you are on $branch)"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "working tree not clean"; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "main is not up to date with origin"; exit 1; }
git rev-parse -q --verify "refs/tags/v$version" >/dev/null && { echo "tag v$version exists"; exit 1; }

notes=$(scripts/changelog-section.sh Unreleased)
[ -n "$(printf '%s' "$notes" | grep -v '^###' | grep -v '^\s*$' || true)" ] || { echo "CHANGELOG.md: Unreleased is empty"; exit 1; }
printf '%s\n' "$notes" | grep -q '^### sv' || { echo "CHANGELOG.md: Unreleased needs a '### sv' part"; exit 1; }
printf '%s\n' "$notes" | grep -q '^### en' || { echo "CHANGELOG.md: Unreleased needs an '### en' part"; exit 1; }

today=$(date +%Y-%m-%d)
python3 - "$version" "$today" <<'EOF'
import re, sys
version, today = sys.argv[1:3]
p = "CHANGELOG.md"
s = open(p).read()
s = s.replace("## Unreleased\n", f"## Unreleased\n\n### sv\n\n### en\n\n## {version} – {today}\n", 1)
open(p, "w").write(s)

p = "gradle.properties"
s = open(p).read()
s = re.sub(r"^appVersionName=.*$", f"appVersionName={version}", s, flags=re.M)
open(p, "w").write(s)

p = "iosApp/project.yml"
s = open(p).read()
s = re.sub(r'MARKETING_VERSION: "[^"]*"', f'MARKETING_VERSION: "{version}"', s)
open(p, "w").write(s)
EOF

git add CHANGELOG.md gradle.properties iosApp/project.yml
git commit -q -m "Release $version"
git tag -a "v$version" -m "AnyLap $version"
git push -q origin main "v$version"
echo "Tagged v$version. Follow the build: https://github.com/VICCOM-devs/AnyLap-Camera-Lap-Timer/actions"
