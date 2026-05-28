# Session Log — NWS vs Silurian rain day/night discrepancy

**Date:** 2026-05-28
**Branch:** main
**Area:** Daily forecast view — rain actuals (day vs night), `ObservationResolver.resolveDailyPrecip`

---

## Problem reported

In the daily forecast view, rain actuals for **yesterday (2026-05-27)** disagreed between sources:
- **Silurian** said it rained **during the day**.
- **NWS** said it rained **at night**, not during the day.

User asked: can we spot the issue using hourly rain actuals? (Investigate on emulator.)

---

## Investigation (data-driven, on emulator-5554)

Pulled the device DB and queried hourly precip by local time for 2026-05-27.

**Silurian** (`SILURIAN_MAIN` measured/history backfill):
| Local time | precip |
|-----------|--------|
| 12:00 | 0.0138 mm |
| 13:00 | 0.0138 mm |
→ both in the **day** window (08:00–20:00); total ≈ 0.028 mm.

**NWS** — *zero* measured precip that day. All 143 KSJC / KNUQ / KPAO observation rows had `precipAmountMm = null`. So `resolveDailyPrecip()` fell back to the NWS **hourly forecast**:
| Local time | precip |
|-----------|--------|
| 23:00 | 0.127 mm |
→ in the **night** window (≥20:00).

NWS-measured-precip distribution across days confirmed the pattern: 5/22–5/27 were **100% null**; only 5/28 (today, actual rain) had measured precip (KNUQ 4.6mm + KSJC 2.7mm). So the measured pipeline works — there genuinely was no measurable NWS rain on 5/27.

### Root cause
`ObservationResolver.resolveDailyPrecip()` is **measured-preferred with hourly-forecast fallback**. When a source has no measured precip for a day, it substitutes that source's hourly **forecast** and presents it as a measured actual. For 5/27:
- NWS → no measurement → showed its *forecast* (0.127mm @ 23:00 → night).
- Silurian → showed its measured `_MAIN` backfill (0.028mm @ noon → day).

They're different data products at different times. Not a timezone bug (timestamps were correctly local).

---

## Decision

User confirmed intent: **history should show actual rainfall.** Chosen fix direction: **measured-only for past days** (suppress forecast fallback for completed days; keep it for the incomplete current day).

---

## Fix

`app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`
- Added `allowForecastFallback: Boolean = true` to `resolveDailyPrecip`. When false (and no measured precip), returns `DailyPrecip(null, null, null)`.
- Date-iterating callers pass `allowForecastFallback = !date.isBefore(today)`:
  - `computeDailyExtremes` (persists `daily_extremes`)
  - `aggregateObservationsToDailyBySource` (used by `WidgetIntentRouter` for today)
- Live-today caller (`ObservationRepository` ~L390) keeps default `true`.

### Tests
`ObservationResolverTest.kt`
- Repointed the three forecast-fallback tests from fixed `2026-05-25` → `LocalDate.now()` (fallback is now today-only) and renamed them `... today ...`.
- Added `computeDailyExtremes past day with null observation precip is measured-only` → asserts all precip null.
- Added `computeDailyExtremes past day still uses measured observation precip` → measurements still honored.

`ObservationRepositoryDailyMergeTest.kt`
- Rewrote `recompute persists precip-only change for a past day` to drive the precip-only delta via **measured** observation precip (run 1 null → run 2 measured) instead of past-day forecast fallback (no longer applies). Removed now-orphaned `HourlyForecastEntity` import.

**Result:** `./gradlew testDebugUnitTest` for the affected classes — BUILD SUCCESSFUL, all tests pass (incl. the existing today-fallback tests).

---

## End-to-end verification on devices

Past-day labels read from the persisted `daily_extremes` table (`precipDayMm`/`precipNightMm`), **not** a live computation. Stored rows self-heal only when the recompute runs: `WeatherWidgetWorker.fetchDailyActuals(recompute=true)`, fired by a full/forced background fetch (refresh handler triggers it only when data is stale >30 min). The merge overwrites past rows via `precipChanged()` (`null != 0.127f` → overwrite).

To force the recompute during testing, a **temporary** edit made `ACTION_REFRESH` honor a `force_refresh` extra → `triggerImmediateUpdate(forceRefresh=true)`.

- **emulator-5554**: forced refresh → log `DAILY_EXTREME_OVERWRITE: date=2026-05-27 src=NWS precip=0.12699999->null`; DB confirmed NWS 5/27 day/night/total all null. ✅
- **Pixel 7 Pro**: recompute ran → NWS 5/27 cleared to null. ✅
- **emulator-5556 / Samsung**: initially still stale because the **clean reverted build** (no `force_refresh` shortcut) sent UI-only refreshes (data fresh). After re-adding the shortcut, rebuilding, and forcing, **user confirmed the night-rain label is gone** on all devices. ✅

---

## Cleanup / state

- **Temp `force_refresh` hack reverted** from `WeatherWidgetProvider.kt`. Working tree contains only the real fix + tests (`git diff --stat`: ObservationResolver.kt, ObservationResolverTest.kt, ObservationRepositoryDailyMergeTest.kt).
- ⚠️ **Devices still run the temp-hack APK.** Next `./gradlew installDebug` ships the clean build. Harmless until then (only enables the `force_refresh` broadcast shortcut).

---

## Key lesson

Past-day rain is cached in `daily_extremes`. A code fix changes how rows are *written*; already-stored rows only update when a full/forced fetch runs the recompute — UI-only refreshes and widget taps never recompute past days. This is why devices lagged after install. (Saved to memory: `nws_past_rain_measured_only.md`.)
