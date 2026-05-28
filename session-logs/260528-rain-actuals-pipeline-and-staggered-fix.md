# NWS Rain Actuals Pipeline End-to-End + Staggered Tests Race Fix

## Summary

Started as a diagnostic question about whether rain history was working on emulator-5554 (NWS night-rain, Silurian day-rain, other APIs zero on 2026-05-27). The investigation confirmed past-day history works correctly but surfaced four orthogonal gaps in the NWS rain pipeline, each fixed in turn:

1. **Today's `DailyActual` dropped precip** — `getDailyActualsWithLiveToday` built today's live `DailyActual` without `precipAmountMm`/`precipDayMm`/`precipNightMm`, hiding rain that was already in `daily_extremes`.
2. **NWS `getObservations` parser dropped `precipitationLastHour`** — the historical observation endpoint had a hand-rolled inline parser that skipped precip fields, while the latest-observation path correctly extracted them via a shared helper.
3. **Pre-dawn gap in day/night windows** — observations between 00:00–08:00 vanished from both `precipDayMm` and `precipNightMm` because the old "Night(D) = 20:00 of D → 08:00 of D+1" definition left their parent date uncovered. Fixed with Option B union window.
4. **`staggered-tests.sh --install` race** — forwarded `--install` to `unit-tests.sh`, which fired `installDebug` to all devices concurrently with `emulator-tests.sh` running `am instrument`. The mid-test APK replacement killed `com.weatherwidget.test` on emulator-5556, producing the non-descriptive "One or more emulators failed."

Side work: added a SharedPref-gated **throwaway 7-day NWS observation backfill** to verify the parser fix on existing emulator data (1,749 rows refreshed, KNUQ + KSJC stations now carry precip). On-device verification confirms NWS today's `precipNightMm` flipped NULL → 6.5000mm after the pre-dawn fix landed.

## Prompts

1. "Fold emulator-5554: NWS says it rained yesterday night. Silurian said it rained yesterday during the day. The other APIs said it didn't rain. It has been raining for like the past 5 hours. I'm wondering what we can ascertain about whether history rain actuals is working."
2. (Plan mode) Scope question → "Fix today's precip-drop bug"
3. (ExitPlanMode) Plan approved
4. "Do we store hourly rainfall actuals history?"
5. "NWS has historical rainfall amounts via observation endpoint. Do we store that info?"
6. "yes, make the parser fix"
7. "It might be nice to have a one time backfill, if rain data is missing. Add it in the code and then later delete it."
8. "I see zero's in the table above for rainfall amounts. Should some of those be null instead?"
9. "fix the pre-dawn gap, option b"
10. "when I run staggered tests with two emulators I get a non descriptive error: scripts/staggered-tests.sh --install / One or more emulators failed — check logs in logs/emulator-tests / When I run scripts/emulator-tests.sh with more than one emulator there is no issue, everything passes. Can you fix staggered tests?"
11. "write a session-log to session-logs2/ dir in markdown format"

## Work Done

### 1. Diagnostic: Past-day rain history is working (Prompt 1)

Pulled live evidence from emulator-5554 — DB rows, logs, screenshot. Verdict:

| Source | 2026-05-27 DB | What user saw | Match? |
|---|---|---|---|
| NWS | precip=0.127, night=0.127 | "rained yesterday night" | ✅ |
| Silurian | precip=0.0276, day=0.0276 | "rained yesterday during day" | ✅ |
| Open-Meteo | 0.0 | no rain | ✅ |
| Tomorrow.io | 0.0 | no rain | ✅ |

The cross-source disagreement is **real per-source data**, not a display bug. NWS's night value came from the hybrid forecast-fallback (`resolveDailyPrecip` ran the forecast branch since NWS observations had no precip). Silurian's day value came from its `_MAIN` pseudo-actual observation rows. Per-source isolation confirmed via `dailyActualsBySource[displaySource.id]` at `DailyViewHandler.kt:175`.

Plan file written to `/home/dcar/.claude/plans/emulator-5556-isn-t-displaying-previous-majestic-quokka.md`.

### 2. Fix: Today's precip-drop in live-today path (Prompt 2, 3)

**Bug:** `ObservationRepository.getDailyActualsWithLiveToday` at line 387 constructed today's `DailyActual` as:

```kotlin
todayBlendedActuals[sourceId] = mapOf(
    today to ObservationResolver.DailyActual(today, high, low, "blended")
)
```

The three precip fields defaulted to null. For past days, the same function reads via `extremesToDailyActualsBySource` which copies all three precip columns from `daily_extremes`. So past days correctly displayed rain; today silently dropped it.

**Fix:**
- Made `ObservationResolver.DailyPrecip` and `resolveDailyPrecip` public.
- Simplified `resolveDailyPrecip` signature: dropped `dayStartMs`/`dayEndMs` params, computes them inline from `date + zone`. Updated the two existing internal callers (`aggregateObservationsToDailyBySource`, `computeDailyExtremes`).
- Wired the helper into `getDailyActualsWithLiveToday`:

```kotlin
val precip = ObservationResolver.resolveDailyPrecip(
    dayObs = todayObs.filter { it.api == sourceId },
    sourceHourly = hourlyForecasts.filter { it.source == sourceId },
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

**Tests:** `ObservationRepositoryTodayPrecipTest` (Robolectric, 2 tests) — measured branch (obs precip wins over conflicting forecast precip) + forecast-fallback branch (NWS hybrid scenario).

### 3. Investigation: Is hourly rainfall actuals history stored? (Prompt 4)

Answered with DB-schema and code evidence:
- **Non-NWS sources (Open-Meteo, Tomorrow.io, Silurian)**: yes — `<SOURCE>_MAIN` pseudo-actual rows in `observations` table at full hourly resolution, written by `ForecastRepository.saveHistoricalActuals` (line 801–832).
- **NWS**: no clean storage — observation stations rarely report `precipitationLastHour`. Daily-level NWS rain comes from the hybrid forecast-fallback summing `hourly_forecasts`. There's no `NWS_MAIN` row by design (it would corrupt the IDW temp blend).
- **No dedicated `hourly_actuals` table** — `observations` doubles as both real-station data and synthetic `_MAIN` actuals.

DB query from emulator-5554 (last 30h) showed the asymmetry concretely:
```
NWS:         647 obs rows /   1 with precip
OPEN_METEO:   67 obs rows /  30 with precip
SILURIAN:     70 obs rows /   5 with precip
TOMORROW_IO:  30 obs rows /  30 with precip
```

### 4. Fix: NWS `getObservations` parser dropped precipitationLastHour (Prompts 5, 6)

User pointed out: NWS observation endpoint *does* provide historical precip; do we store it?

**Investigation:** Found two parallel parsers in `NwsApi.kt`:
- `parseObservationProperties` (line 453–484, shared): extracts `precipitationLastHour` and `precipitationLast24Hours`. Used by `getLatestObservationDetailed`.
- `getObservations` (line 182–225, historical range): hand-rolled inline parsing that extracted timestamp/temp/maxTemp/minTemp but **skipped precip entirely**.

The mapping pipeline was wired end-to-end (`Observation.precipLastHourMm` field exists; `ObservationRepository.buildObservationEntity` at line 644 maps `precipLastHourMm → precipAmountMm`); only the parser was missing 2 lines.

**Fix:** Replaced `getObservations`'s 22-line inline body with a single call to `parseObservationProperties(props, defaultStationName = stationId)`. Net deletion of ~18 lines.

**Test:** New `NwsApiTest.getObservations parses precipitation fields from historical features` — seeds a 3-feature response with two precip-carrying observations and one null-precip observation, asserts all three flow through correctly.

### 5. Throwaway: One-time NWS precip backfill (Prompt 7)

Existing NWS observation rows pre-dated the parser fix, so they all carried `precipAmountMm = null`. Added a SharedPref-gated one-shot to re-fetch and overwrite them with the new parser's precip values.

**Three touchpoints, all banner-commented `THROWAWAY 2026-05-28`:**
1. `ObservationRepository.runOneTimeNwsPrecipBackfillIfNeeded(lat, lon)` — gated by `throwaway_nws_precip_backfill_done_v1` SharedPref key; calls `backfillRecentNwsObservations(lookbackHours = 24*7)`; sets flag regardless of success so it never re-runs.
2. `WeatherRepository.runOneTimeNwsPrecipBackfillIfNeeded(lat, lon)` — passthrough.
3. `WeatherWidgetWorker.doWork` — calls it after location resolution.

**On-device result on emulator-5554** (broadcast `com.weatherwidget.ACTION_REFRESH`, then 15s wait):
```
THROWAWAY_NWS_PRECIP_BACKFILL_DONE: stations=5 rows=1749
  affected=[2026-05-21, 2026-05-22, 2026-05-23, 2026-05-24, 2026-05-25, 2026-05-26, 2026-05-27, 2026-05-28]
```

Per-station precip after backfill (last 7 days, NWS only):

| Station | Type | Rows | NULL precip | Explicit 0 | Positive |
|---|---|---|---|---|---|
| AW020 | Personal | 541 | 541 | 0 | 0 |
| KNUQ | Airport | 492 | 488 | 0 | 4 |
| KPAO | Auto | 90 | 90 | 0 | 0 |
| KSJC | Airport | 541 | 533 | 4 | 4 |
| LOAC1 | Coop | 167 | 167 | 0 | 0 |

Two airport stations (KNUQ, KSJC) report precip; personal/coop/auto sites are NULL across the board. NWS today's `daily_extremes.precipAmountMm` jumped from 1.5mm (forecast fallback) → 6.5mm (measured precip winning via `resolveDailyPrecip`'s measured-preferred branch).

**Cleanup later:** `grep -rn "THROWAWAY 2026-05-28\|THROWAWAY_NWS_PRECIP\|runOneTimeNwsPrecipBackfillIfNeeded\|throwaway_nws_precip_backfill_done_v1" app/src/main`

### 6. Clarification: zeros vs NULL in display table (Prompt 8)

User noticed `0.000` for AW020/KPAO/LOAC1 in a table I'd shown. Was a display formatting bug — my query used `COALESCE(SUM(precipAmountMm), 0)` which collapsed NULL → 0.000. DB itself correctly stores NULL.

Surfaced a related discovery: stations differ in how they encode "dry":
- AW020 / KPAO / LOAC1: omit the field entirely → NULL.
- KNUQ: includes the field only during rain → dry hours are NULL.
- KSJC: includes the field always → dry hours are explicit `0.0`.

The `mapNotNull { it.precipAmountMm }.sum()` pattern handles all three correctly because it skips NULLs and treats explicit zero as a measured value.

Also identified a real pre-existing gap: the pre-dawn 00:00–08:00 window leaves observations attributed to today's `total` but neither `day` nor `night` because the old definitions were `Day(D) = 08:00–20:00`, `Night(D) = 20:00 of D → 08:00 of D+1`. This was the lead-in for the next fix.

### 7. Fix: Pre-dawn gap, Option B (Prompt 9)

**Bug:** observations between 00:00 and 08:00 of date D group under D (since their timestamp date is D), but neither D's day window (08:00–20:00) nor D's old night window (20:00 of D → 08:00 of D+1) covers them. Result: `total` includes them, `day` and `night` don't. NWS today (2026-05-28) was the canonical case — 6.5mm of pre-dawn rain showed as `total=6.5, day=NULL, night=NULL`.

**Fix:** Redefined night as the union of pre-dawn and late-evening, both within the same calendar day:
- `Day(D) = 08:00–20:00 of D` (unchanged)
- `Night(D) = (00:00–08:00) ∪ (20:00–24:00) of D`
- `Total(D) = 00:00–24:00 of D` (unchanged)
- Invariant: `day + night = total` now holds.

Edits in `ObservationResolver.kt`:
- `sumNighttimePrecip` — observation branch — filters by `(timestamp in [00:00, 08:00)) ∨ (timestamp in [20:00, 24:00))`.
- `resolveDailyPrecip` forecast fallback — computes night as `preDawn + lateEvening`, NULL only when both halves have no forecast precip:
  ```kotlin
  val nightPreDawn = sumForecastPrecip(sourceHourly, dayStartMs, dayWindowStart)
  val nightLateEvening = sumForecastPrecip(sourceHourly, dayWindowEnd, dayEndMs)
  val night = when {
      nightPreDawn == null && nightLateEvening == null -> null
      else -> (nightPreDawn ?: 0f) + (nightLateEvening ?: 0f)
  }
  ```
- Docstring updated to spell out both windows and the `day + night = total` invariant.

**Tests:** Two new in `ObservationResolverTest`:
- `computeDailyExtremes attributes pre-dawn precip to same-day night bucket` — observation branch with 3 AM + 5 AM + 10 PM observations summing to night; 2 PM in day; invariant asserted.
- `computeDailyExtremes forecast fallback attributes pre-dawn rain to night bucket` — forecast branch with same shape.

Existing 25 `ObservationResolverTest` cases stay green because their seeded observations are at 10 AM, 2 PM, 10 PM, 11 PM — all already in either day or late-evening night under both old and new definitions.

**On-device result on emulator-5554** after install (triggered a `reason=on_update_stale` non-uiOnly recompute):
```
BEFORE: NWS|2026-05-28|6.5000|NULL|NULL    (8:39 timestamp, old window definition)
AFTER:  NWS|2026-05-28|6.5000|NULL|6.5000  (8:55 timestamp, new union window)
```

`precipNightMm` correctly flipped NULL → 6.5000. `precipDayMm` still NULL because no precip-carrying observations have landed in 08:00–20:00 yet.

### 8. Fix: `staggered-tests.sh --install` race condition (Prompt 10)

**Investigation:** Read both `scripts/staggered-tests.sh` and `scripts/emulator-tests.sh`, plus the failure logs in `logs/staggered-tests` and `logs/emulator-tests/`.

Key evidence — emulator-5556's individual test log was truncated to 113 bytes:
```
com.weatherwidget.data.local.WeatherDatabaseMigrationTest:..
com.weatherwidget.util.RainAnalyzerIntegrationTest:
```
(No final `OK (X tests)` line.)

`_run_on_emulator` in `emulator-tests.sh` (line 484–497) returns 1 if the log doesn't contain `OK (...)` or `INSTRUMENTATION_CODE: -1`. Truncated log → return 1 → `OVERALL_STATUS=1` → "One or more emulators failed."

**Root cause:** `staggered-tests.sh` forwarded `--install` to `unit-tests.sh`. `unit-tests.sh` appends `installDebug` to its Gradle invocation (line 277). `installDebug` is broadcast-style — it hits **every** connected device in parallel, including emulators currently running `am instrument`. The mid-test install force-stops `com.weatherwidget.test`, the instrumentation dies silently, log is cut off.

This explains why standalone `./scripts/emulator-tests.sh` works: it doesn't trigger any `installDebug`, so there's no race.

**Fix in `scripts/staggered-tests.sh`:**
1. **Stop forwarding `--install` to `unit-tests.sh`.** Replaced:
   ```bash
   UNIT_INSTALL_FLAG=""
   if [ "$INSTALL_MODE" = true ]; then
       UNIT_INSTALL_FLAG="--install"
   fi
   "$UNIT_SCRIPT" --log-file "$UNIT_LOG_FILE" $UNIT_INSTALL_FLAG &
   ```
   with a plain invocation (`"$UNIT_SCRIPT" --log-file "$UNIT_LOG_FILE" &`) plus a multi-line NOTE comment explaining why.
2. **Defer `installDebug` to after both phases complete.** New block runs `./gradlew installDebug` only when both `UNIT_STATUS=0` and `EMULATOR_STATUS=0` and `INSTALL_MODE=true`. Writes its own `install-${TIMESTAMP}.log`. `INSTALL_STATUS` is now part of the success gate.

**Verification:** User confirmed end-to-end after the fix landed — `./scripts/staggered-tests.sh --install` now completes successfully with two emulators connected. Race resolved.

## Files changed

### Production code (permanent)

| File | Change |
|---|---|
| `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt` | `DailyPrecip` made public; `resolveDailyPrecip` made public + signature simplified; `sumNighttimePrecip` redefined as `(00:00–08:00) ∪ (20:00–24:00) of D`; forecast-fallback night computed as `preDawn + lateEvening`; docstrings updated for the new `day + night = total` invariant. |
| `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt` | `getDailyActualsWithLiveToday` now populates today's `DailyActual.precip*` fields via `resolveDailyPrecip` (previously dropped). Plus throwaway `runOneTimeNwsPrecipBackfillIfNeeded` block (banner-commented). |
| `app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt` | Throwaway passthrough for `runOneTimeNwsPrecipBackfillIfNeeded` (banner-commented). |
| `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt` | Throwaway call to `runOneTimeNwsPrecipBackfillIfNeeded` after location resolution in `doWork` (banner-commented). |
| `app/src/main/java/com/weatherwidget/data/remote/NwsApi.kt` | `getObservations` now delegates to `parseObservationProperties` instead of hand-rolled inline parsing; gains `precipitationLastHour` / `precipitationLast24Hours` extraction. |

### Tests (permanent)

| File | Change |
|---|---|
| `app/src/test/java/com/weatherwidget/data/repository/ObservationRepositoryTodayPrecipTest.kt` | **New.** 2 Robolectric tests covering today's measured-precip branch and forecast-fallback branch. |
| `app/src/test/java/com/weatherwidget/widget/ObservationResolverTest.kt` | +2 tests: pre-dawn precip lands in same-day night bucket (observation + forecast-fallback variants). |
| `app/src/test/java/com/weatherwidget/data/remote/NwsApiTest.kt` | +1 test: `getObservations parses precipitation fields from historical features`. |

### Scripts

| File | Change |
|---|---|
| `scripts/staggered-tests.sh` | Stopped forwarding `--install` to `unit-tests.sh`; added deferred `./gradlew installDebug` after both test phases pass; `INSTALL_STATUS` joined the success gate. |

### Plan

| File | Change |
|---|---|
| `/home/dcar/.claude/plans/emulator-5556-isn-t-displaying-previous-majestic-quokka.md` | Diagnostic + implementation plan for the today-precip-drop fix. |

## Test results

- **`ObservationResolverTest`**: 27/27 PASS (was 25 before the +2 pre-dawn tests).
- **`ObservationRepositoryTodayPrecipTest`**: 2/2 PASS (new class).
- **`ObservationRepositoryDailyMergeTest`**: 2/2 PASS (regression for past-day + IDW live blender).
- **`YesterdayActualHighConsistencyTest`**: PASS (daily-view ↔ hourly-graph parity).
- **`NwsApiTest`**: 12/12 PASS (including the new historical-precip test + the existing `getLatestObservationDetailed` precip tests, confirming the inline → shared parser swap is behavior-equivalent).

## On-device verification

All checks performed on emulator-5554 with the live storm in progress.

| Check | Result |
|---|---|
| Throwaway backfill fired and completed | ✅ stations=5, rows=1749, affected=8 dates |
| NWS observations now carry precip | ✅ KNUQ (4 rows / 3.8mm), KSJC (8 rows / 2.7mm) |
| NWS today's `daily_extremes.precipAmountMm` flipped from forecast fallback to measured | ✅ 1.5 → 6.5mm |
| Pre-dawn rain now attributes to today's night bucket | ✅ `precipNightMm` NULL → 6.5000 after non-uiOnly recompute |
| Open ports: any non-NWS source dropped precip? | ✅ No — Open-Meteo / Silurian / Tomorrow.io all populated as expected |
| Staggered-tests `--install` race | ✅ User-confirmed working end-to-end with two emulators |

## Open items

1. **Verify the staggered-tests fix.** The full `./scripts/staggered-tests.sh --install` run kicked off after the fix is still in flight. Follow-up needed to confirm both emulators complete cleanly with `--install`.
2. **Delete the throwaway backfill.** After a few non-uiOnly refresh cycles confirm new NWS fetches naturally carry precip, delete the three throwaway touchpoints via `grep -rn "THROWAWAY 2026-05-28"`.
3. **Open-Meteo / Tomorrow.io yesterday=0 investigation.** Deferred during the original plan. Determine whether their `_MAIN` backfill window misses prior-day hours or they genuinely had zero precip for 2026-05-27.
4. **Commits.** All five changes are uncommitted on `main`. Reasonable commit shape: (a) today-precip-drop fix, (b) NWS parser fix + test, (c) pre-dawn Option B + tests, (d) staggered-tests race fix, (e) throwaway backfill (separate so it can be reverted cleanly).
