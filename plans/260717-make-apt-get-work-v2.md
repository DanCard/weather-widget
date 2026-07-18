# Self-hosted apt repo for weather-widget-desktop

## Context
License now permits redistribution, but official Debian inclusion is blocked by build/process requirements. Goal: any Debian/Ubuntu machine can `apt install weather-widget-desktop` after a one-time repo add. Approach: GPG-signed static apt repo published to a `gh-pages` branch of the existing GitHub repo (`DanCard/weather-widget`), served by GitHub Pages. No new hosting, no server.

## Repo layout — FLAT repo (published to gh-pages, served at https://dancard.github.io/weather-widget/)
An apt repo is just static files on a web server. Flat layout = everything in one directory, no pool/dists nesting (that's for archives with thousands of packages):
- `key.gpg` — exported public signing key
- `weather-widget-desktop_<ver>_amd64.deb` — the package itself, at top level
- `Packages` / `Packages.gz` — index listing the .deb (name, version, path, SHA256)
- `InRelease` — GPG-signed summary of the index

User's sources.list line: `deb [signed-by=/usr/share/keyrings/weather-widget.gpg] https://dancard.github.io/weather-widget ./`

## Steps

1. **Keyless public build flag** — `desktop/build.gradle.kts` (`generateDesktopApiKeys` block, ~line 20-62): when Gradle property `-PpublicBuild` is set, skip local.properties/env keys so premium API keys are NOT baked into the published .deb. NWS + Open-Meteo need no keys, app works out of the box; users can still add keys in Settings.

2. **Signing key** — generate a dedicated no-passphrase GPG key ("Weather Widget APT repo") in the default keyring; export public half as `key.gpg`.

3. **Publish script** — new `scripts/apt-repo-publish.sh`:
   - `./gradlew :desktop:packageDeb -PpublicBuild`
   - Assemble flat tree in a worktree of `gh-pages` (orphan branch); copy .deb to top level
   - `dpkg-scanpackages` → `Packages`/`Packages.gz`; `apt-ftparchive release` → `Release`; `gpg --clearsign` → `InRelease`
   - Commit + push `gh-pages`; add `.nojekyll`
   - Re-runnable for future versions (bump `packageVersion` first; script keeps old debs in pool)

4. **Enable GitHub Pages** on `gh-pages` branch via `gh api`.

5. **Docs** — short `docs/APT_REPO.md` with the user-facing install snippet (keyring download, sources.list.d entry, `apt install weather-widget-desktop`) and the release procedure.

## Risk / checkpoint
- **GitHub 100MB per-file hard limit**: jpackage debs (bundled JRE) are typically 80–120MB. After the build, check size. If >100MB: reduce jlink modules, or fall back to a flat repo hosted on GitHub Releases (`deb [signed-by=…] https://github.com/…/releases/download/apt ./`) — decide only if the limit is actually hit.

## Verification
- `gpg --verify` on `InRelease`/`Release.gpg`
- After Pages deploys: `curl -I` the `InRelease` and `.deb` URLs
- Real end-to-end (needs sudo, Danny runs it): add keyring + list file, `sudo apt update && sudo apt install weather-widget-desktop`, confirm tray app launches and `DesktopApiKeys.DEFAULTS` is empty in the installed jar.
