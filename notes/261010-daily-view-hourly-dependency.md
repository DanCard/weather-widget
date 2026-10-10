# Does the daily forecast view still need hourly data?

2026-10-10. Checked `DailyViewLogic.kt` (Android) and `DesktopDailyForecastModel.kt` (desktop).
Context: `performance/261010-hourly-near-window-often-full-eight-days-daily.md` (NEAR/FULL hourly
fetch plan) and `performance/261010-daily-view-summaries-instead-of-far-hourly.md`.

## Answer

**Not fully.** The daily view needs no hourly row for days past 72 h: far-day noon cloud and day/night
rain % come from the `forecasts` row. But the hourly list is still passed into the render and read in
five places.

## What the daily render reads from hourly

| # | Use | Days | Status |
|---|---|---|---|
| 1 | Rain-start summary (`RainAnalyzer.getRainSummary`): "rain at 3pm" text and `hasRainForecast` | today … +2 only (`nearTermLimit`) | **Needs hourly** (hour-level by nature) |
| 2 | Today's triple-bar values (`DailyActualsEstimator.calculateTodayTripleLineValues`, `DailyTodayResolver`): remaining-day forecast high/low | today | **Needs hourly** |
| 3 | Past-day dashed right-bar fallback (`dayHourly` → `PastDayForecastOverlay.forecastHourlyTemps` / hindcast) | past days, only when no stored value | **Needs hourly** (from history) |
| 4 | Day/night rain % (`DailyForecastIconResolver.resolveDailyLabelPrecip`) | all | Row first; hourly only a **legacy fallback** |
| 5 | Noon cloud (`resolveMeasuredNoonCloudCoverPercent`) | all | Row first; hourly only a **legacy fallback** |

Desktop is the same list: `hourly = forecast.raw.hourly` goes into the model, with the row-first chain
for cloud and rain.

## What can be removed

- **#4 and #5**, once rows written before Room 78 / desktop 31 age out. Forecast rows are kept 30
  days, so about **2026-11-09**. After that the hourly fallback in both chains is dead code. Exception:
  climate-normal (GENERIC_GAP) days still read hourly (noted in the 261010 summaries plan).
- **#1–#3 cannot go**: they need hours, not a per-day number.

## Consequence for the NEAR/FULL plan

The daily view's real hourly dependency is **today through +2, about 72 h**, plus past days. A NEAR
window shorter than that leaves two daily-view items up to ~24 h old between FULL runs:

- the rain-start summary for tomorrow and the day after;
- tomorrow's and day+2's day/night rain %, carried forward from the last FULL run.

Options, in order of preference:

1. **NEAR = 72 h, FULL = 8 days.** The daily view stays exactly as fresh as today. Saving is only
   the far-day summaries (FULL once a day) and Google's pages beyond 72 h.
2. **NEAR = 48 h.** Today and tomorrow stay fresh; day+2's rain summary may be up to a day old. Keeps
   most of the write savings.
3. **NEAR = 24 h.** Biggest saving, but tomorrow's rain text and % can lag a day.

Decision pending (user).
