# Diagnostic: Rain Actuals History (emulator-5554, 2026-05-28)

## Context

User observed on emulator-5554 today:
- NWS shows rain *yesterday night* (2026-05-27)
- Silurian shows rain *yesterday day*
- Open-Meteo / Tomorrow.io / OpenWeatherMap / VisualCrossing show no yesterday rain
- It has been raining continuously for ~5 hours (today)

User's question: **"What can we ascertain about whether history rain actuals is working?"**

This is a diagnostic plan, not an implementation. It pins down what the live device state tells us and surfaces one real bug (today's precip drop) that the investigation uncovered — separate from the original "is history working" question.

## Verdict

**Past-day rain actuals history is working correctly.** The widget is faithfully rendering what's in `daily_extremes`, and the per-source values it shows match the DB:

| Source     | 2026-05-27 DB row                              | What user sees on widget       | Match? |
|------------|------------------------------------------------|--------------------------------|--------|
| NWS        | precip=0.127, day=NULL, night=0.127            | "rained yesterday night"       | ✅     |
| Silurian   | precip=0.0276, day=0.0276, night=NULL          | "rained yesterday during day"  | ✅     |
| Open-Meteo | precip=0.0, day=0.0, night=0.0                 | no yesterday rain shown        | ✅     |
| Tomorrow.io | precip=0.0, day=0.0, night=0.0                | no yesterday rain shown        | ✅     |

The API disagreement is **real per-source data**, not a display bug. Per-source isolation, day/night split, the new NWS hybrid forecast fallback (NWS night value came from `hourly_forecasts`, not observations), and Silurian's `_MAIN` pseudo-actual path all work end-to-end.

**Separate bug found while diagnosing:** today's daily-view `DailyActual` is constructed without precip fields, so the ~5 hours of currently-falling rain doesn't reach the today rain label even though it's already accumulated in `daily_extremes` (1.5–4.6mm per source) and `observations`.

## Evidence

### Display path is DB-fed for past days

`ObservationRepository.getDailyActualsWithLiveToday` (ObservationRepository.kt:350–426):

- Past days → `dailyExtremeDao.getExtremesInRange(...)` → `ObservationResolver.extremesToDailyActualsBySource` (ObservationResolver.kt:284–309), which copies `precipAmountMm` / `precipDayMm` / `precipNightMm` from the DB row into `DailyActual`.
- Today → live IDW blender path that builds `DailyActual(today, high, low, "blended")` (line 387) with **no precip arguments**.
- `mergeDailyActualsBySource(primary=pastActuals, secondary=todayBlendedActuals)` (line 422) — past wins for non-null fields, but `pastActuals` is sliced `today-30..today-1`, so today's slot is filled entirely by the precip-less live result.

`DailyViewLogic.kt:529–568` then reads `actual?.precipDayMm`, `actual?.precipAmountMm`, `actual?.precipNightMm` to drive `buildDailyRainLabel` / `buildNightRainLabel`. For past days these resolve from `daily_extremes`; for today they resolve to null.

### DB state on emulator-5554 (yesterday + today)

```
2026-05-27 (yesterday):
  NWS:        precipAmountMm=0.127, precipDayMm=NULL, precipNightMm=0.127  [updated 05:25:38]
  SILURIAN:   precipAmountMm=0.0276, precipDayMm=0.0276, precipNightMm=NULL [updated 05:25:09]
  OPEN_METEO: precipAmountMm=0.0,   precipDayMm=0.0,    precipNightMm=0.0
  TOMORROW_IO: precipAmountMm=0.0,  precipDayMm=0.0,    precipNightMm=0.0

2026-05-28 (today, ongoing):
  NWS:         precipAmountMm=1.5,  precipDayMm=NULL, precipNightMm=NULL
  SILURIAN:    precipAmountMm=1.597, precipDayMm=NULL, precipNightMm=NULL
  OPEN_METEO:  precipAmountMm=2.5,  precipDayMm=NULL, precipNightMm=NULL
  TOMORROW_IO: precipAmountMm=4.572, precipDayMm=NULL, precipNightMm=NULL
```

### NWS-night provenance (2026-05-27)

NWS had **zero observations with non-null `precipAmountMm` on 2026-05-27**. The 0.127mm in `precipNightMm` therefore came from `resolveDailyPrecip`'s forecast fallback (ObservationResolver.kt:399–421) summing `hourly_forecasts` rows in the night window (8PM → midnight). This confirms the hybrid path shipped in `da1a22b` is firing — the original design goal.

### Silurian day provenance (2026-05-27)

Two `SILURIAN_MAIN` pseudo-actual observation rows at 12:00 and 13:00 carrying 0.013785mm + 0.013799mm = 0.0276mm. `resolveDailyPrecip` took the measured branch (observations had precip), `sumDaytimePrecip` matched both rows in the 8AM–8PM window. End-to-end Silurian rain pipeline confirmed.

### Open-Meteo / Tomorrow.io zero on yesterday

Both have `_MAIN` precip rows for 2026-05-26 and 2026-05-28 but **none for 2026-05-27**. Two plausible reads:

1. **Their providers really said 0 yesterday** — these are paid forecast APIs that revise predictions; yesterday's actuals would come from the same gridpoint, which may have been ~0 until the storm rotated in overnight. The agreement of two independent providers on 0 is mild evidence in this direction.
2. **`_MAIN` backfill gap** — if the app fetched at midnight+early-morning today and `saveHistoricalActuals` only writes the recent window, prior-day hours could be missed.

Distinguishing these requires checking `hourly_forecasts` rows for those sources at yesterday's timestamps (the forecast data they were given). If those rows exist and carry non-zero precip, the fallback path would have caught it — but the fallback only fires when observations are *missing* precip, not when they're zero. So if their observations carry `precipAmountMm = 0f` (not null), the measured branch wins and zero is the answer. This is a known design choice, not a bug.

## What this answers about the user's question

**Can we ascertain history rain actuals is working?** Yes, with high confidence, for past days:

- Display path is DB-fed (verified: `extremesToDailyActualsBySource` at line 363, mapping all three precip columns).
- Per-source isolation is intact (DB shows 4 distinct values for the same date).
- NWS hybrid fallback fires when observations lack precip (verified: NWS night 0.127mm with zero observation precip).
- Silurian's `_MAIN` pseudo-actual path delivers precip into `daily_extremes` (verified: 0.0276mm match between SILURIAN_MAIN rows and DB).
- Display matches DB on the user's screen (no display/wiring drift).

What we **cannot** ascertain from this snapshot:
- Whether Open-Meteo / Tomorrow.io's zero is "their providers genuinely said no rain" vs "no data was fetched". Resolving this needs an `hourly_forecasts` query for those sources at yesterday's timestamps and an inspection of their last fetch span — out of scope for this diagnostic.

## Separate bug uncovered: today's precip drops in the live path

`ObservationRepository.kt:387` constructs:

```kotlin
todayBlendedActuals[sourceId] = mapOf(
    today to ObservationResolver.DailyActual(today, high, low, "blended")
)
```

Three precip fields default to null. The today-rain label in `DailyViewLogic.kt:529–536` therefore can't see today's actual rain — it falls back to forecast probability. For the user's "5 hours of ongoing rain" case, the today daily-view bar correctly accumulates precip in `daily_extremes` (1.5mm NWS, 2.5mm OM, etc.) and in observations, but the display layer doesn't read it.

This was masked previously because both branches were silent on precip; it surfaces now that past-day precip works.

**Fix sketch (NOT implementing yet — pending user direction):** populate the three precip fields on line 387 from either (a) the just-computed today `daily_extremes` row (re-read after recompute), or (b) a direct `resolveDailyPrecip` call on `todayObs.filter { it.api == sourceId } + hourly_forecasts.filter { it.source == sourceId }`. Option (b) is cleaner — same helper, same windows, no extra DB roundtrip — and mirrors what `aggregateObservationsToDailyBySource` already does at line 122–148.

## Chosen scope: fix today's precip-drop

User picked option (1) — fix the today-precip drop in `getDailyActualsWithLiveToday`. The OM/Tomorrow yesterday=0 investigation is deferred; the diagnostic conclusions above stand as the answer to the original question.

### Implementation

1. **Expose `resolveDailyPrecip` (or a thin wrapper) on `ObservationResolver`.** It's currently `private` (ObservationResolver.kt:399). Two options:
   - **Preferred:** add a small public helper `fun resolveDailyPrecip(observations, hourlyForecasts, sourceId, date, lat, lon): DailyPrecip` on `ObservationResolver` that filters `observations` and `hourlyForecasts` by `sourceId` internally, computes `dayStartMs`/`dayEndMs` from `date + ZoneId.systemDefault()`, and delegates to the existing private helper. Also expose the `DailyPrecip` data class (or just return a `Triple<Float?, Float?, Float?>` to avoid widening the API surface).
   - Alternative: inline the same logic at the call site. Rejected — duplicates the day/night window math and the measured-vs-forecast branch, which is the exact thing the helper centralizes.
2. **Call it from `getDailyActualsWithLiveToday`** at ObservationRepository.kt:387. Construct the today `DailyActual` with all three precip fields populated:
   ```kotlin
   val precip = ObservationResolver.resolveDailyPrecip(
       observations = todayObs.filter { it.api == sourceId },
       hourlyForecasts = hourlyForecasts,
       sourceId = sourceId,
       date = today,
       zone = zone,
   )
   todayBlendedActuals[sourceId] = mapOf(
       today to ObservationResolver.DailyActual(
           date = today,
           highTemp = high,
           lowTemp = low,
           condition = "blended",
           precipAmountMm = precip.total,
           precipDayMm = precip.day,
           precipNightMm = precip.night,
       ),
   )
   ```
   `zone` is already in scope at line 356; `todayObs` already filtered for `NWS_BLEND` at line 369. No additional DB roundtrip.
3. **Verify the merge in `mergeDailyActualsBySource`** preserves these precip values for today. `pastActuals` doesn't include today (range ends at `today.minusDays(1)`), so the merge takes `todayBlendedActuals` directly — confirmed at ObservationResolver.kt:335–352, `precip*` use `primary ?: secondary`, and primary is null for today.

No display-layer changes required: `DailyViewLogic.kt:529–568` already reads `actual?.precipDayMm` / `actual?.precipAmountMm` / `actual?.precipNightMm`, which will now be non-null for today.

### Tests

New test class `ObservationRepositoryTodayPrecipTest` (Robolectric, alongside `ObservationRepositoryDailyMergeTest.kt`). One test:
- Seed `observations` with two NWS today rows carrying non-null `precipAmountMm` (one in 8AM–8PM, one in 8PM→midnight window).
- Seed `hourly_forecasts` with NWS rows at the same hours carrying non-null `precipAmountMm` (so we also confirm the measured branch wins, not the fallback).
- Call `getDailyActualsWithLiveToday`. Assert `result["NWS"]!![today]` has `precipAmountMm = sum`, `precipDayMm = day-window-sum`, `precipNightMm = night-window-sum`.

Second test (forecast-fallback branch): seed observations with null precip, hourly_forecasts with non-null precip, assert today's `DailyActual` gets the fallback values. Mirrors the design of the past-day test in `ObservationResolverTest`.

Regression sanity: existing `ObservationRepositoryDailyMergeTest.today high comes from live IDW blender…` and `YesterdayActualHighConsistencyTest` should still pass — neither asserts on precip on the live-today path, but both exercise the affected method.

### Verification on device

1. Build + install: `./gradlew installDebug`.
2. Trigger refresh: `adb -s emulator-5554 shell am broadcast -a com.weatherwidget.ACTION_REFRESH` (the app's own action, not the system APPWIDGET_UPDATE — see memory `widget_loading_after_test_run`).
3. Pull DB and confirm today's `daily_extremes` row still has precip populated (no regression):
   `python3 scripts/backup_databases.py` then `sqlite3 ... "SELECT source, precipAmountMm, precipDayMm, precipNightMm FROM daily_extremes WHERE date = strftime('%s', date('now'))*1000;"`.
4. Confirm today's daily rain label now reflects measured rain. With the user's "5 hours of ongoing rain" still active, today's label should show ≥1.5mm (NWS) or similar — not a forecast percentage.
5. Screenshot before + after: `adb -s emulator-5554 exec-out screencap -p > /tmp/before.png && convert /tmp/before.png /tmp/before.jpg` (per CLAUDE.md's screenshot-conversion note); repeat after the install.

### Risk / out-of-scope

- This change does NOT alter past-day rendering — `pastActuals` path is untouched.
- This change does NOT recompute or write `daily_extremes`. The persisted today row already has `precipAmountMm` (per the agent's query); we're just no longer hiding it from the in-memory `DailyActual` returned to the display.
- Day/night splits for today will be null until each window is complete (e.g., night value only finalizes after midnight). This is correct behavior, mirroring past-day semantics.
- Open-Meteo / Tomorrow.io yesterday=0 investigation is deferred per user choice.

## Critical files

- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt:350–426` — the live+past assembly; line 387 is the precip-drop site (the **only** production file changing).
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt:399–421` — `resolveDailyPrecip` private helper to expose (or thin public wrapper to add).
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt:284–309` — `extremesToDailyActualsBySource` (past-day path; reference for correct shape, unchanged).
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt:529–568` — consumer site (reference, unchanged).
- `app/src/test/java/com/weatherwidget/data/repository/ObservationRepositoryTodayPrecipTest.kt` — **new** test file.
