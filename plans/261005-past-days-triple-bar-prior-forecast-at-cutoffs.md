# Past days get the triple bar; "yesterday's forecast" pinned to the 06:00 / 16:00 cutoffs

Status: **implemented 2026-10-05** (width 80 %). See Outcome at the end. Android + desktop.

## Request (user, 2026-10-05)

- Past days use the today column's **triple bar**, with **thinner** bars.
- The **left** bar ("yesterday's forecast"): **low** = the newest forecast fetched **before 06:00 the
  previous day**, which is 24 h before the low cutoff. This applies to **both history and today**.
- Past days still have **no bulb**.

Answers to the follow-up questions:

| Question | Choice |
|---|---|
| Left-bar **high** | Newest fetch **before 16:00 the previous day** (24 h before `HIGH_CUTOFF`) |
| Right bar on past days | Unchanged: the settled rule (`ForecastOverlaySettle`, last fetch before each extreme) |
| Storage | **Freeze into `daily_history`** (survives the 30-day `forecasts` retention) |
| Width | **80 %** of today's triple bars (was 70 %; user changed it 2026-10-05 — "try 80% first") |

## Current behaviour

- Today, left bar: `DailySnapshotSelector.selectPriorDaySnapshot(candidates, nowMillis)` picks the
  newest row fetched before `now − 24h`. If none is old enough, it takes the earliest row. High and
  low come from **one row**, and the pick depends on the current time, so it slides during the day.
  Called from Android `DailyTodayResolver.resolveTodayValues` and desktop
  `DesktopDailyForecastModel.buildDay` (`todaySnapshot`).
- Past day: a history bar plus one overlay at `centerX + forecastBarOffset` (`0.7 × barWidth`). The
  overlay is `daily_history.forecastHigh/LowTemp`, settled by `ForecastOverlaySettle`. Past days
  have no left bar and no frozen "yesterday's forecast".
- `SameDayExtremeCutoff` (06:00 low / 16:00 high) already strips same-day values from writes after
  those times. That's why these two times are the natural anchors for "24 h before".

## Design

### 1. One shared rule: `PriorDayForecast` (`:shared`, replaces `selectPriorDaySnapshot`)

For a target date **D** in local zone **z**:

```
lowCutoff  = (D − 1) at SameDayExtremeCutoff.LOW_CUTOFF  (06:00) in z
highCutoff = (D − 1) at SameDayExtremeCutoff.HIGH_CUTOFF (16:00) in z
low  = newest usable row with fetchedAt < lowCutoff,  taking its lowTemp
high = newest usable row with fetchedAt < highCutoff, taking its highTemp
```

- **Per field**, the same way as `ForecastOverlaySettle`. The two values can come from different
  fetches, and that's by design.
- A row is "usable" for a field when that field is non-null, the row isn't a climate normal or
  `GENERIC_GAP`, and it isn't collapsed (high == low). This is the existing settle predicate,
  extracted so both rules share it.
- **Fallback for today only**: the earliest usable row per field, as today. That's the live column
  the user is looking at, and showing old rows rather than hiding them was the decision recorded on
  2026-09-25. **History has no fallback.** A past-day "yesterday's forecast" fetched after its cutoff
  isn't one, so a side with no pre-cutoff fetch freezes nothing, and the bar isn't drawn if either
  side is missing.
- **DST:** cutoffs come from `atZone(z)` on a `LocalDateTime`, not from `−24h` arithmetic, so a
  23 h or 25 h day still anchors at 06:00 and 16:00 local time.
- **Today's left bar stops moving during the day.** At 10:00 it shows the same thing as at 22:00.
- The snapshot's **icon and colour** (today) come from the row that supplied the **high**, since
  that's the daytime condition.
- **Stale dash (today)**: the bar is dashed when the high row's last confirmation (Android
  `batchFetchedAt`, desktop `fetchedAt`) is more than 24 h before its cutoff. This is the same 24 h
  slack as today's `now − 48h` rule, re-expressed against the fixed anchor. `isStale` gets a
  cutoff-based overload. **History is never dashed in this change** (`ForecastHistoryRow` has no
  `batchFetchedAt`; see Follow-ups).

### 2. Freeze into `daily_history`

- New nullable columns: `priorForecastHighTemp`, `priorForecastLowTemp`.
  - Android Room **v72 → v73** (`ALTER TABLE … ADD COLUMN`, real migration, schema export).
  - Desktop **v25 → v26**.
- Written by a new `ForecastOverlaySettle`-style planner,
  `DailyHistoryMaintenance.planPriorForecasts`. It runs **in the same place as
  `planSettledForecastOverlays`** on both platforms, after every fetch, over the 31 days of
  `ForecastHistoryRow`s already loaded. It is idempotent and monotone: a frozen value is never
  erased, and it only moves to another pre-cutoff row. Candidates must match the date, source and
  site (`LocationMatch.sameSite`), as in settle. Both cutoffs are already past for any day ≤ today,
  so it can freeze **today's row too**. The live today path still reads the `forecasts` table so it
  keeps its fallback.
- **Backfill is free**: on the first run after upgrade, the planner fills every past row whose
  candidates are still inside the 30-day `forecasts` retention.
- Carry-over: every writer that replaces `daily_history` rows must copy the two new columns (the
  six-column rule in `daily_history_frozen_display_columns`). Known sites: `DailyHistoryDao` explicit
  UPDATE (line ~30), `DailyHistoryEntity` mappers (~80, ~108), `DailyHistorySnapshotter` (~138),
  `DailyHistoryMaintenance` (~112), desktop `DesktopWeatherDao` read (~1781) and its upsert. I'll
  grep for every `forecastHighTemp =` and match it.
- `lastWriter = FORECAST_FREEZE` with a new log line: `PRIOR_FORECAST_FREEZE date= src= high=a->b
  (fetched=…) low=…`.

### 3. Drawing: past-day triple bar (Android + desktop)

Past column, left to right:

| Slot | Content | Source |
|---|---|---|
| left (`x − pastTripleOffset`) | yesterday's forecast | `priorForecastHigh/LowTemp` |
| centre (`x`) | actual (history colour, **no bulb**; dashed when borrowed, as now) | `computedHigh/LowTemp` |
| right (`x + pastTripleOffset`) | settled forecast | `forecastHigh/LowTemp` (unchanged) |

- **Width** = 0.8 × today's triple width (Android 8 dp → 6.4, compact 6 → 4.8; desktop
  `compactTodayBarWidth × 0.8` for the centre, with the existing 0.65 thinner flanks scaled the same).
  New constant `PAST_TRIPLE_WIDTH_SCALE = 0.8f`; a single knob so it is easy to try other values.
- **Spacing** uses the shared `TodayColumnHighlight.tripleBarSpacing(...)` with the past widths, so
  the bars touch the same way as today's and are clamped to the column.
  `forecastBarOffset`/`FORECAST_BAR_OFFSET_SCALE` is retired for past days. Today and future columns
  are unchanged.
- **No frosted panel** on past days. That stays today's emphasis.
- Left-bar paint/colour: the same snapshot yellow / rainy override as today's left bar, using the
  day's resolved condition. Past days don't archive the snapshot icon; the right overlay already
  does the same.
- If a past day has no frozen prior pair, there's no left bar. The centre and right bars **keep the
  triple positions**, so columns don't shift between days.
- Follow-on geometry that must use the new offset or know about the left bar:
  - `DailyGraphLayoutResolver` y-range: include `priorForecastHigh/Low` for past days (it includes
    `snapshotHigh` only for today now). Otherwise a warm left bar clips.
  - `DailyBarRenderer.drawHighLabels` / `DailyHighLabelPlanner`: forecast label x follows the
    right bar at the new offset.
  - `DailyForecastRainLabelRenderer` `snapshotBarTop` collision: past days too.
  - Desktop `DailyForecastGraph`: past branch (~293), `fLabelX` (~444), ink radii (~379–380),
    `snapshotBarTop` (~578), and the y-range list (~122, ~1047).
- The left bar **never drives labels or the bar range used for the headline** (the lesson from
  `desktop_today_snapshot_24h_prior`). It's a comparison overlay only.
- Text-only (1-row) layouts: unchanged.

### 4. Data plumbing

- Android: `DailyHistory` → `DailyPastDayResolver.PastDayValues` gains `priorHigh/priorLow` →
  `DayData.snapshotHigh/Low` (reused for past days rather than adding parallel fields;
  `isToday` already separates the two meanings in the renderer).
- Desktop: `DesktopDailyDay.snapshotHigh/Low` for past days now come from `actual.priorForecast*`
  instead of `displaySnapshot`. The "most-recent snapshot for past days" branch
  (`displaySnapshot = if (isToday) todaySnapshot else snapshot`) goes away.

## Tests (automated first)

Shared unit tests (`:shared`):
1. `PriorDayForecastTest`: per-field picks at the 06:00 / 16:00 cutoffs, both sides of the boundary
   (05:59 vs 06:00 fetch), different rows for high and low, collapsed/normal/gap rows skipped,
   today-only fallback, no fallback for history, DST spring-forward and fall-back days,
   `America/Los_Angeles` plus a non-US zone.
2. Stale rule against the cutoff: 23 h 59 m not dashed, 24 h 01 m dashed.
3. `planPriorForecasts`: monotone (never erases), idempotent, site/source/date scoping, backfill of a
   retained past row, today's row frozen, log line.

Android:
4. Room migration test v72 → v73 (columns present, existing values intact).
5. Carry-over: extend `ObservationRepositoryDailyMergeTest` to assert the two new columns survive
   every replacing writer.
6. Renderer (`BarDrawnDebug`): a past day emits `PAST_PRIOR` < `HISTORY` < `FORECAST_OVERLAY` in x,
   all three at the past width, no bulb; the today column is unchanged; a missing prior keeps the
   centre/right positions.
7. Layout: a past prior high above every other value expands the y-range.
8. Integration (2+ classes): `DailyHistoryMaintenance` freeze → `DailyViewLogic` → `DayData`
   carries prior values for a past day.

Desktop:
9. Schema v25 → v26 migration plus the `recompute preserves…` test extended to the new columns.
10. `DesktopDailyForecastModel`: past-day `snapshotHigh/Low` come from the frozen columns, and
    today's left bar uses the cutoff rule (not `now − 24h`).

Device:
11. Emulator (`./scripts/emulator-tests.sh` for instrumented; never `connectedDebugAndroidTest`) and
    install on emulator: screenshot (JPG-converted) of a 5+ column widget showing past triple bars,
    plus a DB query confirming the frozen columns are populated for the last ~7 days. Rebuild and
    restart desktop (`scripts/buildStart-desktop.sh`) and screenshot the popup.

## Risks / notes

- **Visual density**: three bars in a past column at 6.4 dp plus labels. The dual high-label planner
  already handles "actual vs forecast". A third value has **no label** (the left bar is unlabelled
  on today too).
- **Fewer left bars on days the device was offline the previous day**: by design, history has no
  fallback.
- Today's left bar changes meaning (anchored, not sliding). Before 06:00 / 16:00 on the previous day
  there may be no qualifying row for a newly acquired site; the today fallback covers it.

## Follow-ups (not in this change)

- History stale dash: needs `batchFetchedAt` on `ForecastHistoryRow` (Android dedup re-stamps it),
  or a frozen confirmation time.
- Retire `DailySnapshotSelector.PRIOR_WINDOW_HOURS` once nothing references it.

## Outcome (2026-10-05)

- Implemented as designed. The scale constant lives in shared `TodayColumnHighlight.PAST_TRIPLE_WIDTH_SCALE`;
  Android `DailyBarRenderer.PAST_TRIPLE_WIDTH_SCALE` aliases it.
- Tests: shared `PriorDayForecastTest` (9), `PriorForecastFreezeTest` (6); Android
  `SettlePastForecastOverlaysTest` (+1, Room), `PastDayTripleBarDataIntegrationTest` (2),
  `DailyForecastGraphRendererRoboTest` (+3), `DailyTodayResolverSnapshotStaleTest` (rewritten for
  anchors), `WeatherDatabaseMigrationTest.migrate72To73` (emulator); desktop
  `DesktopPriorForecastFreezeTest` (3), `DesktopDailyForecastModelTest` (+1). Full suites green:
  app 2335, shared 1811, desktop 473.
- Emulator (Generic_Foldable_API36): after a Settings → Refresh, `PRIOR_FORECAST_FREEZE` backfilled
  every source for 09-29..10-05; past columns draw the triple bar.
- **Follow-up fix (same day):** the user saw no left bars on emulator-5556 and the Pixel — no full
  sync had run since install (the freeze only runs in a full sync, and that sync paints before it), and
  on battery the next one can be hours away. Renders now fall back to a live pick when the frozen
  columns are empty (`PriorDayForecast.resolvePast`, no fallback, same rule as the freeze — the
  settled overlay's existing pattern). Android's past-day snapshots held only each day's newest row,
  so the three loaders (bundle, startup, interaction) append `ForecastDao.getPriorForecastCandidates`
  (fetchedAt in [targetDate − 72h, targetDate + 5h]; ~hundreds of rows, not the ~6k of all fetches)
  after the newest rows, keeping each day's first row its newest. Desktop already loads every fetch.
  Verified with no refresh on emulator-5556 and the Pixel 7 Pro right after install.
- Desktop: v26 migration and freeze ran on restart; Fri/Sat/Sun draw the triple bar.
- `DailySnapshotSelector.selectPriorDaySnapshot` is now
  unused by production code (kept for its tests; retire with `PRIOR_WINDOW_HOURS`).
