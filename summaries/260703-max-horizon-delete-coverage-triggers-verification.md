# Verification: Always request max forecast horizon; delete coverage-chasing triggers

Full investigation in `notes/260703-forecast-coverage-check-deep-dive.md`
Plan in`plans/260703-f-coverage-gap-request-every-30-minutes.md`

**Verdict: PASS**

**Claim:** Every fetch requests `forecast_days=16`; the render-time coverage check (the
30-min forced-fetch loop on widget 352) and the nav-time extension trigger are deleted on
both platforms; the no-hourly day-tap flow keeps working without its forecast-days
parameter; unfillable edge days terminate at climate filler instead of refetching.

**Method:** Installed the debug APK on the Samsung SM-F936U1 (`RFCT71FR9NT`) and drove the
widget via `adb` broadcasts + logcat + pulled DB; rebuilt the desktop distributable via
`scripts/buildStart-desktop.sh` and forced a full refresh using the documented
aged-REFRESH-row procedure, observing `weather.db`.

## Steps

1. ✅ Installed new APK, cleared logcat, broadcast `ACTION_REFRESH` → widget 352 (the
   looping widget) painted its DAILY view repeatedly; **zero** `coverage gap` /
   `COVERAGE_REFRESH_ENQUEUE` / `nav_extend_forecast` lines (previously emitted on
   *every* render). Worker cycle completed: `SYNC_START reason=manual_refresh` →
   `Worker result SUCCESS`.
2. 🔍 **Probe — day-tap on the unfillable edge day itself** (broadcast `ACTION_DAY_CLICK
   date=2026-07-11 widget=352`, the NWS climate-filler day): pending banner *"Hourly
   temperature data missing for Sat Jul 11. A refresh will be triggered"* → forced
   refresh ran with `force=true`, `KEY_TARGET_SOURCE=NWS`, and no forecast-days input →
   honest result banner *"No new hourly temperature data was able to be retrieved for
   Sat Jul 11. Data ends Fri Jul 10 at 8 AM"*. The two-phase flow survives the threading
   removal intact, and the unfillable day terminates cleanly instead of looping.
3. ✅ Pulled the device DB: Open-Meteo coverage reaches **2026-07-18 (today+15, 14 future
   days)** — the full 16-day horizon; NWS sits at 2026-07-10 (its real ~7-day limit)
   rendering filler past it, quietly.
4. ✅ Desktop: restarted the rebuilt distributable, forced a full refresh → NWS fetch
   succeeded at 21:51:10 through the new no-parameter `fetchForecast()`, coverage
   today+7 as NWS provides, no `REFRESH_FAIL`/`SOURCE_ERROR` rows.
5. ✅ A 45-minute background re-check was armed to confirm logcat stays clean past the
   old 30-minute debounce cadence.

## Findings

- 🔍 The unfillable-day probe (step 2) doubles as confirmation that the *Android*
  no-hourly forced refresh still fires with correct scoping (`KEY_TARGET_SOURCE`) after
  losing `KEY_FORECAST_DAYS`.
- ⚠️ **Pre-existing, unrelated:** `TemperatureDeltaVisibilityRoboTest` ("delta badge
  hidden when NOW line not visible") fails identically on a clean `HEAD` worktree — it
  came in with commit `23ce822a` (the delta-visible-on-future-scroll change), not this
  refactor. Worth a separate look.
- Desktop Open-Meteo's 16-day request couldn't be observed live because the desktop only
  fetches the *displayed* source (currently NWS) — by design. It's pinned instead by the
  two unit tests asserting the literal `forecast_days=16` through both the raw API
  (`OpenMeteoApiTest`) and the full Android repository path (`OpenMeteoIntegrationTest`).
- Unit suite: 1403/1404 pass (the one failure is the pre-existing item above); the
  instrumented `DailyFutureDayNoHourlyClickIntegrationTest` passes on the emulator.

## What was removed

Both coverage-chasing triggers (DailyViewHandler render-time check +
`enqueueForecastCoverageRefresh`; WidgetIntentRouter nav-extend; desktop
`onNeedForecastExtension` + `ensureForecastDays`), the five-hop
`forecastDays`/`KEY_FORECAST_DAYS` threading (incl.
`NoHourlyDayClickCoordinator.forecastDaysFor`), and
`ForecastHorizon.{BASELINE_DAYS,daysToCover,extensionTarget}` +
`ForecastHorizonContract`. `ForecastHorizon` is now a single documented request-formation
constant (`MAX_DAYS = 16`; Open-Meteo rejects 17). Stale `last_enqueue_coverage_*` prefs
keys are never read again and were left to rot harmlessly.
