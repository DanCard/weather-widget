# Precipitation Simulation Loop Viewer (WeatherNext Integration)

**Date**: 2026-10-20  
**Associated Plan**: `plans/261020-precipitation-sim-loop-viewer.md`  
**Platforms**: Linux Desktop (`:desktop`), Android Widget (`:app`), Shared Logic (`:shared`)

---

## 1. Overview & Objectives

Incorporate the Google WeatherNext 3 high-resolution forecast loop data (from `https://github.com/ajoros/ai-weather-guy`) directly into the Weather Widget applications:
1. **Interactive Entry Point**: Add a `"Sim loop"` button in the unused space on the precipitation / rain chance graph.
2. **Dedicated Viewer**: Clicking / tapping the button opens a dedicated simulation player window (Desktop) or activity (Android) that automatically begins playback upon opening.
3. **Layer Configuration**: Defaults to the **1-Hour Rain Rate (`qpf1_imerg`)** model, with toggles for 6-Hour Fronts (`pcp6`), Total Rain (`qpf_acc`), and Water Vapor (`ivt`).
4. **Playback & Controls**: Snappy default playback cadence (~140 ms / ~7 FPS), smooth cross-fade frame interpolation, scrubbing slider, playback speed control (0.5× / 1× / 2×), stepping buttons, local time timestamps, and pinned user location coordinates.

---

## 2. Implementation Summary

### A. Shared Module (`:shared`)
* **Package**: `com.weatherwidget.data.remote.weathernext`
  * `WeatherNextDomain`: Domain resolution (`CA`, `PNW`, `WIDE`) based on geographic bounding box coordinates.
  * `WeatherNextLayer`: Model layer configurations (`QPF1_IMERG`, `PCP6`, `QPF_ACC`, `IVT`), default speeds, and color swatch legends.
  * `WeatherNextCoordinateProjector`: Plate Carrée forward projection from `(lat, lon)` to image pixels and crop bounding boxes.
  * `WeatherNextForecastClient`: Manifest fetcher, frame URL resolution, and UTC-to-local time formatting.
  * `WeatherNextTest`: JVM unit tests validating domain resolution, coordinate projection, and time formatting (`@Category(ShortDuration::class)`).

### B. Linux Desktop (`:desktop`)
* **Entry Point**: In `desktop/src/main/kotlin/com/weatherwidget/desktop/PrecipitationGraph.kt`, added a `"🌧️ Sim loop"` pill button in the top-right empty graph area that invokes `onOpenSimLoop`.
* **Routing**: Hooked `onOpenSimLoop` through `DesktopWidgetPopup.kt`, `DesktopWindowHosts.kt`, and `DesktopUiApplication.kt`.
* **Viewer Window**: Created `desktop/src/main/kotlin/com/weatherwidget/desktop/RainForecastWindow.kt`:
  * Autoplays immediately on launch with sub-frame alpha cross-fading on a Compose Canvas.
  * Header displays location pin and domain title.
  * Top chip bar allows toggling between layers with layer descriptions.
  * Bottom controls include timeline slider, play/pause, prev/next step buttons, speed cycle toggle, and location-centered 2× zoom crop toggle.
  * Floating HUD displays forecast valid time in local time, lead hours, and color swatch legend.

### C. Android App & Widget (`:app`)
* **Widget Graph & Overlay**:
  * Added `sim_loop_touch_zone` in `app/src/main/res/layout/widget_weather.xml`.
  * `PrecipitationGraphRenderer.kt`: Draws `"🌧 Sim loop"` pill button on the graph bitmap in the upper-right area.
  * `PrecipViewHandler.kt`: Enables `sim_loop_touch_zone`, assigns `PendingIntent` launching `RainForecastActivity` with `extra_lat` and `extra_lon` location extras.
  * `DailyVisibilityManager.kt`, `TemperatureViewBinder.kt`, and `CloudCoverViewHandler.kt`: Hide `sim_loop_touch_zone` outside precipitation graph mode.
* **Viewer Activity**:
  * Created `app/src/main/res/layout/activity_rain_forecast.xml` and `app/src/main/java/com/weatherwidget/ui/RainForecastActivity.kt`.
  * Native Android layout with custom double-buffered Canvas rendering for smooth frame cross-fading, timeline scrubber, layer switcher chips, play/pause, step controls, and speed selector.
  * Registered `RainForecastActivity` in `app/src/main/AndroidManifest.xml`.

---

## 3. Verification & Testing

1. **Static Analysis & Localization**:
   * Resolved package placement into `com.weatherwidget.data.remote.weathernext` (consistent with data/remote APIs).
   * Verified `HardcodedUserFacingStringTest`: `testLocalizationDebugUnitTest` passed cleanly.
2. **Robolectric & Unit Tests**:
   * `PrecipTouchRoutingRoboTest`: Verified sim loop touch zone visibility in graph mode and hidden in text mode, and validated intent launching `RainForecastActivity` with location extras.
   * `RainForecastActivityRobolectricTest`: Verified activity initialization, domain detection, and layer switcher behavior.
   * `./scripts/unit-tests.sh`: **All 5,587 unit tests passed** across `:shared`, `:desktop`, and `:app`.
3. **Build & Desktop Distribution**:
   * Built and packaged via `./gradlew :desktop:createDistributable`.
   * Executed `scripts/buildStart-desktop.sh`; verified clean daemon startup and UI launch.
