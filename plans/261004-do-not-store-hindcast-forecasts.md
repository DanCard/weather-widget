# Do not store daily forecasts that are actually hindcasts

Follow-up to [261004-forecast-overlay-frozen-at-extreme-time.md](261004-forecast-overlay-frozen-at-extreme-time.md)
(settle-after-the-fact) and in the same family as
[260808-nws-actuals-forecast-contamination.md](260808-nws-actuals-forecast-contamination.md)
(past data wearing the wrong clothes).

## Problem

**The database must not contain junk.** Once a day's low and high have already happened (or are
about to happen), any "forecast" of those values is a **hindcast**. Open-Meteo documents its past
hours as "stitching the first hours of each successive model run … initialised from real
measurements" — nothing in the response marks them. Storing them as forecasts:

1. Pollutes `forecasts` (and the evolution/history buckets derived from it).
2. Inflates accuracy tracking (a "prediction" made after the fact is not a prediction).
3. Forces every reader (settle, display, stats) to re-discover the same filter.

**Display is out of scope.** If the DB only holds real forecasts, the UI fixes itself.

User rule (2026-10-04):

> I don't like when API update forecast, past the forecast point. … ignore updates when low and
> high is in the past or near future, within 1 hour.
>
> I don't want the database to contain junk, as in forecasts that aren't forecasts, that are
> actually hindcasts.

### Already filtered at write time

| Path | Gate today |
|---|---|
| Past **days** (targetDate < today) | `ForecastSnapshotStore.saveForecastSnapshot` drops them; desktop `persistForecastResult` keeps `date >= today`. |
| **Hourly** elapsed hours | `ElapsedForecastBackfill` files them into history, not live forecast tables (freshest-wins would promote a hindcast). |
| **NWS period** rows | `SNAPSHOT_SKIP_ELAPSED` when `periodEnd < now`. |
| Climate normals / implausible temps | Dropped earlier. |

### The hole

**Today's daily high/low keep being written after those extremes have passed** (or minutes before
they do). Example (Oct 3, Mountain View, Open-Meteo): high ~16:15, low ~05:15; the **22:25** fetch
still wrote 89/58 into `forecasts`. That row is not a forecast.

`periodEnd` does not help for sources that publish a whole-day high/low with no period end
(Open-Meteo daily, WeatherAPI, …). Nothing compares the row against *when the high/low actually
happen*.

## Rule

A daily forecast row for a day is **junk (a hindcast)** — do not store it — when **both** the high
and the low it predicts are no longer in the future:

```
sideResolved(sideTimeMs, nowMs)  =  sideTimeMs <= nowMs + 1h
dropRow  =  sideResolved(highTime) && sideResolved(lowTime)
```

"Within 1 hour" = the extreme is in the past **or** due within the next hour (the last useful
forecast window, per 2026-10-04: *"I find it useful when forecast is updated, even an hour before
the high is reached. I don't find it useful when it is updated after."* — so a fetch at 15:15 for a
16:15 high is still a forecast and **is** stored; a fetch at 16:15 or later is not).

**Per-side refinement (recommended):** if only one side is resolved, store the row but **null out
the resolved side** (keep the still-live low, or still-live high). That matches NWS, which already
drops `lowTemp` from later batches after the low passes. A row is fully dropped only when both
sides are resolved or both values are null.

**How "side time" is known** (first hit wins):

1. **Observed extreme time** — `daily_history.computedHighAt` / `computedLowAt` when present for
   that date/site (already maintained for settle). Once the blend has reached the extreme, the
   side is resolved even if the clock guess was wrong.
2. **Period end** — `periodEndTime` / day-partition end when the API supplies it (`dayEnd` 20:00
   for a day high, `nightEnd` next 08:00 for a night low — `ForecastSnapshotStore.mapDailyForecast`
   already computes these windows).
3. **Hourly-curve predicted peak/trough** — the hour of max/min temperature in the day's hourly
   forecast for that source (the same series the widget draws).
4. **Fallback** when nothing above is known: treat the **day high** as resolved after **20:00**
   local and the **night low** after **08:00** the next calendar morning (conservative; keeps
   overnight writes of a morning low). Never invent a time in the past for a future `targetDate`.

Future `targetDate`s (tomorrow and later) are always stored — their low/high cannot have happened.

### Out of scope

- Display / freeze windows / `daily_history.forecast*Temp` (already settled after rollover).
- Hourly live tables (already gated by `ElapsedForecastBackfill`).
- Rain chance, precip amount, cloud — same junk concern is possible but the request names low/high.
- `ForecastSnapshot` 1-day-ahead accuracy rows (already have an 8 pm cutoff) — leave as-is unless
  accuracy tests show hindcast contamination there too.

## Design

### 1. Pure gate in `:shared`

```kotlin
object ForecastHindcastGate {  // or fold into an existing :shared policy object
    const val NEAR_FUTURE_GRACE_MS = 60 * 60 * 1000L

    data class ExtremeTimes(val highAtMs: Long?, val lowAtMs: Long?)

    /** Null out (or drop) values that are no longer predictions. */
    fun filterDaily(
        highTemp: Float?,
        lowTemp: Float?,
        times: ExtremeTimes,
        nowMs: Long,
    ): Pair<Float?, Float?>  // filtered high/low; both null => caller drops the row
}
```

Callers supply `times` resolved via the cascade above; the gate itself is pure and testable.

### 2. Android — `ForecastSnapshotStore.saveForecastSnapshot` (and `mapDailyForecast` callers)

Inside the existing `forecastsToSave` filter/map, after `SNAPSHOT_SKIP_ELAPSED`:

- Resolve extreme times for `(targetDate, source, site)` (reuse the same lookup settle uses, or a
  cheap hourly-peak helper — do **not** force a full actuals blend on every save).
- Apply `filterDaily`; if both sides null → skip the row and log
  `SNAPSHOT_SKIP_HINDCAST date=… source=… highAt=… lowAt=…`.
- Keep `SNAPSHOT_SKIP` / `SNAPSHOT_UPGRADE` semantics for rows that still have a live side.

### 3. Desktop — `DesktopWeatherRepository.persistForecastResult` → `weatherDao.upsertForecasts`

Same gate before `upsertForecasts` (the `date >= today` filter stays). Log
`FORECAST_SKIP_HINDCAST` at INFO (sparse — one line per dropped day/source/batch).

`ForecastOverlaySettle` becomes simpler over time: post-extreme batches will no longer exist for
new data. **Keep settle as-is** for historical rows already in the DB (and for a day whose extreme
time is only known later than the fetch).

### 4. Existing junk (optional, second step)

One-time scrub or leave historical rows and let settle/accuracy ignore them. Recommend **leave
history, fix the writer first**; a scrub can follow if accuracy numbers look dirty.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | `filterDaily`: both sides past → both null; both within 1 h → both null | unit |
| 2 | `filterDaily`: high past, low future → high null, low kept; symmetric | unit |
| 3 | `filterDaily`: both future beyond 1 h → unchanged | unit |
| 4 | `filterDaily`: targetDate tomorrow → never gated by today's clock | unit |
| 5 | Extreme-time cascade: observed `computedHighAt` wins over predicted hour | unit |
| 6 | Cascade fallback: day high after 20:00, night low after next 08:00 | unit |
| 7 | Android save: 22:25 batch after 16:15/05:15 extremes not inserted (Oct 3 shape) | Robolectric |
| 8 | Android save: 15:15 batch before 16:15 high **is** inserted | Robolectric |
| 9 | Desktop `persistForecastResult` same two cases | integration |
| 10 | Accuracy / settle regression: pre-extreme batches still recorded and settleable | existing + unit |

Mutation check each.

## Open questions

1. **Per-side null-out vs drop-whole-row** when only one extreme has passed. Recommended: per-side
   (NWS parity). Confirm.
2. **Is the 1-hour grace "don't store if due within 1 h" or "don't store if passed more than 1 h
   ago"?** Reading above: the last *useful* forecast is ≥ 1 h before the extreme, so **stop storing
   at `extremeTime`** and treat `[extremeTime, extremeTime+1h]` also as junk only if you want a
   stricter "within 1 hour" early freeze. The 2026-10-04 quote supports storing until the extreme;
   the new "within 1 hour" wording is ambiguous. Default in this plan: **store while
   `now < extremeTime`**, drop when `now >= extremeTime` (grace only for *unknown* times).
3. Hourly past-hour rows that still land in `hourly_forecasts` (should already be history) — verify
   with a log, or trust `ElapsedForecastBackfill`?
4. Scrub existing hindcast rows from `forecasts`, or leave them?

## Implementation notes

- Dual-platform parity is mandatory (`:shared` gate + Android + desktop).
- Do not break `ForecastOverlaySettleTest`'s historical fixture (the 22:25 row) — that test builds
  rows directly in the DAO; the gate lives in the **writer**, so it stays green.
- `deleteForecastsInBucket` / evolution history automatically stop showing post-extreme points once
  they are never written.
- Prefer one shared name for the concept (`hindcast`) already used in `ElapsedForecastBackfill`
  docs so grep finds all the fences.
