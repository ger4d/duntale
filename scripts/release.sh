#!/usr/bin/env bash

set -euo pipefail

usage() {
  echo "Usage: scripts/release.sh (--minor|--patch|--major|--set <version>) [--dry-run]" >&2
  echo "  --minor, --patch  Bump the last version segment: 0.1.0 -> 0.1.1" >&2
  echo "  --major           Bump the middle version segment: 0.1.0 -> 0.2.0" >&2
  echo "  --set <version>   Use an explicit version string" >&2
  echo "  --dry-run         Print the computed version without editing or pushing" >&2
  exit 1
}

dry_run=false
mode=""
explicit_version=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      dry_run=true
      shift
      ;;
    --minor|--patch|--major)
      if [[ -n "$mode" ]]; then
        usage
      fi

      mode="$1"
      shift
      ;;
    --set)
      if [[ -n "$mode" || $# -ne 2 ]]; then
        usage
      fi

      mode="--set"
      explicit_version="$2"
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

if [[ -z "$mode" ]]; then
  usage
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

current_version="$(python3 - <<'PY'
import json
with open('src/main/resources/manifest.json', 'r', encoding='utf-8') as handle:
    print(json.load(handle)['Version'])
PY
)"

if [[ ! "$current_version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "Current manifest version '$current_version' must use X.Y.Z format for automatic bumps." >&2
  exit 1
fi

major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"

case "$mode" in
  --minor|--patch)
    patch=$((patch + 1))
    version="${major}.${minor}.${patch}"
    ;;
  --major)
    minor=$((minor + 1))
    patch=0
    version="${major}.${minor}.${patch}"
    ;;
  --set)
    version="$explicit_version"
    if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z]+)*$ ]]; then
      echo "Version must look like 0.1.0 or 0.1.0-rc1" >&2
      exit 1
    fi
    ;;
  *)
    usage
    ;;
esac

if [[ "$dry_run" == true ]]; then
  echo "Current version: $current_version"
  echo "Next version: $version"
  echo "Tag: v$version"
  exit 0
fi

if [[ -n "$(git status --short)" ]]; then
  echo "Working tree must be clean before creating a release." >&2
  exit 1
fi

perl -0pi -e 's/^version = ".*"/version = "'"$version"'"/m' build.gradle.kts
perl -0pi -e 's/"Version":\s*"[^"]+"/"Version": "'"$version"'"/' src/main/resources/manifest.json

git add build.gradle.kts src/main/resources/manifest.json
git commit -m "release: v$version"
git tag -a "v$version" -m "Release v$version"
git push origin HEAD --follow-tags

echo "Bumped ${current_version} -> ${version} and pushed tag v${version}. GitHub Actions will build and publish the shadow jar."