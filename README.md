# Weather Widget

[![Get it on Google Play](https://img.shields.io/badge/Google%20Play-Weather%20Analyzer%20Widget-414141?logo=googleplay)](https://play.google.com/store/apps/details?id=com.weatherwidget)

Android and Linux weather widget.  Graphs daily , hourly forecast for temperature, cloud cover and rain.

## Overview

Provides real-time weather updates and forecasts right on your home screen.

It also includes a **Linux desktop companion** (Compose for Desktop) that shares the same weather layer and adds a system-tray temperature readout, a forecast-accuracy database, and an optional clock-sized panel readout via XFCE's Generic Monitor (genmon). See [Desktop App (Linux)](#desktop-app-linux).

## Key Features

* **History comparison:**
  * Header row shows delta from prior day
  * Delta from from forecast for current temp
  * Able to view prior day forecast for comparison to today
* **Multi-Source Weather Forecast Integration:**
  * Seamless toggling between primary weather providers: NWS (US-only, official government data), Open-Meteo (global, no API key), and Silurian.
  * Hidden/configurable support for Tomorrow.io, WeatherAPI, Visual Crossing, and OpenWeatherMap (key-based).
  * Priority fallback logic to fetch from alternate sources if a primary source is unavailable or lacks coverage for the current location.
* **Adaptive Android Widget Layouts:**
  * Dynamically adapts its UI layout to fit any widget size from 1x1 up to 8+ columns and 2+ rows.
  * *1x1:* Show high/low extrema, weather condition icon, and current temp (if space allows).
  * *1x3 (Narrow/Horizontal):* Shows yesterday, today, and tomorrow in clean text mode.
  * *2x3 (Graphical):* Adds daily graphical temperature range bars.
  * *4+ Columns / 2+ Rows:* Adds full forecast graphs and visualizes up to 7+ days.
  * Generous touch targets for left/right navigation, view toggling, and API selection.
* **Custom Graphical Renderers:**
  * *Daily Graph:* Renders custom temperature bars. For past days, overlays 1-day-ahead forecast snapshots (yellow bars) next to actual observations for visual comparison.
  * *Hourly Graph:* Renders a smooth Bezier temperature curve with min/max labels, start/end values, and a vertical "Now" indicator line.
  * *Precipitation Graph:* Shows hourly chance of rain (0-100%) and expected rain volumes.
  * *Cloud Cover Graph:* Displays hourly cloud coverage percentage.
  * *Label Collision Avoidance:* Intelligent positioning algorithms prevent overlapping text elements.
* **Current Temperature Interpolation & Units:**
  * Real-time current temperature estimation by interpolating hourly forecast data points, saving battery by avoiding redundant network fetches.
  * Support for both Celsius and Fahrenheit, updating all labels and graphs automatically.
* **Detailed Forecast Accuracy Tracking:**
  * Captures daily 1-day-ahead forecast snapshots before an 8:00 PM cutoff.
  * Retrieves historical observations from NWS (with up to 5 nearby fallback stations for resilience).
  * Compares predictions vs. observations to compute detailed 30-day statistics: high/low error margins, directional temperature bias, and a 0-5 rating score.
  * *Forecast History Activity:* Inspect forecast evolution over time relative to actual weather.
* **Battery & Power-Aware Sync System:**
  * Separation of light UI updates (10-16 min) from heavy network operations.
  * Dynamic API fetch intervals based on battery levels (scaling from 60 min on charger down to 480 min/suspended under low battery).
  * WorkManager-based updating with work-stall recovery to prevent frozen widgets on devices with aggressive OEM battery-saving controls.
* **Linux Desktop Companion App (Compose Multiplatform):**
  * System-tray temperature icon and tray context menu.
  * Graphical pop-up window showing hourly/daily graphs and details, mirroring the Android layout.
  * *XFCE Panel Integration (Genmon):* High-performance IPC Server supplying clock-sized Pango markup weather summaries directly to the XFCE panel.
  * Single-instance enforcement (last-launch-wins) and autostart capabilities.
* **Diagnostic Tools & Play Store Hardening:**
  * Diagnostics UI showing persistent database logs (`app_logs`) to track API fetches, scheduler updates, and system events.
  * Explicit background location disclosures and a built-in privacy policy viewer.

## Tech Stack

The application is built using modern Android development practices and libraries:

*   **Kotlin:** The primary programming language.
*   **Coroutines & Flow:** For asynchronous programming and reactive data streams.
*   **Hilt:** For Dependency Injection.
*   **Room:** For local database and caching.
*   **Ktor:** For making network requests to the weather API.
*   **WorkManager:** For scheduling background sync tasks.

## Install (Android)

Available on Google Play: **[Weather Analyzer Widget](https://play.google.com/store/apps/details?id=com.weatherwidget)**

After installing, long-press your home screen → **Widgets** → **Weather Widget**, and drag it into
place. Resize it to anything from 1x1 upward; the layout adapts to the space it is given. NWS and
Open-Meteo work with no setup — the other sources are optional and key-based, configurable in
Settings.

## Setup Instructions

To build and run this project locally, you will need a valid Weather API Key. 

1.  Clone the repository.
2.  Create a file named `local.properties` in the root directory of the project if it doesn't already exist.
3.  Add your API key to the `local.properties` file using the following format:

    ```properties
    WEATHER_API_KEY=your_actual_api_key_here
    ```

4.  Open the project in Android Studio and sync with Gradle files.
5.  Build and run the application on an emulator or physical device.

## Desktop App (Linux)

The `:desktop` module is a [Compose for Desktop](https://www.jetbrains.com/lp/compose-multiplatform/)
tray app that reuses the shared weather/networking layer. It shows the current temperature as a
system-tray icon, a draggable popup with hourly/daily graphs, and a forecast-accuracy view. Weather
data is cached in a local SQLite database (`~/.local/share/weather-widget/weather.db`).

> **Desktop Environment Note:** Currently, the Linux desktop companion has only been tested on **XFCE**. If you would like support or testing verified on other popular desktop environments like GNOME or KDE, please let me know.

### Install via apt (Debian/Ubuntu)

The desktop app is published as a GPG-signed apt repository — no building required:

```bash
curl -fsSL https://github.com/DanCard/weather-widget/releases/download/apt/key.gpg | sudo tee /usr/share/keyrings/weather-widget.gpg > /dev/null
echo "deb [signed-by=/usr/share/keyrings/weather-widget.gpg] https://github.com/DanCard/weather-widget/releases/download/apt ./" | sudo tee /etc/apt/sources.list.d/weather-widget.list
sudo apt update && sudo apt install weather-widget-desktop
```

Upgrades arrive through normal `sudo apt upgrade`. The published build ships without premium
API keys — NWS and Open-Meteo work out of the box; add your own keys in Settings for the other
sources. Details and maintainer docs: [docs/APT_REPO.md](docs/APT_REPO.md).

### Build & install (from source)

Requires **Java 21** and, to build the installer, a full JDK that includes `jpackage` (Android
Studio's bundled JBR does **not** — set `JPACKAGE_HOME` or `-Djpackage.home` to a full JDK, or install
one at `/usr/lib/jvm/java-21-openjdk-amd64`).

```bash
# Build the repo-local app distribution used by autostart
./gradlew :desktop:createDistributable

# Build, stop the running app, and start the new repo-local distributable
scripts/buildStart-desktop.sh

# Development only: run without the distributable wrapper
./gradlew :desktop:run

# Optional installer build, if a system package is wanted
./gradlew :desktop:packageDeb
```

Daily autostart uses `scripts/desktop-app-launcher-and-autostart.sh`, which launches the repo-local
distributable at `desktop/build/compose/binaries/main/app/weather-widget-desktop/bin/weather-widget-desktop`.
If the distributable is missing, the script rebuilds it once with `:desktop:createDistributable`.
Use `scripts/buildStart-desktop.sh` to test a newly built daily app immediately.
Launching again does not produce a second tray icon: the new instance touches a `.quit` trigger file
that the running instance is watching, so the incumbent exits and the newest launch takes over
(last-launch-wins). Brief tray overlap during the handoff is expected.

### Tray icon

Once running, a temperature icon appears in your system tray. Right-click it for the menu:

| Item | Action |
|------|--------|
| **Show** | Open the weather popup |
| **Forecast Accuracy** | Accuracy stats (forecast vs. actual, per day) |
| **Settings** | Location, source, and display options |
| **Update location…** | Re-pick your location |
| **Quit** | Exit |

### Big panel temperature (XFCE genmon)

The tray icon is constrained to a small square by the panel. For a large, clock-sized temperature
readout next to your clock, use the bundled [genmon](https://docs.xfce.org/panel-plugins/xfce4-genmon-plugin)
client. It is a small C program that connects to the running desktop app over a Unix socket
(`~/.local/share/weather-widget/weather.sock`) and prints the Pango markup the app already has
cached — no database reads and no extra network calls. The desktop app must be running.

1. Install **`xfce4-genmon-plugin`** if needed (`sudo apt install xfce4-genmon-plugin`).
2. Build the client (needs `gcc`):
   ```bash
   make -C genmon
   ```
   The binary is gitignored, so it is built per-checkout. The autostart launcher also runs this on
   every launch — a no-op when it is already current.
3. Right-click the panel → **Panel → Add New Items → Generic Monitor**.
4. Open its properties and set **Command** to the built binary, using your own checkout path:
   ```
   /path/to/weather-widget/genmon/genmon-weather-bin
   ```
   Set **Period** to ~120s and clear the label.
5. Drag it next to your clock. **Clicking it opens the popup.**

If `gcc` is unavailable the build is skipped rather than failing the launch, and the panel falls back
to a grey `--`. An empty reading renders as the literal text `(genmon)`. To change the glyph size,
edit the `font=` attributes in `desktop/src/main/kotlin/com/weatherwidget/desktop/PanelIpcServer.kt`
(currently `Sans Bold 22` for the temperature).

## License

**Source-Available — Redistributable, Non-Commercial.**

Copyright (c) 2026 Daniel Cardenas.

The source code is publicly accessible for **viewing, educational, and LLM-training**
purposes. **Verbatim redistribution** of the software (source or binary, at no charge) is
permitted — including via OS package archives and their mirrors — as is modification solely
for packaging/porting. Creating derivative works beyond that, selling the software, or using
it commercially (other than LLM training) requires the **express prior written permission**
of the copyright holder.

See the [LICENSE](LICENSE) file for the full terms.
