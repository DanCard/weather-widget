# Unify daily-extreme blending with the live blender

## Context

Yesterday the user watched the widget show "high: 73.1°" through the evening. Today the history view shows "high: 73.5°" for the same day. Both numbers come from the same raw observations stored in the same DB; they diverge because the two render paths use different blending algorithms:

- **Live path (correct)** — `ObservationBlender.blendObservationSeries` builds a per-timestamp IDW blend across stations, then the consumer (`ObservationRepository.getDailyActualsWithLiveToday`) takes `maxOf` / `minOf` over that series. Time-aligned: each blended sample is a physically meaningful temperature that existed at one instant.
- **Historical path (buggy)** — `ObservationResolver.blendExtremes` takes each station's spot-max independently, then IDW-blends those non-coincident peaks *as if they were synchronous*. Produces a number that nothing ever actually equalled.

Worker tick recompute (`WeatherWidgetWorker.fetchDailyActuals` → `recomputeDailyExtremesForDay`) writes to `daily_extremes`, and a persistence guard (lines 449-457) only ratchets up — so the over-counted high drifts upward over the day and freezes there. The user's evening view (73.1, live) ≠ the next-morning history view (73.5, ratcheted historical).

There's a giveaway comment at `ObservationRepository.kt:393-396` warning future maintainers not to merge `daily_extremes` into today's live actuals "because the two algorithms disagree." That comment is the right *workaround* for today; the root fix is to make both paths use the same algorithm.

**Intended outcome:** today's live high, frozen at end-of-day, equals tomorrow's history high. To verify: tonight's last `WIDGET_RENDER` value for today ≈ tomorrow's `daily_extremes` row for the same date.

## Approach

Route the historical path through the same `ObservationBlender.blendObservationSeries` that the live path already uses, then take `maxOf`/`minOf` over the resulting series. Drop the `maxTempLast24h`/`minTempLast24h` floor inside the blend (those fields remain on `ObservationEntity` for `ForecastHistoryActivity` to use elsewhere — we just stop using them to inflate the daily extreme). Split the persistence policy: ratchet for today (preserves transient-drop protection), overwrite for past days (idempotent because past days have complete observation sets).

No DB migration. The worker iterates 30 days back every tick already; on first install of the fix it will overwrite every existing past-day row with the corrected value. Self-healing within ~1 minute of install.

## Files to modify

### Production code

- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt`
  - Add `computeDailyExtremesViaSeries(observations, hourlyForecasts, activeSources, lat, lon): List<DailyExtremeEntity>` — groups obs by `(date, source)`, loops sources, calls `ObservationBlender.blendObservationSeries` with each source's `WeatherSource`, then `maxOf`/`minOf` over the returned series. Picks most-common condition from raw obs (unchanged logic). Constructs `DailyExtremeEntity` with `updatedAt = now`.
  - Change `computeDailyExtremes` (line 174) signature to `(observations, hourlyForecasts, activeSources, lat, lon)`; delegate to the new helper.
  - Mark `blendExtremes` (line 95) `@Deprecated` and remove once nothing references it (after `aggregateObservationsToDailyBySource` is gone — see below).
  - Delete `aggregateObservationsToDailyBySource` (line 138) after rerouting its only external caller.

- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt`
  - `recomputeDailyExtremesForDay` (line 426): load `hourlyForecasts` for `[startTs - 24h, endTs + 6h]` via `hourlyForecastDao.getHourlyForecasts(...)`. Pass them + `activeSources` to the new `computeDailyExtremes`. Pass `activeSources` through `recomputeDailyExtremesFromStoredObservations` (line 403) — add a parameter.
  - Persistence guard (lines 449-465): split by `date.isEqual(LocalDate.now())`:
    - **Today:** keep the existing ratchet (`maxOf(existing.high, new.high)` / `minOf(existing.low, new.low)`).
    - **Past day:** overwrite unconditionally with `new`, log as `DAILY_EXTREME_OVERWRITE`.
  - Remove the workaround comment at lines 393-396 once the merge is safe (it won't be a no-op anymore — past values are now consistent with live).

- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetWorker.kt`
  - `fetchDailyActuals` (line 231): pass `hourlyForecasts` and `activeSourceList` through to `recomputeDailyExtremesFromStoredObservations`.

- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
  - `getDailyActuals` (lines 419-454): delete the body and route through `ObservationRepository.getDailyActualsWithLiveToday(lat, lon, hourlyForecasts, activeSourceList)` instead. The router already loads hourly forecasts upstream — confirm during implementation; if not, plumb them in. This removes the only remaining caller of `aggregateObservationsToDailyBySource`.

### Tests

- `app/src/test/java/com/weatherwidget/widget/ObservationResolverTest.kt`
  - `blendExtremes IDW near station dominates over far station` (line 323) — rewrite as `computeDailyExtremes IDW near station dominates`. New expected values computed from time-aligned algorithm (spot temps at each timestamp, not per-station-peak).
  - `blendExtremes IDW two equidistant stations average their extremes` (line 341) — rewrite. Where the old test asserted "average of station highs," the new test asserts "max over time of IDW-blended samples." Re-derive numbers.
  - `blendExtremes per-station aggregation same station multiple readings uses max extreme` (line 356) — semantic change: now the IDW pulls the same-station value at each candidate timestamp; assert per-time aggregation, not per-station max-over-time.
  - `blendExtremes spot-temp fallback also uses IDW when no official extremes` (line 386) — likely passes unchanged; verify.
  - **New tests:**
    - `computeDailyExtremes with two stations peaking at different times produces lower high than per-station-max` — locks in the bug fix.
    - `recomputeDailyExtremesForDay today preserves prior peak (ratchet)` — guards the live-day behavior.
    - `recomputeDailyExtremesForDay past day overwrites stale higher value` — guards the self-healing migration.

## Verification

1. **Unit tests:** `./gradlew testDebugUnitTest --tests "com.weatherwidget.widget.ObservationResolverTest"` and `--tests "com.weatherwidget.data.repository.ObservationRepositoryTest"` (add coverage if missing).
2. **Build + install:** `./gradlew installDebug`. Observe emulator logcat for `recomputeDailyExtremesForDay` lines covering 30 days; verify `DAILY_EXTREME_OVERWRITE` rows appear in `app_logs` for past days where the blend changed.
3. **Reconcile yesterday:** query `daily_extremes` for `date=2026-05-16, source=NWS` via `python3 scripts/backup_databases.py` + local sqlite3. Expect `highTemp` to drop from 73.49 → roughly 73.1 (the live blender's value). Same for `lowTemp`.
4. **End-to-end consistency check (multi-day):** capture today's last live `WIDGET_RENDER` value just before midnight. Next morning, query `daily_extremes` for today's date — should equal the captured live value within ±0.05°. (Run as a one-off; doesn't need automation.)
5. **History-of-forecasts activity sanity:** open `ForecastHistoryActivity` for a past day. Confirm `maxTempLast24h` / `minTempLast24h` still render where that UI uses them — those fields stay on `ObservationEntity`, just don't influence the blend anymore.

## Out of scope

- The `DailyClickHandlerFactory` change rerouting history taps to the temperature hourly graph — already shipped in the prior turn.
- The DB-persisted `DAILY_EXTREME_BLEND` audit log — already shipped in the prior turn; we'll see its output naturally once this fix lands.
- Removing the `maxTempLast24h`/`minTempLast24h` columns from `ObservationEntity` — they remain in use by `ForecastHistoryActivity` per the user's note. Out of scope for this plan.
