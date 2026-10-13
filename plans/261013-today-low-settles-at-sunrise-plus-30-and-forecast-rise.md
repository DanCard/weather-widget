# Today's low settles at sunrise + 30 min, once the forecast only rises

## Problem

The today column's low label switches from `min(observed, forecast)` to the observed actual at a
fixed **09:00** (`DailyDayValueResolver.ACTUAL_LOW_CUTOFF_HOUR`, `:shared`, both platforms).

- The cutoff only changes the printed number when the night was **warmer** than forecast. A colder
  night already wins through the `min` and recolors (`isLowLabelActual`). So on a warm night the
  stale forecast low stays on screen for hours after the real low happened at dawn.
- Sunrise moves about 2 h across the year (Mountain View: ~05:50 PDT in June, ~07:20 PST in
  December), and also with DST and latitude. A fixed clock hour is late in summer and only about
  right in winter.
- A cold front can bring the day's real low in the evening. Both the 9 am rule and a plain sunrise
  rule would mark the morning low settled too early.

## Rule (user, 2026-10-13; revised the same day)

Today's low is **settled** when an observed low exists (`actualLow != null`, unchanged) and either:

1. **Sunrise + 30 min, forecast rising:** `now ≥ sunrise + 30 min`, and no hourly forecast in the
   next **3 h** is below the observed low. Sunrise uses the standard zenith 90.833°, not the 96°
   civil dawn that `SunPositionUtils.getSunTimes` returns (about 25 min earlier). A tie counts as
   settled, and so does having no forecast hours in the window.
2. **09:00, absolute:** settled whatever the forecast does. This is also the only cutoff when
   there is no sunrise to use (no location, polar day/night) or sunrise + 30 min falls after 09:00.

**Why only 3 h** (revision, prompted by a side-agent review): the first draft checked every hour up
to midnight. After a warm night the evening is routinely forecast below the morning low, as
temperatures fall toward tomorrow's low, so the low would have stayed unsettled all day on exactly
the mornings this change is for. The 3 h window only asks "is the morning still falling?", and
09:00 caps the wait.

Example for today (2026-10-13, Mountain View 37.417, −122.089, PDT): sunrise 07:14, so the low can
settle from **07:44**, and by 09:00 at the latest.

Time zones: sunrise is computed as an `Instant` and converted into the zone the caller's "now"
uses, which is the device zone. There is no stored location zone. For a location pinned in another
zone, that site's sunrise may land on another device-local date; the 09:00 cap still holds.

## Design

`:shared` only. Callers pass inputs and do no reasoning of their own, so both platforms print the
same number.

- New `TodayLowSettle` in `shared/.../util/`:
  - `sunriseCutoff(date, lat, lon, zone)` returns sunrise + 30 min, or null. Sunrise comes from a
    new `SunPositionUtils.sunriseInstant(date, lat, lon)` (zenith 90.833, an `Instant`). The
    existing 96° path, which day/night shading uses, keeps its exact output; it now shares the
    equation's core.
  - `decide(now, lat, lon, zone, actualLow, todayHourly, epochMs, temperature): Decision`
    applies the rule above (`LOOKAHEAD_HOURS = 3`, `ABSOLUTE_CUTOFF_HOUR = 9`).
- `DailyDayValueResolver.effectiveLowForLabel` replaces `nowHour: Int?` with
  `lowSettled: Boolean = false`. `ACTUAL_LOW_CUTOFF_HOUR` is deleted. `nowHour` stays on desktop
  `DesktopDailyDay` because the 17:00 high cutoff still uses it; `lowSettled` sits beside it.
- Callers:
  - Android `DailyTodayResolver` (line ~114) already has `hourlyForecasts`, `now` and the
    location. It computes `lowSettled` there.
  - Desktop `DesktopDailyForecastModel` (line ~538, where `nowHour` is set today) replaces
    `nowHour` with `lowSettled` on `DesktopDailyDay`. `DailyForecastGraph.lowForLabel()` and the
    two other `nowHour` uses (lines ~341, ~454) read it.
- Logging: the first paint after the decision flips logs one
  `TODAY_LOW_SETTLED settled=… reason=SETTLED|SETTLED_AT_NINE|BEFORE_CUTOFF|NO_ACTUAL|FORECAST_FALLS
  cutoff=HH:mm from=sunrise|nine actualLow=… lookaheadMin=…`. Repeat paints log nothing.

Out of scope: `ACTUAL_HIGH_CUTOFF_HOUR` (17:00) stays as it is. A solar-noon + ~3 h version could
follow the same pattern later.

## Tests (implemented)

- `:shared` `TodayLowSettleTest` (16 tests):
  - Mountain View cutoffs on 2026-10-13 (07:44), both solstices, and both DST change days,
    against published sunrise times ±3 min. Auckland's sunrise lands on the previous UTC day.
  - No location, or polar night, falls back to 09:00. Reykjavík in December (sunrise ≈ 11:20)
    is capped at 09:00.
  - Before the cutoff: not settled. After it, with a rising forecast: settled.
  - A dip inside the 3 h window blocks settling. A cold evening outside the window doesn't.
    09:00 settles regardless. A tie settles, an hour already passed doesn't count, an empty window
    settles, and with no actual it never settles.
- `DailyDayValueResolverTest`: the `nowHour` cases are ported to `lowSettled`.
- Android integration test `DailyTodayResolverLowSettleTest`, through the real
  `DailyTodayResolver` → `TodayLowSettle` → `effectiveLowForLabel` chain:
  - A warm night prints 47 (forecast) before the cutoff and 50 (observed) after it.
  - A dip in the next 3 h holds 47 until 09:00.
  - A cold evening forecast doesn't hold it.
- Desktop uses the same `TodayLowSettle.decide` and `effectiveLowForLabel`; it compiles and the
  existing desktop suite passes.

## Docs

The `effectiveLowForLabel` / `isLowLabelActual` KDoc and the renderer comments that said "9am" are
updated. CLAUDE.md never described the low cutoff, so it is unchanged.
