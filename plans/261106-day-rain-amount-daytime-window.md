# Day rain amount: sum the 8am–8pm hours, not the calendar day

## Evidence (desktop, Silurian, 37.417/−122.089, fetched 2026-11-06)

Fri 2026-11-13 showed `.26in` (day) and `.24in` (night).

| Window | Amount |
|---|---|
| Daily row `precipAmountMm` (00–24) | 6.64 mm = 0.262 in → day label |
| Hourly 08–20 | 2.60 mm = 0.103 in |
| Hourly 20–08 | 6.10 mm = 0.240 in → night label |
| Overlap 20–24 | 3.70 mm = 0.146 in (counted in both) |

Hourly 00–24 sums to exactly the daily row, so the provider is consistent. The labels are not.

## Root cause

`DailyColumnBuilder` (shared; Android `DailyViewLogic` and desktop `DesktopDailyForecastModel`)
gives `buildDailyRainLabel` the daily row's midnight-to-midnight total, while the night label uses
`DailyRainLabels.nightForecastAmountMm` (8pm–8am). The 8pm–midnight rain lands in both.

## Fix

1. `DailyRainLabels`: factor the night sum into a private window sum; add
   `dayForecastAmountMm` (08:00–20:00, same rules: fully covered window, even step, every hour has
   an amount, display source only, no GENERIC_GAP).
2. `DailyColumnBuilder`: today/future day label uses `dayForecastAmountMm`. With no complete
   daytime hours it shows the chance % instead of the whole-day total. Past days are unchanged
   (they show the measured day/night split).
3. Tests: `DailyRainLabelsTest` (day window sum and coverage cases) and a column test copying the
   Nov 13 shape, asserting `.1in` day / `.24in` night. It must fail on the old code (`.26in`).
4. Verify: unit tests; desktop restart and check Fri label; Android widget via emulator.
