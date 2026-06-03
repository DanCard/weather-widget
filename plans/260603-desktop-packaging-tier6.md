# Tier 6 — Desktop Packaging, Install & Autostart

> Turns the desktop app from a `./gradlew :desktop:run`-in-a-terminal prototype into a real installed
> application that survives logout/reboot and launches on login. The user now depends on it daily
> (genmon reads its DB), so its dying when the terminal closes is the most practical remaining gap.

## Context

- Runs only via `./gradlew :desktop:run` in a Konsole → dies on logout/reboot, needs a terminal open.
- `desktop/build.gradle.kts` already declares `compose.desktop { nativeDistributions {
  targetFormats(Deb, AppImage); packageName="weather-widget-desktop"; packageVersion="1.0.0" } }` but
  it's untested and under-configured (no JVM modules, no Linux icon, no menu shortcut).
- Code is path-clean: no hardcoded `/home`, uses XDG dirs (`~/.config`, `~/.local/share`), so a
  packaged binary writes the same `weather.db` the genmon script reads — **packaging won't break genmon.**
- Tools: Java 21 (has `jpackage`), `dpkg-deb` present, **no `appimagetool`** → deb is the primary target.

## Goal

`./gradlew :desktop:packageDeb` produces a working `.deb` that installs a menu entry + icon, runs
correctly (DB + HTTPS work in the minimized runtime), autostarts on login, and won't double-launch.

## Workstream A — make the packaged build actually work (critical)

1. **JVM modules (the must-fix).** jpackage jlinks a minimized runtime; declare the modules the app
   needs or it crashes only when packaged:
   - `java.sql` — sqlite-jdbc / `DriverManager` (DB access). Without this the packaged app throws at
     first DB use even though dev `run` works.
   - TLS to api.weather.gov needs the security/crypto modules (`jdk.crypto.ec`, `jdk.crypto.cryptoki`),
     plus commonly `java.naming`, `java.management`.
   - Determine the real set with `jdeps` on the runtime classpath / iterate via runtime testing, then:
     ```
     nativeDistributions { modules("java.sql", "java.naming", "jdk.crypto.ec", "java.management") }
     ```
2. **Linux icon + menu shortcut.** Add a desktop icon PNG (the `drawable` dir holds Android icons —
   export/scale one to e.g. `desktop/icons/weather-widget.png`) and configure:
   ```
   nativeDistributions {
     linux { shortcut = true; menuGroup = "Utility"; iconFile.set(project.file("icons/weather-widget.png")) }
   }
   ```
   jpackage then emits `/usr/share/applications/*.desktop` + icon automatically.
3. **Verify the install layout** — deb installs under `/opt/weather-widget-desktop/`; binary at
   `/opt/weather-widget-desktop/bin/weather-widget-desktop`. Confirm it launches the tray standalone.

## Workstream B — autostart on login + single-instance guard

1. **Autostart entry.** Ship/install `~/.config/autostart/weather-widget-desktop.desktop`:
   ```
   [Desktop Entry]
   Type=Application
   Name=Weather Widget
   Exec=/opt/weather-widget-desktop/bin/weather-widget-desktop
   X-GNOME-Autostart-enabled=true
   ```
   Decide: ship it in the deb (postinst, but it's per-user — awkward) vs. a small "enable autostart"
   step/toggle. Simplest reliable: document + a helper, or a Settings toggle that writes the file.
2. **Single-instance guard (needed).** Autostart + a manual launch = two processes, and Dorkbox
   `SystemTray.get()` is a singleton — two instances means duplicate/お broken trays (same class of
   conflict as `:desktop:test`). Add a lock (e.g. `~/.local/share/weather-widget/.lock` via
   `FileChannel.tryLock`, or a localhost port bind) in `main()`; if held, exit immediately.

## Workstream C — make genmon survive repo removal (recommended)

The panel currently runs `python3 /home/dcar/projects/weather-widget/scripts/genmon-weather.py` — tied
to the repo checkout. Install the script to a stable location with the deb (e.g.
`/opt/weather-widget-desktop/genmon-weather.py` or `/usr/share/weather-widget/`), and document
updating the genmon command to that path so it keeps working if the repo is moved/deleted.

## Files

- `desktop/build.gradle.kts` — `nativeDistributions` modules, `linux { … }` icon/shortcut.
- `desktop/icons/weather-widget.png` *(new)* — desktop icon.
- `desktop/.../Main.kt` — single-instance lock at startup.
- autostart `.desktop` (shipped or written by a Settings toggle).
- packaging of `scripts/genmon-weather.py` into the deb.

## Tests / Verification

1. `./gradlew :desktop:packageDeb` succeeds; inspect with `dpkg-deb -c build/.../*.deb`.
2. `sudo apt install ./<deb>` (or `dpkg -i`); launch from the app menu → **tray appears, DB updates,
   popup works** (this is where a missing `java.sql`/crypto module would crash — confirm it doesn't).
3. Confirm HTTPS works: `app_logs` gets a REFRESH row and `observations` fill (TLS to NWS succeeded in
   the minimized runtime).
4. Single-instance: launch twice → second exits, only one tray.
5. Log out / reboot → app autostarts, tray present without a terminal.
6. genmon still shows the temperature (reads the same `weather.db`); update its command to the
   installed script path and confirm.
7. `./gradlew :shared:test :desktop:test` green (stop the running app first —
   [[desktop-test-running-app-conflict]]).

## Notes / caveats

- **AppImage**: Compose's `TargetFormat.AppImage` = jpackage app-image (a directory), not a true
  `.AppImage`; no `appimagetool` installed. Treat deb as the deliverable; revisit AppImage only if a
  portable single-file build is wanted.
- Bump `packageVersion` per release; keep it in sync with any version display.
- Packaged app writes XDG paths → existing `weather.db`/`config.json` and the genmon wiring keep
  working across the dev→installed transition (no data migration needed).
