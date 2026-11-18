# Desktop API toggle latency investigation and cross-platform speedups

**Date:** 2026-11-18
**Plan:** [performance/261118-desktop-api-toggle-latency.md](../performance/261118-desktop-api-toggle-latency.md)

## User prompts

1. "Desktop: Performance when I click on an API is not great. Can you measure performance? Ideas on improving performance when toggeling API?"
2. "The benchmark is not correct. I see a second usually and more. Does the end to end benchmark for API toggle know when rendering has finished? Does it start on click?"
3. "yes" (write plan in `performance/`)
4. "How much of the plan is in shared code versus desktop code?"
5. "Can we make the improvements shared between desktop and android?"
6. "yes" (update plan with `:shared` cross-platform improvements)
7. "start"
8. "still feels slow changing api"
9. "yes feels better, thanks. Write a session summary to summaries/ dir and commit when done"

## Root causes identified

1. **Incomplete E2E measurement:** `SOURCE_SWITCH_E2E phase=data_ready` started on click (`DesktopSourceSwitch.switchTo`) but stopped right after `forecast = cached` (`DesktopSourceSwitch.onDataShown`) — excluding Compose recomposition, UI-thread blend/overlay compute, layout, and Canvas draw.
2. **4× `loadCached()` storm per click:** `DaemonRuntime.runLaunchRefresh` called `notifyDataUpdated()` even on cache-only `source_change` (`action=NONE`), firing both `UiNotifyClient` (`socket:push`) and `DataUpdateWatcher` (`.data-updated`) in the UI alongside `LaunchedEffect(repository)`.
3. **Redundant & oversized SQLite reads in `DesktopWeatherRepository.loadCached()`:** read 30 days of `observations` (13,728 rows) twice per load (`getCloudActuals` + `getObservationsInRange`) and scanned 40 days of all-source `hourly_forecasts` (`getHourlySettledAllSources`) just for the header rain-% reserve.
4. **Un-sliced blend work in `:shared` (`ActualTemperatureSeriesBuilder.blendObservationSeries`):** normalized, sorted, and blended all 30 days of observations before discarding timestamps outside `startMs..endMs`, and scanned `stationObs.minByOrNull { it.distanceKm }` inside the per-timestamp loop.
5. **500 ms `DailyForecastGraph` frame stall in `:shared` (`NightRainLabelPlacer.place`) + 90 KB per-frame log spam:** discovered immediately via the new `phase=rendered` logs (`renderMs = 467–559 ms`). `NightRainLabelPlacer.place()` allocated `Box` and `Spot` objects inside a ~10,000-spot × ~280-band × ~35-band loop on every frame, while `DailyRainLabelLayout` formatted all ~2,000 obstacle rects into a 90 KB `Log.v` string per frame.

## Changes

### `:shared` (benefits both Android and Desktop)
1. [SourceSwitchRunner.kt](../shared/src/main/kotlin/com/weatherwidget/shared/sourceview/SourceSwitchRunner.kt): added `renderedLine(...)` for `SOURCE_SWITCH_E2E phase=rendered sinceSwitchMs= renderMs=`.
2. [HeaderContent.kt](../shared/src/main/kotlin/com/weatherwidget/shared/util/HeaderContent.kt): added shared `precipReserveWindowMs(...)` (`-1h .. +7h` around `now`/`centerTime`).
3. [ActualTemperatureSeriesBuilder.kt](../shared/src/main/kotlin/com/weatherwidget/shared/actuals/ActualTemperatureSeriesBuilder.kt): sliced inputs in `blendObservationSeries` to full local days overlapping `startMs..endMs` plus the ±3 h reach, filtered `candidateTimes` to `startMs..endMs` before blending, and hoisted `anchorByStation` outside the loop.
4. [NightRainLabelPlacer.kt](../shared/src/main/kotlin/com/weatherwidget/shared/graph/NightRainLabelPlacer.kt): pre-filtered obstacles to the search bounding box + `comfortPx`, narrowed horizontally per column, switched the inner grid search to primitive `FloatArray`s with zero `Box`/`Spot` allocations, and computed full `Spot` objects only for the winning `best` and `runnerUp`.
5. [DailyRainLabelLayout.kt](../shared/src/main/kotlin/com/weatherwidget/shared/graph/DailyRainLabelLayout.kt): logged `obstacles=${obstacles.size}` instead of formatting all obstacle rects into a 90 KB string per frame.

### `:app` (Android)
1. [HeaderWidthChecker.kt](../app/src/main/java/com/weatherwidget/widget/handlers/HeaderWidthChecker.kt): wired to `HeaderContent.precipReserveWindowMs(...)`.

### `:desktop`
1. [DesktopSourceSwitch.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopSourceSwitch.kt) & [DesktopWidgetPopup.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWidgetPopup.kt): added `onFrameRendered(sourceId)` fired from `Modifier.drawWithContent` after `drawContent()` when `snapshotIsSelectedSource`.
2. [DesktopProcess.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopProcess.kt) & [DaemonRuntime.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DaemonRuntime.kt): added `launchCacheNotifiesUi(reason)` to skip redundant `notifyDataUpdated()` on cache-only `source_change` / `source_list_edit`.
3. [DesktopSnapshotCache.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopSnapshotCache.kt) & [DesktopUiApplication.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopUiApplication.kt): added in-memory per-source `ForecastSnapshot` cache (invalidated on daemon updates, site/settings changes, or 5 min TTL), `DesktopReloadDeduplicator` to suppress `.data-updated` right after `socket:push`, and single-flight reload cancellation.
4. [DesktopWeatherRepository.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWeatherRepository.kt): loaded `observations` once and reused them in `MetarCloudBlender.fromSiteRows(...)`; scoped `allSourcesHourly` to `HeaderContent.precipReserveWindowMs(...)`.
5. [TemperatureGraph.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/TemperatureGraph.kt), [DesktopWidgetPopup.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopWidgetPopup.kt), & [DailyForecastGraph.kt](../desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt): `remember`ed `ActualTemperatureSeriesBuilder.build` and `DesktopDailyForecastModel.build` (excluding `config.settings.weatherSource` from `dailyState` keys), and cached `TextLayoutResult.inkBands`.

### Tests & living docs
- [HeaderContentTest.kt](../shared/src/test/kotlin/com/weatherwidget/shared/util/HeaderContentTest.kt): `precipReserveWindowMs` bounds test.
- [ActualTemperatureSeriesWindowSliceTest.kt](../shared/src/test/kotlin/com/weatherwidget/shared/actuals/ActualTemperatureSeriesWindowSliceTest.kt): bit-for-bit equivalence test for 30-day vs narrow-slice `blendObservationSeries` and `TodayColumnOverlayContentResolver.resolveLatest`.
- [DesktopTogglePerformanceTest.kt](../desktop/src/test/kotlin/com/weatherwidget/desktop/DesktopTogglePerformanceTest.kt): unit + real-SQLite integration tests for `phase=rendered`, `DesktopSnapshotCache`, `DesktopReloadDeduplicator`, `launchCacheNotifiesUi`, and single-read `loadCached()`.
- [arch/operations.md](../arch/operations.md): documented `phase=rendered`, `DesktopSnapshotCache`, and the deduplicated reload path.

## Measured results

### Live desktop `SOURCE_SWITCH_E2E` (`app_logs`)

| Transition | Before (`phase=rendered`) | After (`phase=rendered`) | Speedup |
|---|---:|---:|---:|
| `OPEN_WEATHER_MAP → GOOGLE_WEATHER` | 999 ms (`renderMs=559`) | **160 ms** (`data_ready=59`, `renderMs=101`) | **6.2×** |
| `GOOGLE_WEATHER → TOMORROW_IO` | 1,114 ms (`renderMs=467`) | **244 ms** (`data_ready=87`, `renderMs=157`) | **4.6×** |
| `OPEN_METEO → WEATHER_API` | 1,073 ms (`renderMs=495`) | **283 ms** (`renderMs=64`) | **3.8×** |
| `SILURIAN → OPEN_METEO` | 1,050 ms (`renderMs=541`) | **321 ms** (`renderMs=83`) | **3.3×** |
| `NWS → SILURIAN` | 1,034 ms (`renderMs=538`) | **330 ms** (`renderMs=90`) | **3.1×** |

### Microbenchmarks (24 MB live DB snapshot)
- `:shared` NWS `ActualSeries(24h)` cache miss: **512 ms → 11 ms** (46×); NWS `DailyOverlay`: **242 ms → 19 ms** (12.7×); Tomorrow.io `DailyOverlay`: **101 ms → 1 ms** (100×).
- Desktop cold `loadCached()`: **152–303 ms → 77–176 ms**; warm repeat toggle: **0 ms** DB read.
- Android Long unit test bucket: **201 s → 107 s** (nearly 2× faster from `:shared` `NightRainLabelPlacer` speedup).

## Verification

- `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`: **7,199 unit tests passed** (3,582 shared, 940 desktop, 2,677 Android), **128 emulator tests passed**, installed on 3 Android devices, desktop distributable built and restarted, and live toggles verified in `app_logs`.
