# Past-day daily view: fall back to forecast labels when no actual row exists

Date: 2026-08-22
Status: SUPERSEDED as the final design by `260822-daily-history-forecast-only-rows.md`
(2026-08-22, user-approved). The root cause and evidence below remain accurate, and the
read-side resolver (`resolvePastLineValues`) survives — but its values will be sourced from
the `daily_history` row itself (`computed ?: forecast*`) once forecast-only rows are written,
making labels survive forecasts-table retention instead of depending on it. The display
fallback described here becomes the transitional read-side behavior, not the end state.

## Problem

On the Samsung (SM-F936U1, `RFCT71FR9NT`), daily forecast view history columns for
**Open-Meteo** drew their bars and weather icons but **no high/low temperature labels**.
**Tomorrow.io** shows the same gap for days before its actuals tracking began.

## Evidence

1. Screenshot of the Samsung home screen: past days (Wed/Thu/Fri) show bars + icons, no
   high/low labels; future days show both. Device DB pull (`databases/weather_database`):
   1. `daily_history` contains no `OPEN_METEO` rows at all (rows exist only for NWS, SILURIAN,
      TOMORROW_IO).
   2. `daily_history` has no `TOMORROW_IO` rows for 2026-08-19/20 (only 2026-08-21 onward).
   3. `forecasts` snapshots DO exist for both sources on those past dates (e.g. OPEN_METEO
      2026-08-19: 73.6/58.3 and earlier batches; TOMORROW_IO 2026-08-19: 73.1/55.9 etc.).
3. Commit `4826fad2` ("Make Open-Meteo forecast-only", 2026-08-21) stopped writing Open-Meteo
   rows to `daily_history` and added `OpenMeteoLegacyActualsCleanup` that deletes legacy rows.
4. Android renderers require the *solid* (actual) value for labels:
   1. `DailyBarRenderer.drawHighLabels`: `if (day.solidLineHigh == null) return emptyList()`.
   2. `DailyColumnRenderer`: low label uses `resolveBottomStackLow = bottomStackLow ?: solidLineLow`.
   3. Icon anchors fall back to the forecast (`iconAnchorLow`), which is why bars/icons still
      drew while labels vanished.
5. `DailyViewLogic.prepareGraphDays` / `prepareTextDays` set past-day solid values only from
   `dailyActuals[date]?.computedHighTemp/computedLowTemp`; with the row gone they become null.

## Root cause

Past-day label values came exclusively from `daily_history` actuals. When a source
legitimately has no actual row for a past day (Open-Meteo is now forecast-only by design;
Tomorrow.io lacks rows for pre-tracking days), the solid line values were null and both
renderers skipped the labels entirely — even though forecast snapshots with valid high/low
were available.

## Fix (implemented, awaiting approval)

Universal fallback (not source-gated, so it also covers pre-tracking Tomorrow.io and any
NWS missing-row day): when a past day has **no** actual AND a forecast value exists, the
forecast doubles as the solid (labeled) value. When an actual exists, behavior is unchanged
(actual is the label, forecast remains the comparison overlay).

1. `shared/.../util/DailyDayValueResolver.kt` — new pure `resolvePastLineValues(actualHigh,
   actualLow, forecastHigh, forecastLow): PastLineValues`. Falls back only when BOTH actual
   values are null; passes values through otherwise.
2. `DailyViewLogic.prepareGraphDays` (Android graph mode) — past branch routes actual +
   overlay values through `resolvePastLineValues`.
3. `DailyViewLogic.prepareTextDays` (Android text mode) — past branch falls back to the
   latest complete display-source snapshot (`!isClimateNormal`, non-null high/low) when the
   actual is missing.
4. `DesktopDailyForecastModel.buildDay` (desktop parity per the dual-platform rule) — past-day
   branch uses the same `resolvePastLineValues`.

No renderer changes are needed: the dual-label planner (`DualHighLabel.showBoth`) collapses to
a single label when solid == forecast (`MIN_DIFF_DEG` threshold), so past days get exactly one
labeled high/low with the standard past-day styling.

## Test plan (the point of this doc — prevent recurrence)

Follows the project's framework preference ladder: pure Kotlin where possible, Robolectric for
Context/DAO-touching paths, instrumented only for real Bitmap/RemoteViews.

### 1. Shared unit tests (`shared/test/.../DailyDayValueResolverTest.kt`) — extend existing

`resolvePastLineValues` cases (pure JVM, no framework):

1. Actual present (both high/low) → solid == actual, forecast passthrough (guards the
   "don't change normal behavior" invariant).
2. No actual, forecast present → solid == forecast (THE regression case).
3. No actual, no forecast → all null (no fabricated values; must match pre-existing
   "no data → no label" behavior of the climate-normal bait tests).
4. Partial forecast (high only / low only), no actual → solid per-field, missing side stays
   null (labels remain partial rather than fabricated).
5. Forecast present but actual present → forecast NOT promoted into solid.

### 2. Android Robolectric tests (`app/test/.../handlers/DailyViewLogicTest.kt`) — extend existing

Existing patterns to reuse: `extreme()` builds a `DailyHistory` actual; `createWeather()`
builds `ForecastEntity` rows; `centerDate = today.minusDays(2)`, `pastWed = today.minusDays(3)`.

Graph mode (`prepareGraphDays`) — new cases:

1. **Regression guard:** past day, `dailyActuals` EMPTY, displaySource OPEN_METEO, snapshot
   with high/low present → `past.solidLineHigh/solidLineLow` equal the snapshot values
   (previously null — this test fails on the pre-fix code).
2. Past day WITH actual (dailyActuals present) → solidLine == actual, dashedLine == overlay
   (unchanged behavior lock-in).
3. No actual AND no usable snapshot → solidLine stays null (no fabrication; composes with the
   existing "must NOT synthesize forecastHigh from climate normals" bait tests).
4. Fallback selects the LATEST complete snapshot for `displaySource` and ignores other
   sources and `isClimateNormal` rows when an actual is absent.
5. Silurian (also `supportsTemperatureActuals = false`) past day without actual → same
   fallback holds.

Text mode (`prepareTextDays`) — new cases:

6. Past day, no actual, OPEN_METEO snapshot present → `TextDayData.highLabel/lowLabel` show
   the formatted forecast values (previously null).
7. Same day WITH actual → labels show actual values (unchanged).
8. Snapshot from a different source only → labels stay null (source isolation).

### 3. Android Robolectric integration test — full loader path (new class)

Connect ≥2 real components, per the testing-strategy doc this is an integration test:
seed an in-memory Room DB the way `DailyActualsStore`/fetch write it (OPEN_METEO forecasts
table rows for a past date, NO daily_history row — exactly the post-`4826fad2` Samsung state),
then run the real read path (`GraphDataLoader`/DAO → `DailyViewLogic.prepareGraphDays`) and
assert the past day carries non-null solidLine high/low. This locks the repo-side change
(`OpenMeteoLegacyActualsCleanup` + no writer) together with the widget read side, so a future
provenance change can't silently blank history labels again.

### 4. Desktop unit test (`desktop/test/.../DesktopDailyForecastModelTest.kt`) — extend existing

1. Past day with `actual = null` and snapshot present → `solidHigh/solidLow` == snapshot
   high/low (desktop regression guard).
2. Past day with actual → unchanged passthrough.

### 5. Existing suites to keep green

1. `:shared` — full bucket run (`:shared:testShortShared`).
2. `:app:testShortDebugUnitTest` plus the Long-bucket classes touched:
   `DailyViewLogicTest`, `YesterdayActualHighConsistencyTest`, `NwsHistoryIntegrationTest`.
3. `:desktop:testShortDesktop` (+ bucket containing `DesktopDailyForecastModelTest`).
4. Watch specifically: the existing "past day must NOT synthesize forecastHigh from climate
   normals" bait tests — the fallback must never route through climate normals.

### 6. On-device verification (Samsung `RFCT71FR9NT`)

1. `./gradlew installDebug` on the Samsung.
2. Widget on Meteo source → screenshot → Wed/Thu/Fri columns show high/low labels.
3. Switch source to Tomorrow.io → screenshot → 8/19–8/20 history columns show labels
   (forecast values), 8/21 shows actual.
4. Switch to NWS → history unchanged (actuals still the label).
5. Desktop: `:desktop:run`, daily view history on an Open-Meteo location → labels visible.

## Non-goals

1. No changes to accuracy/forecast-history (ForecastHistoryActivity) computation — this is a
   display fallback only; nothing is persisted or relabeled as an "actual".
2. No renderer changes (no edits to `DailyBarRenderer`/`DailyColumnRenderer`); the fallback
   is applied at the data-preparation layer on both platforms.
3. No re-introduction of Open-Meteo actuals — commit `4826fad2`'s provenance decision stands;
   history labels for such sources now correctly say "here is what was forecast".
