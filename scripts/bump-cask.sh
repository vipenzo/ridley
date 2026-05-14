#!/usr/bin/env bash
#
# Bump the Ridley Homebrew cask (vipenzo/homebrew-ridley) to a new version.
#
# Usage:
#   scripts/bump-cask.sh            # bump to the latest release tag
#   scripts/bump-cask.sh v1.12.0    # bump to a specific tag
#
# Requires: gh (authenticated), git, shasum, sed.

set -euo pipefail

TAP_REPO="vipenzo/homebrew-ridley"
APP_REPO="vipenzo/ridley"
CASK_PATH="Casks/ridley.rb"

# Resolve the tag.
if [[ $# -ge 1 ]]; then
  TAG="$1"
else
  TAG=$(gh -R "$APP_REPO" release list --limit 1 --json tagName --jq '.[0].tagName')
fi

if [[ -z "$TAG" || ! "$TAG" =~ ^v[0-9] ]]; then
  echo "Error: invalid tag '$TAG' (expected like v1.12.0)" >&2
  exit 1
fi
VERSION="${TAG#v}"
DMG_NAME="Ridley-${TAG}-macOS.dmg"

echo "==> Bumping cask to $TAG (version $VERSION)"

# Download the DMG and compute SHA256.
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

echo "==> Downloading $DMG_NAME from release"
gh -R "$APP_REPO" release download "$TAG" \
   --pattern "$DMG_NAME" --dir "$WORK_DIR"

SHA256=$(shasum -a 256 "$WORK_DIR/$DMG_NAME" | awk '{print $1}')
echo "    sha256: $SHA256"

# Clone the tap and update the cask.
TAP_DIR="$WORK_DIR/tap"
echo "==> Cloning $TAP_REPO"
gh repo clone "$TAP_REPO" "$TAP_DIR" -- --quiet

cd "$TAP_DIR"
sed -i.bak -E \
    -e "s/^([[:space:]]*version )\".*\"$/\1\"${VERSION}\"/" \
    -e "s/^([[:space:]]*sha256 )\".*\"$/\1\"${SHA256}\"/" \
    "$CASK_PATH"
rm "${CASK_PATH}.bak"

if git diff --quiet -- "$CASK_PATH"; then
  echo "==> Cask already at $VERSION — nothing to do"
  exit 0
fi

echo "==> Diff:"
git --no-pager diff -- "$CASK_PATH"

git add "$CASK_PATH"
git commit -m "Bump cask to $TAG"
git push origin HEAD

echo "==> Done: $TAP_REPO updated to $TAG"
