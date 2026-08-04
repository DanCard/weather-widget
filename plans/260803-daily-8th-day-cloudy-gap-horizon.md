# 8th day renders "cloudy" — gap-fill horizon narrower than the render horizon

## Symptom

On the emulator (10-column daily widget, display source NWS, today = 2026-08-03), the last
column — Tue 2026-08-11, i.e. today+8 — renders a **grey cloud icon on a slate-grey bar**, while
still showing the climate-normal temperatures 80.5° / 58.6°.

Scrolling left/right repaints it correctly (green climate-normal bar + clear icon); after a while
it reverts to the grey cloud. Reproduced on the Samsung too (correct, then reverts, ~7 min).

## Root cause

Two independent horizons disagree:

| Path | Forecast query end | `appendGaps` horizonDays |
|------|--------------------|--------------------------|
| `DailyInteractionRenderer` (scroll/tap) | `today+30` | `DAILY_FORECAST_DAYS = 30L` |
| `WidgetStartupCoordinator.loadStartupData` | `today.plusDays(7)` | **`7L`** |
| `WeatherWidgetWorker.fetchForecastSnapshots` | `today.plusDays(7)` | **`7L`** |

The daily render horizon is **width-derived**, not 7: `numColumns = dimensions.cols` via the
uncapped `columnsForWidthDp`, and at offset 0 the render reaches `today + numColumns - 2`. The
10-column widget therefore draws today+8, one day past the startup/worker window.

So on the startup/worker paths the today+8 column gets **no row at all** — neither a real NWS row
(NWS coverage ends today+7) nor a `GENERIC_GAP` climate-normal row (blocked by `horizonDays = 7L`):

1. `weather == null` → `DailyForecastIconResolver.resolveIcon` returns `ic_weather_unknown` at its
   first line — a grey cloud (`#B0BEC5`), flattened to a plain cloud by the daily icon tint.
2. `WeatherConditionColors.forecastColor(isSunny=false, isRainy=false, isMixed=false)` falls to
   `else -> FORECAST_CLOUDY` `#8E99A4` → slate-grey "cloudy" bar.
3. The temperatures still look right because `DailyViewLogic` has a *second*, independent
   climate-normal path: the `climateNormals` MonthDay map fills `finalHigh/finalLow` when a future
   day has no row (`DailyViewLogic.kt:507`). Hence correct normals wearing a fake "cloudy" costume.

The interaction path's 30-day horizon supplies the `GENERIC_GAP` row, so `isSourceGapFallback`
paints the green `COLOR_GAP_FALLBACK` `#34C759` bar and the icon resolves to `ic_weather_clear`.
That is the scroll-fixes-it / worker-reverts-it flip-flop.

Verified live on the emulator:

```
# bad (stale render)   bar pixel = srgba(142,153,164) = #8E99A4 FORECAST_CLOUDY
# good (after repaint) bar pixel = srgba(52,199,89)   = #34C759 COLOR_GAP_FALLBACK
DailyViewLogic: prepareGraphDays: index=9 date=2026-08-11 weather=true forecast=false
DailyGraphRenderer: graphDay col=10 date=2026-08-11 iconRes=2131165312 iconName=ic_weather_clear
```

DB confirms no NWS row and no `Generic` hourly for 2026-08-11; `climate_normals` 08-15 = 80.5 / 58.7,
matching the rendered 80.5° / 58.6° exactly.

### Second bug the same mismatch causes

The startup/worker forecast query also ends at `today+7`, so for a **long-range display source**
(Open-Meteo reaches today+16, Silurian today+15) a wide widget's day-8+ columns drop the *real*
forecast rows on those paths and fall back to climate normals — not just NWS.

## Plan

1. Add a single shared horizon constant to `WidgetQueryWindows` (`DAILY_FORECAST_DAYS = 30L`), the
   value the interactive path already proves is affordable, and document that it must cover the
   width-derived render horizon.
2. `WidgetStartupCoordinator.loadStartupData`: `horizonEnd` and both `appendGaps` /
   `appendGapsToSnapshots` calls use the shared constant instead of `7`/`7L`.
3. `WeatherWidgetWorker.fetchForecastSnapshots`: `recentEnd` and `appendGapsToSnapshots` likewise.
4. `DailyInteractionRenderer.DAILY_FORECAST_DAYS` delegates to the shared constant so there is one
   source of truth.
5. Leave `historyStart = today.minusDays(1)` on the startup path alone — only the future horizon is
   implicated in this bug.

## Verification

- Unit test in `ClimateGapFillerTest`: with real rows covering only today..today+7, a fill at the
  render horizon emits a `GENERIC_GAP` row for today+8 (fails at `horizonDays = 7L`).
- Unit test that the startup/worker horizon constant is >= the widest realistic daily column count,
  so the two horizons cannot silently diverge again.
- Emulator: force the startup/worker repaint (`cmd jobscheduler run -f com.weatherwidget <id>`) and
  confirm the today+8 column stays green + clear across repaints, no grey cloud.

## Phase 2 — collapse to one climate-normal path (decided 2026-08-03: do it now, separate commit)

The duplicate mechanism is what let this fail *quietly* — correct numbers, wrong styling — so
leaving it in place would make the Phase 1 fix unverifiable by eye and hide the next regression.

Deciding fact: `ClimateNormalsRepository.warmBestEffort` is already called on **every** network
fetch (`ForecastRepository.kt:206`), so the `climate_normals` cache is warmed independently of the
render. The MonthDay map's only unique capability — fetching on a cache miss — is therefore
redundant, and `DailyViewHandler.kt:176` is doing potential **network I/O on the render path**.

1. Drop the `climateNormals` fallback in `DailyViewLogic` (`:507` graph path and `:247` text path)
   and the `climateNormals` parameter threaded through `prepareGraphDays` /
   `prepareGraphDayInputs`.
2. Drop `DailyViewHandler.kt:176`'s `repository.getHistoricalNormalsByMonthDay(lat, lon)` call —
   removes an HTTP call from widget render.
3. Derive the overlay-paint flag from the gap row instead of `isClimateOverlay`:
   `weather?.isClimateNormal == true`. `isClimateNormal` and `isSourceGapFallback` then express the
   same condition — keep one (`isSourceGapFallback`) and delete the other.
4. `GENERIC_GAP` rows become the single way climate normals reach the daily view.

Behavior change to accept: with a cold normals cache and no fetch yet, a beyond-coverage future day
renders as genuinely absent instead of showing fabricated normals. That matches the stated product
rule ("no data, so show climate normals" — and no normals means no data).

Also worth fixing while here: `DailyForecastIconResolver`'s `WeatherSource.GENERIC_GAP -> null`
branch is dead code — gap rows carry `nativeDailyIconToken = null`, so the `isNotEmpty()` guard at
`:101` skips the native block and `condition = "Historical Avg"` falls through the generic mapper's
`else` to `IC_CLEAR`.

## Status

Phase 1: in progress. Phase 2: planned, same session.
