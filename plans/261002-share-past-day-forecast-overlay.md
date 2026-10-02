# Share the past-day forecast overlay selection

## Problem

Which old forecast a past day's yellow overlay bar draws is decided in three places:

| Where | Rule (after the frozen `daily_history.forecastHigh/LowTemp`, which all three prefer) |
|---|---|
| Android `DailyPastDayResolver.resolvePastDayOverlay` (graph) | newest row with high AND low, display source, not a climate normal; else none |
| Android `DailyViewLogic.kt:167` (text-only layout, inline copy) | same as above |
| Desktop `DesktopDailyForecastModel.buildDay` (inline) | newest with high AND low AND high ≠ low; else newest with high OR low; else the latest-batch `forecast` row |

Desktop's snapshot list excludes the newest fetch batch (`getDailyForecastSnapshots`), which is why it
needs the third fallback; Android's list includes it.

## Evidence (desktop DB, 2026-10-02)

98 past days across 5 sources: 74 use the frozen overlay; of the rest, 22 pick the same row under
both rules and **2 differ**, both NWS:

| Day | Android | Desktop |
|---|---|---|
| 2026-09-02 | 74/74 (collapsed row → zero-height bar) | 74/56 |
| 2026-09-03 | 74/74 | 74/57 |

So the observed divergence is the high ≠ low filter, and desktop's rule is the better one there. The
partial and latest-batch fallbacks never changed a result.

## Fix

One `:shared` `PastDayForecastOverlay.select(frozenHigh, frozenLow, candidates)` returning the
overlay high/low (or null), used by all three call sites.

Rule (user chose this hybrid, 2026-10-02):
1. Frozen `daily_history` overlay when both values are present.
2. Newest candidate with high AND low AND high ≠ low (desktop's collapse filter, adopted on Android).
3. Newest candidate with high AND low, even if collapsed (so a day whose only rows are collapsed
   still draws what it has, rather than nothing).
4. Otherwise none — no partial rows, no latest-batch row standing in for a past forecast.

Each platform maps its rows to a minimal candidate (high, low, fetchedAt) and filters source /
climate normals before calling. Desktop passes its older-batch snapshots plus the latest-batch row
for that date, so the candidate set matches Android's.

## Tests

- `:shared` `PastDayForecastOverlayTest`: frozen wins; collapsed newest skipped for older real range;
  only collapsed rows → collapsed; partial-only → none; empty → none.
- Android: existing `DailyPastDayResolver` / `DailyViewLogic` tests stay green; add one for the
  collapsed-row case through `resolvePastDayOverlay`.
- Desktop: a `DesktopDailyForecastModel` test where only a partial row exists → no overlay.
