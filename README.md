# Weather Widget

An elegant and functional Android weather widget application.

## Overview

This project is a modern Android weather widget application. It provides real-time weather updates and forecasts right on your device's home screen.

It also includes a **Linux desktop companion** (Compose for Desktop) that shares the same weather layer and adds a system-tray temperature readout, a forecast-accuracy database, and an optional clock-sized panel readout via XFCE's Generic Monitor (genmon). See [Desktop App (Linux)](#desktop-app-linux).

## Tech Stack

The application is built using modern Android development practices and libraries:

*   **Kotlin:** The primary programming language.
*   **Coroutines & Flow:** For asynchronous programming and reactive data streams.
*   **Hilt:** For Dependency Injection.
*   **Room:** For local database and caching.
*   **Ktor:** For making network requests to the weather API.
*   **WorkManager:** For scheduling background sync tasks.

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

### Build & install

Requires **Java 21** and, to build the installer, a full JDK that includes `jpackage` (Android
Studio's bundled JBR does **not** — set `JPACKAGE_HOME` or `-Djpackage.home` to a full JDK, or install
one at `/usr/lib/jvm/java-21-openjdk-amd64`).

```bash
# Build the repo-local app distribution used by autostart
./gradlew :desktop:createDistributable

# Build, stop the running app, and start the new repo-local distributable
scripts/build-exe-and-restart.sh

# Development only: run without the distributable wrapper
./gradlew :desktop:run

# Optional installer build, if a system package is wanted
./gradlew :desktop:packageDeb
```

Daily autostart uses `scripts/desktop-app-launcher-and-autostart.sh`, which launches the repo-local
distributable at `desktop/build/compose/binaries/main/app/weather-widget-desktop/bin/weather-widget-desktop`.
If the distributable is missing, the script rebuilds it once with `:desktop:createDistributable`.
Use `scripts/build-exe-and-restart.sh` to test a newly built daily app immediately.
A single-instance lock prevents duplicate tray icons.

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
script (it reads the same database — no extra network calls):

1. Install **`xfce4-genmon-plugin`** if needed (`sudo apt install xfce4-genmon-plugin`).
2. Right-click the panel → **Panel → Add New Items → Generic Monitor**.
3. Open its properties and set **Command** to the repo script:
   ```
   python3 /home/dcar/projects/weather-widget/scripts/genmon-weather.py
   ```
   Set **Period** to ~120s and clear the label.
4. Drag it next to your clock. **Clicking it opens the popup.** Tune the `FONT` constant at the top of
   the script if you want bigger/smaller glyphs.

## License

**Proprietary — All Rights Reserved.**

Copyright (c) 2026 Daniel Cardenas. All rights reserved.

This software and its source code are proprietary. No permission is granted to use,
copy, modify, distribute, or create derivative works from any part of this project
without the **express prior written permission** of the copyright holder. All rights
not expressly granted are reserved.

See the [LICENSE.md](LICENSE.md) file for the full terms.
