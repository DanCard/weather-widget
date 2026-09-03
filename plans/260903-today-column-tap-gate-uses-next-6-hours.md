# Today-column tap gate: next-6-hour rain chance, not the whole-day chance

**Date:** 2026-09-03
**Status:** proposed

## Problem

A main-column tap on the daily view routes to the precipitation graph when

```
isRainIndicator(icon) AND precipProbability >= DAILY_CLICK_PRECIP_THRESHOLD (16)
```

For **today**, `precipProbability` is `dayData.rainData.dailyPrecipProbability` — which is
`DailyRainLabels.resolveDailyLabelPrecip(...).dayPrecip`, the max chance across today's whole
*daytime* window (falling back to the daily field). It does not care how much of that window is
already in the past.

So at 06:00, a 40% chance at 17:00 sends the tap to the precipitation graph — but the graph the tap
opens is `ZoomStage.WIDE` centred on now, i.e. **now−12h .. now+6h**, which ends at 12:00 and shows
no rain at all. The user lands on a flat precipitation graph and has to zoom or pan to find the
rain that justified the routing.

The widget header already disagrees with the gate: `DailyHeaderResolver` displays
`HeaderPrecipCalculator.getNext8HourPrecipProbability(...)` — a *rolling* window — while the tap
routes off the whole-day figure. Two different numbers, one column.

## Change

For **today's column only**, feed the routing gate the maximum minute-interpolated chance over
**[now, now + 6h)** instead of the whole-day figure. 6h is deliberate: it is exactly
`ZoomStage.WIDE.window().forwardHours`, so the gate asks about precisely the forecast the tap is
about to put on screen.

Every other day keeps `dayPrecip` — "next 6 hours" is undefined for Friday.

### Scope: which taps actually change

| Zone | Gate today | Changes? |
|---|---|---|
| `graph_dayN_zone` (main column) | `isRainIndicator(icon) AND prob >= 16` | **yes** — `prob` becomes the 6h rolling max |
| `graph_bottom_dayN_zone` (icon band) | icon-home only, no probability | no |
| `graph_night_rain_zone_*` | forced `PRECIPITATION` | no |
| past days | forced `TEMPERATURE` before the icon is read | no |

### Decisions

1. **Metric = max**, not mean and not combined-probability — same as the header's next-8h value, so
   the two numbers are computed by one code path and can only differ by window length.
2. **Threshold stays `>= 16`.** The ask changes the *window*, not the boundary; `>= 16` and `> 16`
   differ only at exactly 16, and `DAILY_CLICK_PRECIP_THRESHOLD` is shared with desktop.
3. **The icon still gates.** `isRainIndicator(icon)` is unchanged and still derived from the whole
   day, so this change can only ever route *fewer* taps to precipitation, never more. A clear-icon
   today with rain arriving in 4h still opens the temperature graph — deliberately out of scope
   here; call it out if that case matters.
4. **Fallback = today's `dayPrecip`.** No hourly rows for the display source ⇒ the gate behaves
   exactly as it does now, so a source with no hourly coverage does not silently lose its
   precipitation routing.
5. **Shared, not Android-only** — desktop's `dayClickConfig`/`handleDayClick` run the same resolver.

## Implementation

### `:shared`

- `PrecipProbabilityCalculator`: extract the hard-coded `LOOKAHEAD_HOURS = 8` into a parameter.
  Add `maxPrecipProbabilityWithin(lookaheadHours, ...)` holding the existing body; keep
  `getNext8HourPrecipProbability(...)` as a delegate at 8 so the header path is untouched.
- `DayClickResolver`: add

  ```kotlin
  const val TODAY_LOOKAHEAD_HOURS = 6L   // == ZoomStage.WIDE.window().forwardHours

  fun routingPrecipProbability(
      targetDay: LocalDate, now: LocalDateTime,
      hourly: List<HourlyForecast>, displaySourceId: String, fallbackSourceId: String,
      dailyProbability: Int?,
  ): Int?
  ```

  Returns the 6h rolling max when `targetDay == now.toLocalDate()`, else `dailyProbability`.
  `resolveView` itself is unchanged — only what is fed to it.

### Android

- `DailyClickHandlerFactory.buildDayClickIntent` / `setupGraphZoneClickHandlers`: accept
  `hourlyForecasts` + `displaySource`, and call `routingPrecipProbability` instead of passing
  `dayData.rainData.dailyPrecipProbability` straight through.
- `DailyGraphRenderer` (`:360`, `:372`): pass `ctx.hourlyForecasts` and `ctx.displaySource` — both
  already on `DailyRenderContext`.
- `NightRainGridMapper` needs no change (forces `PRECIPITATION`).
- Extend the `CLICK_DAILY` log line with the value actually used and its provenance, e.g.
  `precipGate=34(rolling6h)` vs `precipGate=40(daily)`, so the DB shows why a tap routed as it did.

### Desktop

- `Main.kt` `handleDayClick` and `dayClickConfig`: replace
  `clickedDay?.forecast?.precipProbability ?: clickedDay?.snapshot?.precipProbability` with the same
  shared call, fed from `snapshot.raw.hourly` (already in scope at both sites).
- `onDayClickAudit` line gains the same provenance suffix.

## Tests

- `DayClickResolverTest` (shared, new cases)
  - rain at now+3h ⇒ `PRECIPITATION`; the *same* day with that rain moved to now+8h ⇒ `TEMPERATURE`
    (the case that motivates the change, and it must fail against the current code)
  - boundary: 15 ⇒ temperature, 16 ⇒ precipitation
  - `targetDay != today` ignores hourly entirely and uses `dailyProbability`
  - empty hourly ⇒ falls back to `dailyProbability`
  - hourly present but only for another source ⇒ falls back via `fallbackSourceId`, then daily
- `PrecipProbabilityCalculatorTest` — lookahead parameter honoured; 8h delegate unchanged.
- `DailyViewHandlerIntentContractTest` (Robolectric, Android) — build a today intent with hourly
  rows placing rain outside the 6h window and assert `EXTRA_TARGET_VIEW == TEMPERATURE`; inside it,
  `PRECIPITATION`. Two classes in play (`DailyClickHandlerFactory` + `DayClickResolver`), so this is
  the integration-level coverage.
- `DailyForecastGraphTapZoneTest` / `DesktopUiTest` — desktop reaches the identical verdict from the
  same inputs, guarding parity.

## Verification on device

After install, tap today's column and check `app_logs`:

```sql
SELECT datetime(timestamp/1000,'unixepoch','localtime'), message
FROM app_logs WHERE tag='CLICK_DAILY' ORDER BY timestamp DESC LIMIT 10;
```

The new `precipGate=` field should show the rolling value, and on a morning where rain is forecast
only for the evening the tap should now land on the temperature graph.
