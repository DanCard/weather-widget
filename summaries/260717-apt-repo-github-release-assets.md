# APT repo via GitHub Release assets (2026-07-17)

## Goal
Let any Debian/Ubuntu machine `apt install weather-widget-desktop`. Official Debian non-free
inclusion is blocked (Gradle network builds, bundled JRE, sponsor/NEW-queue process), so the
chosen path is a self-hosted, GPG-signed apt repository. The LICENSE was rewritten first to
permit verbatim redistribution and packaging-only modification (Debian-distributable terms).

## User install

```bash
curl -fsSL https://github.com/DanCard/weather-widget/releases/download/apt/key.gpg | sudo tee /usr/share/keyrings/weather-widget.gpg > /dev/null
echo "deb [signed-by=/usr/share/keyrings/weather-widget.gpg] https://github.com/DanCard/weather-widget/releases/download/apt ./" | sudo tee /etc/apt/sources.list.d/weather-widget.list
sudo apt update && sudo apt install weather-widget-desktop
```

Upgrades arrive via normal `apt upgrade`.

## Design

- **Flat repo** (deb + `Packages.gz` + signed `InRelease` side by side, no `pool/`/`dists/`):
  simplest for a single package, and the only layout possible on release-asset URLs, which have
  no subdirectories. apt never assumes structure — `Packages` records each `Filename:` explicitly.
- **Hosted as GitHub Release assets** under fixed tag `apt`, not a gh-pages branch: the keyless
  jpackage `.deb` is **105MB** and GitHub rejects git files >100MB, so the original gh-pages plan
  was unworkable (also keeps the `.deb` out of the git tree entirely, per Danny's preference).
  Release assets allow 2GB; apt follows the 302 redirects to GitHub's CDN fine.
- **`-PpublicBuild` Gradle flag** (desktop/build.gradle.kts): bakes NO premium API keys into the
  published artifact. NWS + Open-Meteo work keyless out of the box; users add keys in Settings.
- **Signing key** `4A3DA6424F158FB45EC45ACCEED6F5FF6C716C97` ("Weather Widget APT Repo"),
  passphrase-less, in `~/.gnupg` on the dev machine. Override with `APT_REPO_KEY_ID`.

## Release procedure

1. Bump `packageVersion` in `desktop/build.gradle.kts`.
2. Run `scripts/apt-repo-publish.sh` — builds with `-PpublicBuild`, greps the generated
   `DesktopApiKeys.kt` and **refuses to publish if any key leaked in**, generates indexes with
   `dpkg-scanpackages`/`apt-ftparchive`, signs `InRelease`/`Release.gpg`, uploads via
   `gh release upload apt --clobber`. New deb uploads under a new filename; clobbered indexes
   flip clients to it on their next `apt update`.

**Never delete the `apt` release/tag** — every user's sources.list points at its asset URLs.

## Verification performed

Userspace apt client (no sudo) pointed at the live URLs with overridden
`Dir::Etc::SourceList`/`Dir::State`/`Dir::Cache` and the host's dpkg status:
`apt-get update` fetched and GPG-verified `InRelease` through the CDN redirects;
`apt-get install -s weather-widget-desktop` resolved cleanly
(`Inst weather-widget-desktop (1.0.0 Weather Widget:stable [amd64])`).

## Files

- `scripts/apt-repo-publish.sh` — publish pipeline (new)
- `docs/APT_REPO.md` — install snippet + maintainer docs (new)
- `desktop/build.gradle.kts` — `-PpublicBuild` flag (modified)
- `LICENSE` — redistribution grants (committed separately by Danny)
