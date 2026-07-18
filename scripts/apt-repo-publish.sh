#!/usr/bin/env bash
# Publishes the desktop app as a flat, GPG-signed apt repository whose files are GitHub
# Release assets under the fixed tag "apt" (NOT committed to any git branch — release assets
# allow files >100MB, and nothing lands in the source tree).
#
#   apt base URL: https://github.com/DanCard/weather-widget/releases/download/apt
#
# Usage: scripts/apt-repo-publish.sh
#   To release a new version: bump packageVersion in desktop/build.gradle.kts, then run this.
#
# The repo is "flat" (deb + Packages + InRelease side by side — no pool/dists); release-asset
# URLs have no subdirectories, so flat is also the only layout that works here.
# See docs/APT_REPO.md for the user-facing install snippet.
set -euo pipefail

REPO_ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
GH_REPO="DanCard/weather-widget"
TAG=apt
# Dedicated no-passphrase signing key ("Weather Widget APT Repo"). Its public half is
# exported as key.gpg below; users install that into /usr/share/keyrings.
KEY_ID="${APT_REPO_KEY_ID:-4A3DA6424F158FB45EC45ACCEED6F5FF6C716C97}"

cd "$REPO_ROOT"

echo "==> Building key-free public .deb (-PpublicBuild)"
./gradlew :desktop:packageDeb -PpublicBuild -q

DEB="$(ls -t desktop/build/compose/binaries/main/deb/*.deb | head -1)"
[ -n "$DEB" ] || { echo "ERROR: no .deb produced" >&2; exit 1; }

# Guard: never publish a deb with baked API keys. The generated source must have no entries.
GEN=desktop/build/generated/apikeys/kotlin/com/weatherwidget/desktop/DesktopApiKeys.kt
if grep -qE '"\S+" to "' "$GEN"; then
    echo "ERROR: $GEN contains baked API keys — refusing to publish. Was -PpublicBuild dropped?" >&2
    exit 1
fi
echo "==> $(basename "$DEB") ($(du -m "$DEB" | cut -f1)MB)"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

echo "==> Assembling flat repo in $STAGE"
cp "$DEB" "$STAGE/"
gpg --export "$KEY_ID" > "$STAGE/key.gpg"

cd "$STAGE"
# Flat-repo index. Release-asset URLs are flat, so Filename: must be ./<deb> — strip the
# "./" dir scan prefix is exactly what dpkg-scanpackages emits for the current directory.
dpkg-scanpackages --multiversion . /dev/null > Packages
gzip -9kf Packages

apt-ftparchive \
    -o APT::FTPArchive::Release::Origin="weather-widget" \
    -o APT::FTPArchive::Release::Label="Weather Widget" \
    -o APT::FTPArchive::Release::Suite=stable \
    -o APT::FTPArchive::Release::Architectures=amd64 \
    -o APT::FTPArchive::Release::Description="Weather Widget desktop apt repo" \
    release . > Release.tmp
mv Release.tmp Release

echo "==> Signing"
gpg --default-key "$KEY_ID" --batch --yes --clearsign -o InRelease Release
gpg --default-key "$KEY_ID" --batch --yes --armor --detach-sign -o Release.gpg Release
gpg --verify InRelease >/dev/null 2>&1 || { echo "ERROR: InRelease signature failed verification" >&2; exit 1; }

echo "==> Uploading release assets to tag '$TAG'"
if ! gh release view "$TAG" --repo "$GH_REPO" >/dev/null 2>&1; then
    gh release create "$TAG" --repo "$GH_REPO" \
        --title "APT repository" \
        --notes "Flat apt repository. Install instructions: docs/APT_REPO.md. Do not delete this release; apt clients fetch from its asset URLs." \
        --latest=false
fi
# --clobber replaces same-named assets (indexes, key, re-published debs). Older-version debs
# remain as assets but drop out of Packages, so apt no longer offers them — harmless.
gh release upload "$TAG" --repo "$GH_REPO" --clobber \
    "$(basename "$DEB")" Packages Packages.gz Release Release.gpg InRelease key.gpg

echo "==> Done: https://github.com/$GH_REPO/releases/download/$TAG/InRelease"
