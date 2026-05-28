# Day/Night Rain Actuals — Graph Refinements + Statistics History

## Context

The previous commit (`addb625`) added observed rainfall to the **hourly precipitation graph**
(PRECIPITATION view) for past windows. The renderer collapses the *entire visible window* into a
**single** `Pred Xmm` + `Act Ymm` pair (`rainAmountWindowHours = hours.size`,
`PrecipViewHandler.kt:322`; period logic at `PrecipitationGraphRenderer.kt:381-432`,
`findFixedWindowRainPeriods` / `findVisibleWindowRainPeriods` at `:952-1011`).

Two problems with that single aggregate, plus a desire for a durable history view:

1. **No day/night split** — one number over a 24h WIDE window blends an afternoon shower with
   overnight rain. The rest of the app frames rain as day-rain vs night-rain (DAILY view).
2. **WIDE zoom only** is uninformative when zoomed in (NARROW ≈ 4–5h) — a single total tells you
   nothing per-hour.
3. No place to review day/night predicted-vs-actual rain *over time*.

**Day/night definition (user-specified): fixed clock hours — DAY = 08:00–20:00, NIGHT = 20:00–08:00.**
This is clock-based, NOT the sun-position `isNight` field on `PrecipHourData`. Do **not** reuse
`isNight` for bucketing; derive from `dateTime.hour in 8 until 20`.

Decisions (confirmed with user):
- Show in **both** the widget graph and a new Statistics history block.
- **WIDE**: split into day/night; label **wettest day + wettest night only**; draw a vertical
  **day/night divider line** at the 08:00 / 20:00 boundaries, styled like the temperature graph's
  NOW line.
- **NARROW**: per-hour `Pred`/`Act`, but **only for hours where rain exists** (pred>0 or actual>0),
  capped to the first 4 in-window hours (5th is clipped at the edge).

---

## Part A — WIDE zoom: day/night split + divider line

**File:** `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt`
(+ `PrecipitationGraphStyle.kt` for a divider paint), `PrecipViewHandler.kt` for wiring.

1. Add a clock-based classifier helper, e.g. `isDayHour(dt: LocalDateTime) = dt.hour in 8 until 20`.
2. Replace the single-window aggregation (when `zoom == WIDE`) with **day/night segmentation**:
   - Partition the visible `hours` into contiguous runs by `isDayHour`.
   - Reuse the existing per-period summation (the pattern in `findFixedWindowRainPeriods`) to total
     `precipAmountMm` (pred) and `actualPrecipAmountMm` (actual) per run.
   - Pick the **wettest day run** and **wettest night run** (max total over pred+actual) → at most
     2 segments. Emit pred + actual placements for those via the existing
     `calculateRainAmountPlacements` (`:410-432`) so collision avoidance is preserved.
3. **Divider line**: model on `GraphRenderUtils.drawNowIndicator` (`GraphRenderUtils.kt:369-404`).
   Compute the X for each 08:00 / 20:00 crossing inside the window (reuse `computeNowX` pattern at
   `:198`) and draw a vertical line. Add a dimmer `dayNightDividerPaint` to
   `PrecipitationGraphStyle.kt` (distinct from `currentTimePaint`). Region labels ("day"/"night")
   optional — axis hour labels already give context; keep minimal unless cramped allows.

Keep NARROW out of this path (handled in Part B); WIDE keeps `rainAmountWindowHours` plumbing but the
renderer branches on a new segmentation mode.

## Part B — NARROW zoom: per-hour Pred/Act where rain exists

**Files:** `PrecipitationGraphRenderer.kt`, `PrecipViewHandler.kt`.

1. When `zoom == NARROW`, build **per-hour** rain periods (one `RainPeriod` per hour, `windowHours=1`)
   instead of one window, for the first 4 in-window hours.
2. **Filter**: skip any hour where both pred (`precipAmountMm`) and actual (`actualPrecipAmountMm`)
   are null/≤0 — "only where rain exists."
3. Emit `Pred` (when pred>0) and `Act` (when past hour & actual>0) per surviving hour; future hours
   naturally show pred only. Existing `calculateRainAmountPlacements` stacks Pred above Act and
   resolves collisions.

## Part C — Statistics: day/night rain accuracy history

**New computation + UI.** Mirrors the existing temp-accuracy flow in `StatisticsActivity.kt`
(`:54-129`) / `AccuracyCalculator.kt` / `DailyAccuracy` (`AccuracyStatistics.kt:38`).

Data sources (no new pipeline — both already populated):
- **Predicted hourly**: `HourlyForecastHistoryEntity.precipAmountMm` via `HourlyForecastHistoryDao`
  (1-day-ahead `snapshotBucket`). Likely need a new DAO query to fetch history for a target-date
  range selecting the ~1-day-ahead bucket per date (current API is `getHistoryForBucket`).
- **Actual hourly**: `ObservationEntity.precipAmountMm` via `ObservationDao.getObservationsInRange`
  (same bucketing logic already used in `PrecipViewHandler.buildActualPrecipByHour`).

Steps:
1. New `RainAccuracyCalculator` (or extend `AccuracyCalculator`): for each of the last N days, bucket
   both predicted and actual hourly precip into DAY (8a–8p) / NIGHT (8p–8a) clock buckets and sum mm.
   Produce a `DailyRainAccuracy(date, source, predDayMm, actDayMm, predNightMm, actNightMm)`.
2. UI: add a rain section to `StatisticsActivity` — either a second RecyclerView/adapter or extend
   `DailyAccuracyAdapter` + `item_daily_accuracy.xml` with day/night rain rows (predicted vs actual).
3. Keep clock-bucketing identical to Parts A/B so widget and stats agree.

---

## Files to modify

- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphRenderer.kt` — day/night segmentation
  (WIDE), per-hour periods (NARROW), divider line.
- `app/src/main/java/com/weatherwidget/widget/PrecipitationGraphStyle.kt` — `dayNightDividerPaint`.
- `app/src/main/java/com/weatherwidget/widget/handlers/PrecipViewHandler.kt` — branch on zoom; pass
  segmentation/per-hour mode; shared `isDayHour` helper.
- `app/src/main/java/com/weatherwidget/data/local/HourlyForecastHistoryDao.kt` — range/1-day-ahead query.
- New `app/src/main/java/com/weatherwidget/stats/RainAccuracyCalculator.kt` + data class in
  `AccuracyStatistics.kt`.
- `app/src/main/java/com/weatherwidget/ui/StatisticsActivity.kt` + `DailyAccuracyAdapter.kt` +
  `res/layout/item_daily_accuracy.xml` (or a new rain item layout).

## Reuse (don't reinvent)

- Period summation + collision-aware placement: `findFixedWindowRainPeriods`,
  `calculateRainAmountPlacements` (`PrecipitationGraphRenderer.kt`).
- Divider line: `GraphRenderUtils.drawNowIndicator` / `computeNowX`.
- Observation hour-bucketing: `PrecipViewHandler.buildActualPrecipByHour`.
- `PrecipRect` testability seam (see memory `precip_rect_testability`) — keep it.

## Verification

- **Unit tests** (`./gradlew testDebugUnitTest`):
  - `PrecipitationGraphRendererTest` — add: WIDE emits ≤2 day/night Pred+Act labels & a divider;
    NARROW emits per-hour Pred/Act skipping dry hours; clock boundary (8a/8p) classification.
  - `PrecipViewHandlerTest` — `isDayHour` bucketing; NARROW first-4 / rain-exists filtering.
  - New `RainAccuracyCalculatorTest` — day/night mm bucketing from history + observations.
  - Renderer tests: assert drawLine counts / X positions, not colors (memory
    `renderer_test_color_is_zero`).
- **Instrumented**: `./scripts/emulator-tests.sh` (never `connectedDebugAndroidTest`).
- **Manual on device**: `./gradlew installDebug`, then navigate the precip graph to a past day in
  WIDE (verify divider + day/night totals) and NARROW (verify per-hour Pred/Act, dry hours blank);
  open Statistics to confirm the day/night rain history block. Pull screenshots per CLAUDE.md
  (screencap → convert to JPG).
