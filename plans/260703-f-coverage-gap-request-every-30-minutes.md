# Always request max forecast horizon; delete coverage-chasing triggers

## Context

A user-noticed widget redraw on the Samsung SM-F936U1 was traced to a **futile 30-minute loop**: widget 352's visible edge (today+8) exceeds NWS's ~7-day forecast horizon, so the render-time coverage check in `DailyViewHandler` force-fetches every 30 min (debounce interval), NWS returns the same 7 days, the gap persists, and all widgets repaint — forever. Root cause: `ForecastHorizon.extensionTarget()` has no concept of per-source horizon limits; the nav-time trigger knew this (hardcoded Open-Meteo gate) but the render-time trigger, written 30 minutes later, lost it.

**Agreed design** (discussed over several turns; user rejected per-source capability constants — "those things change over time" — and rejected the Open-Meteo-gated trigger as provider special-casing): routine fetches always request `MAX_DAYS` (16). Only Open-Meteo's adapter honors the number (`forecast_days` URL param); every other source ignores it and returns what it returns. Stored coverage is then always the deepest each source can provide, any remaining gap is by definition unfillable (climate-filler is the *correct rendering*, not a defect), and **both coverage-chasing triggers get deleted**, not patched. `MAX_DAYS` survives only as request formation (Open-Meteo rejects 17) — drift degrades gracefully (under-ask), never misbehaves.

Full investigation written up in `notes/260703-forecast-coverage-check-deep-dive.md`.

**Scope decision — full removal of the `forecastDays` threading** (not just a default flip): verification showed the desktop no-hourly day-tap flow calls the settled-for-deletion `ensureForecastDays` (Main.kt:305), so that flow must be edited either way; and the Android no-hourly flow's explicit `daysToCover` value (clamped 8..16) could only *under-ask* relative to the new default of 16 — a dead parameter. `ForecastHorizon` shrinks to a single documented constant.

**Verified consumer inventory** (Explore + Plan agents, repo-wide): every symbol below has no consumer outside this deletion set. `daysToCover`'s only callers are the two no-hourly call sites being simplified; `ForecastHorizonContract` is referenced only by `ForecastHorizonTest`; `enqueueForcedRefresh`'s only explicit-`forecastDays` callers are the two deleted triggers; `OpenMeteoApi.getForecast`'s `days` param must survive (desktop `fetchHistory` passes `days = 1`); `OpenMeteoApi.kt:179`'s `forecast_days=1` is the unrelated current-conditions path — untouched.

## Changes

Single commit; build only after all production edits (shared deletions break downstream compilation until app/desktop catch up).

### Phase 1 — shared
1. `shared/src/main/kotlin/com/weatherwidget/shared/config/ForecastHorizon.kt` — delete `BASELINE_DAYS`, `daysToCover`, `extensionTarget`. Rewrite kdoc: `MAX_DAYS` is a request-formation constant (Open-Meteo's `forecast_days` ceiling; 17 rejected); every fetch requests it; sources return what they can; days past real coverage show climate filler by design; no per-source capability limits encoded anywhere. Keep the historical "7-day window dropped next Saturday" note (always-16 subsumes it).
2. Delete `shared/src/main/kotlin/com/weatherwidget/shared/config/ForecastHorizonContract.kt` entirely.
3. `shared/src/main/kotlin/com/weatherwidget/data/remote/OpenMeteoApi.kt:37` — `days: Int = ForecastHorizon.MAX_DAYS` (param stays; line 179 untouched).

### Phase 2 — app
4. `DailyViewHandler.kt` — delete the coverage-staleness block (L290-309: comment, `realCoverageMax`, `visibleRightmost` destructure, `extensionTarget`→`enqueueForecastCoverageRefresh`). Locals verified block-only. Prune `ForecastHorizon` import; prune `RefreshScheduler` import if this was its last use.
5. `WidgetIntentRouter.kt` — delete the `if (!isLeft)` nav-extend block + comment (L233-260; `DAILY_NAV_EXTEND_FORECAST`, `reason=nav_extend_forecast` live only here). Prune `ForecastHorizon` import; verify other imports still used before pruning.
6. `RefreshScheduler.kt` — delete `enqueueForecastCoverageRefresh` (L122-155), `COVERAGE_REFRESH_DEBOUNCE_MS` (L21-24), the `forecastDays` param on `enqueueForcedRefresh` (L91) and its `putInt(KEY_FORECAST_DAYS, …)` (L105). `refreshIfStale`'s separate `last_enqueue_*` staleness keys in the same `widget_refresh` prefs stay intact.
7. `WeatherWidgetWorker.kt` — delete the L66 `KEY_FORECAST_DAYS` read, the `forecastDays =` argument at L177, the const at L808, and the `ForecastHorizon` import.
8. `WeatherWidgetProvider.kt` — in `handleDayClickAction`, delete the `putInt(KEY_FORECAST_DAYS, …)` block (L649-652) and the now-unused `targetDate` val (L637-642). Banner/`KEY_TARGET_SOURCE`/`KEY_NO_HOURLY_*`/goAsync handling untouched.
9. `NoHourlyDayClickCoordinator.kt` — delete `forecastDaysFor` (L31-32) + `ForecastHorizon` import.
10. `WeatherRepository.kt:43,46` and `ForecastRepository.kt:148,194,301,336-341` — remove `forecastDays` params/forwarding; Open-Meteo call becomes `openMeteoApi.getForecast(latitude, longitude, historyDays = …)` relying on the new default. Prune imports.
11. Stale comment: `WidgetSizeCalculator.kt:46-51` — rewrite: routine fetches request `MAX_DAYS`; Open-Meteo reaches today+15, NWS ~today+6; columns past real coverage show filler by design; no on-demand extension exists.

### Phase 3 — desktop
12. `DesktopWeatherRepository.kt` — delete `widestForecastDaysFetched` (L186-189), `forecastFetchMutex` (L190), `ensureForecastDays` (L192-220); `refresh()` drops its `forecastDays` param (L224) and calls `weatherService.fetchForecast()` (L231). Prune `ForecastHorizon`/`Mutex`/`withLock` imports. Routine callers (`Main.kt:614`, `DaemonProcess.kt:152/364/413`) already use the default — no edits there.
13. `DesktopWeatherService.kt` — remove `forecastDays` param from `fetchForecast` (L98) and `fetchOpenMeteoForecastWithActuals` (L164-166; pass `days = ForecastHorizon.MAX_DAYS` explicitly to keep the constant's role visible). Rewrite stale doc L91-97. `fetchHistory` unchanged.
14. `Main.kt` —
    - Delete `forecastExtendInFlight` + `onNeedForecastExtension` lambda (L270-293), its `WidgetPopup` argument (L710) and param (L749), and the `rightmostVisibleDate` + `LaunchedEffect` extension call site (L1000-1014).
    - Simplify `onNeedHourlyRefresh` (L295-319): new type `((List<HourlyForecast>) -> Unit) -> Unit`; completes immediately with cached `forecast?.hourly ?: emptyList()`. At L1090-1091 drop the `targetDays = daysToCover(…)` computation. Two-phase banner UI contract preserved.
    - Prune `ForecastHorizon` import.

    *Behavioral delta (accepted consequence of the design):* today a desktop no-hourly tap beyond the session-widest horizon triggers one widening refetch; after this change there is no tap-triggered fetch on desktop — data is already fetched at max depth every cycle. (Android's no-hourly tap keeps its forced refresh; only the days number is dropped.)

### Phase 4 — tests
15. Delete `shared/src/test/.../ForecastHorizonTest.kt` and `desktop/src/test/.../DesktopForecastExtensionIntegrationTest.kt` (both exclusively pin deleted functions).
16. `OpenMeteoApiTest.kt:74-97` — collapse the two horizon tests into one: default request sends `forecast_days=16` (assert the literal string "16" so an accidental constant change trips it).
17. `OpenMeteoIntegrationTest.kt:131-161` — rewrite as "getWeatherData requests the maximum horizon" (one call, assert `forecast_days=16`); the L155 leg passes the deleted param and must go.
18. `WeatherWidgetProviderNoHourlyRoboTest.kt:114` — drop the `KEY_FORECAST_DAYS` assertion (rest of the test still pins the no-hourly force-refresh contract).
19. `NoHourlyDayClickCoordinatorTest.kt:72-77` — delete the `forecastDaysFor` test; prune imports.
20. `DesktopNoHourlyDayClickTest.kt` — mechanical lambda-signature updates (L78 default, L104/121/140 captures); test semantics unchanged. `DesktopBackfillIntegrationTest` needs no edit (defaults on both sides).

Housekeeping: stale `last_enqueue_coverage_<sourceId>` keys in `widget_refresh` prefs are never read again — leave to rot; note in commit message.

## Verification

1. **Unit**: `./gradlew :shared:test :app:testDebugUnitTest :desktop:test` (desktop tests are safe with the app running). Targeted: `--tests "*OpenMeteo*" --tests "*NoHourly*"`.
2. **Instrumented** (emulator only — never `connectedDebugAndroidTest`): `./scripts/emulator-tests.sh -c com.weatherwidget.widget.handlers.DailyFutureDayNoHourlyClickIntegrationTest`.
3. **Live on Samsung (RFCT71FR9NT)** — the bug's signature was `DailyViewHandler: coverage gap: widget=352` + `COVERAGE_REFRESH_ENQUEUE` recurring every ~30 min + `SYNC_START reason=coverage_gap`:
   - `./gradlew installDebug`, poke widget 352's daily view (render + navigate to rightmost edge to exercise both old trigger paths).
   - `adb -s RFCT71FR9NT logcat -v time | grep -E "coverage gap|COVERAGE_REFRESH_ENQUEUE|nav_extend_forecast|reason=coverage_gap"` → expect zero hits; observe ≥40 min to outlast the old 30-min cadence.
   - Open-Meteo widget: navigate daily view right to today+15 — real bars already present, no fetch triggered. NWS widget 352: edge days show climate filler quietly (terminal state, not a refetch trigger).
4. **Desktop**: `scripts/buildStart-desktop.sh`; pan daily view to today+15 (Open-Meteo real data immediately); tap a no-hourly day → pending→result banner resolves with no daemon fetch.
5. Run `/verify` before committing (product-source change with a drivable runtime surface).

## After implementation
- Update memory `nws_unfillable_coverage_gap_loop.md` (mark implemented) and the notes file's "agreed direction" section (mark done).
- Deferred, separate task if redraws still annoy: skip `updateAppWidget()` when displayed content is unchanged (fingerprint rendered bitmap + final rounded text values).
