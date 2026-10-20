# Implementation Plan: Precipitation "Sim loop" Interactive WeatherNext 3 Forecast Viewer

**Date:** 2026-10-20  
**Feature:** "Sim loop" button in precipitation graph space + interactive auto-playing AI rain forecast loop viewer  
**Platforms:** Linux Desktop (`:desktop`) & Android (`:app`) with core logic in `:shared`  

---

## 1. Overview & User Goals
- **Context:** When inspecting the Rain Chance / Precipitation Graph, users want to understand when rain will start, how intense it will be, and where storms are moving.
- **Entry Point:** A stylish, compact **"Sim loop"** button placed in the unused space of the Precipitation Graph (both on the Android home screen widget and in the Desktop popup).
- **Viewer Experience:** Tapping/clicking "Sim loop" opens a dedicated viewer that **automatically plays** the 1-Hour Rain Rate (`qpf1_imerg`) loop (or 6-hour rain and storm fronts), centered on the user's location with local timestamps, smooth frame interpolation, and speed/scrubbing controls.

---

## 2. Core Architecture & Components

```
                              ┌──────────────────────────────────────────────┐
                              │            :shared Module                    │
                              │  - WeatherNextForecastClient (Ktor)          │
                              │  - Domain Selector (ca / pnw / wide)         │
                              │  - Coordinate Projector (lat, lon -> px, py) │
                              │  - Cache Manager (disk-backed JPEGs)         │
                              └──────────────────────┬───────────────────────┘
                                                     │
                   ┌─────────────────────────────────┴─────────────────────────────────┐
                   ▼                                                                   ▼
┌──────────────────────────────────────┐                            ┌──────────────────────────────────────┐
│        Linux Desktop (:desktop)      │                            │          Android App (:app)          │
│ - "Sim loop" button on               │                            │ - "Sim loop" touch target on         │
│   PrecipitationGraph.kt              │                            │   Precipitation widget layout        │
│ - RainForecastWindow.kt              │                            │ - RainForecastActivity.kt            │
│   * Auto-plays on launch             │                            │   * Auto-plays on launch             │
│   * Compose Canvas with sub-frame    │                            │   * Touch pinch/pan + scrub controls │
│     cross-fade interpolation         │                            │   * Localized timestamps & scale     │
│   * Mouse wheel zoom + timeline scrub│                            │                                      │
└──────────────────────────────────────┘                            └──────────────────────────────────────┘
```

---

## 3. Detailed Component Plan

### A. `:shared` Module
1. **Client & Domain Resolver (`shared/src/main/kotlin/com/weatherwidget/shared/weathernext/`)**:
   - `WeatherNextForecastClient`:
     - Fetches `https://ajoros.github.io/ai-weather-guy/{domain}/manifest.json` using Ktor.
     - Resolves user `(lat, lon)` to domain:
       - `ca`: Lat 27.5°–44.5°N, Lon 216°–249°E (California & West Coast)
       - `pnw`: Lat 40.5°–54.5°N, Lon 216°–249°E (Pacific Northwest)
       - `wide`: Lat 10°–75°N, Lon 120°–300°E (North America & Pacific)
     - Maps lat/lon to exact pixel coordinate on the projection grid:
       $$\text{px} = \text{ax\_left} + \frac{\text{lon}_{360} - \text{lon}_0}{\text{lon}_1 - \text{lon}_0} \cdot (\text{ax\_right} - \text{ax\_left})$$
       $$\text{py} = \text{fig\_h} - \left(\text{ax\_bottom} + \frac{\text{lat} - \text{lat}_0}{\text{lat}_1 - \text{lat}_0} \cdot (\text{ax\_top} - \text{ax\_bottom})\right)$$
   - `WeatherNextLayer`:
     - `qpf1_imerg` (1-Hour Rain Rate & Showers, default)
     - `pcp6` (6-Hour Rain, Pressure Isobars & Fronts)
     - `qpf_acc` (Total Cumulative Rain Accumulation)
     - `ivt` (Water Vapor Transport / Atmospheric Rivers)
2. **Disk Cache**:
   - Stores downloaded frames under `{cacheDir}/weathernext/{domain}_{layer}_{lead}.jpg`.
   - Checks file existence and timestamps to minimize network transfer.

---

### B. Linux Desktop (`:desktop`)
1. **Precipitation Graph Button (`PrecipitationGraph.kt`)**:
   - Render a compact, semi-transparent pill button labeled **"Sim loop"** with a subtle rain icon (`🌧️ Sim loop`) in the upper-right clear region of the graph.
   - Respects existing graph bounds and labels so it never overlaps text.
   - Click action: triggers `onOpenSimLoop()`.
2. **Dedicated Window (`RainForecastWindow.kt`)**:
   - Single-instance Compose Desktop window (matches desktop window management conventions).
   - **Auto-Play:** Begins playing the 1-hour rain loop automatically on open at 1.0x snappy speed (~140ms / ~7 FPS).
   - **Interactive Features:**
     - Top title bar with chart switcher (1-Hour Rain Rate default, 6-Hour Rain & Fronts, Total Rain, Water Vapor).
     - Live localized timestamp: e.g. `Forecast Valid: Tue Oct 20, 10:00 AM PDT (Lead: F+006h)`.
     - Visual color scale / legend bar.
     - Play/Pause toggle, step backward/forward buttons.
     - Timeline scrub slider with smooth sub-frame cross-fading.
     - Speed selector (`0.5x`, `1.0x Snappy`, `1.5x`, `2.5x`).
     - Zoom buttons (`1x Full Domain`, `4x`, `8x City Zoom`) + mouse scroll wheel zooming.
     - Accurate location pin (`📍 Mountain View`).

---

### C. Android App & Widget (`:app`)
1. **Widget Touch Target (`PrecipViewHandler.kt` & Widget Layouts)**:
   - On the precipitation graph view:
     - Render a crisp **"Sim loop"** pill badge in the upper unused corner of the precipitation graph bitmap.
     - Set a `PendingIntent` on the touch zone targeting `RainForecastActivity`.
2. **Dedicated Activity (`RainForecastActivity.kt`)**:
   - Registered in `AndroidManifest.xml`.
   - Dark modern atmospheric UI matching the app's design language.
   - **Auto-Play:** Starts playing the 1-hour loop immediately upon opening.
   - **Interactive Controls:**
     - Smooth pan and pinch-to-zoom centered on user's location pin.
     - Bottom playback bar: Play/Pause, continuous scrub slider, speed chips, and local time display.
     - Top selector dropdown/chips for switching forecast layers.

---

## 4. Verification & Testing Protocol

1. **Unit & Category Tests**:
   - Add unit tests in `:shared` verifying:
     - Domain selection boundary conditions (`ca`, `pnw`, `wide`).
     - Coordinate projection mathematics (exact pixel positioning for Mountain View and edge coords).
     - Local time formatting from ISO UTC strings.
   - Ensure duration category annotations (`@Category(ShortDuration::class)`) are applied across all test classes.
2. **Desktop Verification**:
   - Build and run desktop distributable via `./scripts/buildStart.sh`.
   - Switch to Precipitation view in `WeatherWindow`.
   - Click **"Sim loop"** button in the open space.
   - Verify `RainForecastWindow` opens and **immediately begins auto-playing** the 1-hour rain loop.
   - Verify zooming, scrub slider, layer switching, and local time formatting.
3. **Android Verification**:
   - Assemble and run emulator tests: `./scripts/emulator-tests.sh`.
   - Deploy to device/emulator via `./gradlew installDebug`.
   - Switch widget to Precipitation view, verify "Sim loop" pill is cleanly visible in open space, and tap to verify `RainForecastActivity` launches and auto-plays.
