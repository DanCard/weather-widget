# APT Repository (Debian/Ubuntu install)

The desktop app is distributed as a flat, GPG-signed apt repository whose files are **GitHub
Release assets** under the fixed tag `apt` — nothing is committed to any git branch, and the
`.deb` (>100MB, too big for git) lives outside the source tree entirely. Works on any Debian
testing/stable or Ubuntu machine (amd64).

## Installing (end users)

```bash
curl -fsSL https://github.com/DanCard/weather-widget/releases/download/apt/key.gpg | sudo tee /usr/share/keyrings/weather-widget.gpg > /dev/null
echo "deb [signed-by=/usr/share/keyrings/weather-widget.gpg] https://github.com/DanCard/weather-widget/releases/download/apt ./" | sudo tee /etc/apt/sources.list.d/weather-widget.list
sudo apt update
sudo apt install weather-widget-desktop
```

Upgrades then arrive via normal `sudo apt upgrade`.

The published build ships **without** premium API keys (NWS and Open-Meteo work out of the box,
no key needed). Keys for Silurian / WeatherAPI / OpenWeatherMap / Visual Crossing / Tomorrow.io
can be added in Settings.

## Publishing a release (maintainer)

1. Bump `packageVersion` in `desktop/build.gradle.kts`.
2. Run `scripts/apt-repo-publish.sh`.

The script builds the `.deb` with `-PpublicBuild` (bakes **no** API keys — and refuses to
publish if any leak in), assembles the flat repo (deb + `Packages.gz` + signed `InRelease`),
and uploads everything as assets on the `apt` release tag via `gh`, clobbering the indexes.
apt clients see the new version on their next `apt update`.

- **Do not delete the `apt` release/tag** — client sources.list files point at its asset URLs.
- Signing key: local GPG key `4A3DA6424F158FB45EC45ACCEED6F5FF6C716C97`
  ("Weather Widget APT Repo") — no passphrase, lives in `~/.gnupg` on the dev machine.
  Override with `APT_REPO_KEY_ID` if it is ever rotated (re-publish `key.gpg` and tell users
  to re-run the keyring download).
- Layout is flat (no `pool/`/`dists/`): release-asset URLs have no subdirectories, and with a
  single package the Debian-style nesting buys nothing. `Packages` records each `.deb` path
  (`./weather-widget-desktop_<ver>_amd64.deb`) explicitly, so apt never assumes structure.
- Old-version debs stay downloadable as assets but drop out of `Packages`, so apt only offers
  the latest.
