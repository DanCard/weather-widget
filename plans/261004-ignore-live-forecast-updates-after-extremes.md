# Ignore live forecast updates once the day's low/high are past (or nearly past)

Follow-up to [261004-forecast-overlay-frozen-at-extreme-time.md](261004-forecast-overlay-frozen-at-extreme-time.md).

## Problem

APIs keep rewriting a day's forecast high/low **after those extremes have already happened** (or are
about to). Those writes are hindcasts, not forecasts: Open-Meteo documents its past hours as
"stitching the first hours of each successive model run … initialised from real measurements",
with nothing in the response to mark them. The user rule (2026-10-04):

> I find it useful when forecast is updated, even an hour before the high is reached. I don't find
> it useful when it is updated after.

New request (2026-10-04, this plan): **ignore updates when the low and high are in the past or near
future, within 1 hour.**

### What is already solved (261004)

| Layer | Behavior today |
|---|---|
| Past-day yellow overlay (`daily_history.forecastHighTemp/LowTemp`) | Settled by `ForecastOverlaySettle` to the last valid fetch with `fetchedAt ≤ computedHighAt` / `≤ computedLowAt`. Done after the day rolls over. |
| Extreme timestamps | `daily_history.computedHighAt/LowAt` from `ActualsAggregator` (first blend point within 0.5 °F of max/min). |
| Archive freeze window | `DailyHistoryFreeze.overlayWindowOpen` = until **local midnight at end of date**. |

### What still moves after the forecast point

1. **Live today overlay.** `DailyHistoryFreeze.overlayWindowOpen` stays open until midnight, so every
   fetch re-merges `forecastHighTemp/LowTemp` all afternoon/evening — even after the high (or low)
   was reached. Settle only runs on past days (`ForecastOverlaySettle.settle` returns null for today).
2. **Widget/desktop "forecast" high/low for today.** The right side of the Today triple bar and the
   daily-column forecast figures re-read the latest `forecasts` batch on every fetch. After 16:00
   the "forecast high" can still slide to match what already happened.
3. **`forecasts` table batches.** Every API response is stored (REPLACE-upsert per source/date).
   Needed for accuracy tracking and for settle's `fetchedAt ≤ extreme` pick — do **not** stop
   writing these. The problem is *which* batch the UI and freeze path consume, not that it is stored.

Out of scope (unchanged): rain-chance 8 pm / 8 am windows, `ForecastSnapshot` accuracy history
(should keep recording every batch), hourly curves, current-temp interpolation.

## Rule (interpretation)

Per-side, matching 261004:

- **High stays live** until the high has been reached (or is about to be — see options).
- **Low stays live** until the low has been reached.
- The two sides freeze independently (NWS already drops `lowTemp` from later batches after the low
  passes; requiring both would be wrong).
- "Reached" = same definition as `ForecastOverlaySettle.firstReachedAt` (first blend point within
  0.5 °F of the day's max/min) when `computedHighAt/LowAt` is known.

**Ambiguity in "within 1 hour":** the 2026-10-04 quote says an update *one hour before* the high is
still useful. So "within 1 hour" is probably **not** "freeze 1h early". Two readings that fit both
sentences:

| Reading | Freeze when… | Matches 261004 quote? |
|---|---|---|
| **A (recommended)** | extreme already reached; use ±1 h only as a *recognition* window when the exact time is unknown | Yes — updates remain useful until the high |
| **B** | `now ≥ extremeTime − 1h` (freeze one hour early) | No — contradicts "even an hour before … useful" |

## Design options

### Option A — freeze the live overlay per side at the extreme (recommended)

Stop rewriting a side of **today's** frozen overlay once that side's extreme is known to have been
reached (or, without a known time, once a 1 h heuristic says it has).

1. **Pure helper in `:shared`**, next to `ForecastOverlaySettle`:
   ```kotlin
   // DailyHistoryFreeze or new ForecastUpdateGate
   fun sideStillLive(
       nowMs: Long,
       extremeAtMs: Long?,      // computedHighAt / computedLowAt; null = unknown
       predictedHourMs: Long?,  // optional: hourly-curve predicted peak/trough
       graceMs: Long = 60 * 60 * 1000,
   ): Boolean
   ```
   - `extremeAtMs != null && nowMs > extremeAtMs` → **false** (frozen).
   - `extremeAtMs == null` and `predictedHourMs != null` and `nowMs ≥ predictedHourMs + graceMs`
     (or `≥ predictedHourMs` under reading B) → **false**.
   - Else **true**.
2. **Today overlay merge** (`DailyHistoryMaintenance` / `DailyHistoryFreeze.merge`): today's
   `overlayWindowOpen` becomes per-side: high window closes at `computedHighAt` (or heuristic), low
   at `computedLowAt`. Past days unchanged (midnight + settle).
3. **Display path for today's forecast high/low** (widget `DailyViewLogic` / `DailyPastDayResolver`,
   desktop `DesktopDailyForecastModel`): when rendering today, if a side is frozen, prefer the
   frozen `daily_history.forecast*Temp` over the newest batch. That is what the user sees stop
   moving.
4. **Do not change** the `forecasts` writer — history and accuracy stay complete; settle can still
   pick the right pre-extreme batch afterwards.
5. Both platforms via `:shared`; no Room/schema change (reuses `computedHighAt/LowAt`).

**Edge cases**

- Extreme time unknown (no observations yet, or flat curve never "reaches"): fall back to the
  hourly forecast's predicted peak/trough hour + grace; if that is also unknown, keep live until
  midnight (today's current behavior) and let settle fix it after rollover.
- Late observations move `computedHighAt` earlier → side freezes earlier on the next pass. Moving
  later does **not** unfreeze (monotone freeze, same spirit as `DailyHistoryFreeze.merge`).
- High already reached, low not (typical afternoon): high frozen, low keeps updating. Complete-batch
  rule: if the API batch is incomplete (NWS drops low), do not let a frozen high block a live low
  write — merge per field, not per row (261004 already learned this).
- Climate normals / GENERIC_GAP / high==low degenerate rows: never freeze-worthy candidates
  (reuse `DailyHistoryFreeze.isValidOverlayCandidate` / `ForecastOverlaySettle.usable`).

### Option B — freeze 1 hour before the extreme

Same as A but close the window at `extremeAt − 1h`. Simpler UX ("stop touching it soon") but
directly contradicts the 2026-10-04 quote. Only pick this if the user restates the rule.

### Option C — only settle after the fact (status quo + polish)

Keep live updates until midnight; rely on `ForecastOverlaySettle` to rewrite the archive after
rollover. Already shipped. Does **not** stop the widget from sliding this afternoon — fails the
new complaint.

### Option D — suppress writes at the repository fetch layer

Drop entire API daily rows for "today" once both extremes are past, at `ForecastRepository` /
desktop equivalent. Too blunt: throws away the batch that accuracy tracking and settle need, and
does not help the high-frozen / low-still-live case.

## Recommended shape (A)

1. `:shared` pure gate `ForecastUpdateGate.sideStillLive` (or extend `DailyHistoryFreeze`) + unit
   tests for the matrix: known extreme past/future, unknown extreme with/without predicted hour,
   both sides, independent sides.
2. Wire into today's overlay merge (Android `DailyHistoryMaintenance` + `DailyActualsStore` path;
   desktop `DesktopWeatherRepository` freeze block) — per-field, monotone.
3. Wire into today's display pick for forecast high/low (widget + desktop day model): frozen side
   prefers `daily_history.forecast*Temp`.
4. Leave `forecasts` inserts, `ForecastSnapshot`, and accuracy alone.
5. Logs at DEBUG: `FORECAST_FREEZE high/low closed at …` (sparse; one line per side per day).

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | `sideStillLive` matrix (known/unknown extreme, predicted hour, grace, both sides) | unit (`:shared`) |
| 2 | Today overlay: high freezes at `computedHighAt`, low still merges | unit (`DailyHistoryFreeze`/maintenance) |
| 3 | Today overlay: incomplete NWS batch (null low) cannot clobber a frozen high | unit |
| 4 | Extreme time moves earlier → freeze earlier; later → no unfreeze | unit |
| 5 | Display: frozen side shows `daily_history` overlay, live side shows newest batch | unit (view logic / day model) |
| 6 | Past days still settle as in 261004 (no regression) | unit + existing suite |
| 7 | Android end-to-end: fetch → freeze mid-day → later fetch does not move the yellow bar | Robolectric |
| 8 | Desktop end-to-end equivalent | integration |

Mutation check each.

## Open questions (for user)

1. **Reading A vs B** — freeze at the extreme (A) or one hour *before* it (B)? The 2026-10-04 quote
   supports A; the new "within 1 hour" wording could mean B.
2. Should the freeze also apply to the **right-hand live forecast** of the Today bar (not only the
   yellow overlay)? Plan assumes yes — that is what the user sees move.
3. If the extreme time is unknown and no hourly peak is known, is "keep live until midnight + settle
   later" acceptable?
4. Rain chance / precip amount / cloud: out of scope here, or same gate?

## Implementation notes

- Reuse `ForecastOverlaySettle.EXTREME_REACHED_TOLERANCE_F` and `firstReachedAt` definitions so
  "reached" means the same thing at freeze time and settle time.
- Prefer extending `DailyHistoryFreeze` (already the shared freeze-policy home) over a new parallel
  object, unless the gate needs hourly-forecast inputs that do not belong there.
- Dual-platform parity is mandatory (`:shared` + Android + desktop).
- No schema change expected. If a predicted-peak hour is stored, prefer computing it from the
  hourly series already loaded in the merge path (avoid a new column).
