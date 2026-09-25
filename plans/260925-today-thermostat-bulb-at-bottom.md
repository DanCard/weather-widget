# Today thermostat: bulb must sit at the bottom of the mercury

## Symptom
Pixel 7 Pro, daily view, Today column: the pink bulb is drawn at the top of the mercury, with the
stem hanging down below it.

## Evidence (logcat 2026-09-25 06:08)
```
TODAY_BAR_DEBUG widget=88 obsHigh=45.686478 obsLow=50.3 fHigh=57.9 fLow=50.3 ...
DailyEstimator: source=OPEN_METEO actual.low=null currentTemp=45.686 solidLineHigh=45.686 (current_temp)
                solidLineLow=50.3 (forecast_low)
DailyEstimator: source=SILURIAN   actual.low=46.94 currentTemp=46.233 solidLineHigh=46.233 solidLineLow=46.94
```
The mercury "high" (current temp) is **below** its "low" on both widgets:
- Open-Meteo has no observed low, so `solidLineLow` falls back to the forecast low (50.3), which the
  pre-dawn current temp (45.7) is already under.
- Silurian's recorded actual low (46.94) lags the current temp (46.23), which is newer.

The renderers put the bulb at `solidLow`'s y, assuming low ≤ high. When they are inverted, "low" is
the top of the drawn line, so the bulb sits on top.

## Fix
1. **Data (Android `DailyActualsEstimator`)**: the mercury's bottom is the coldest temp so far, so
   `solidLineLow = min(actual/forecast low, currentTemp)` when currentTemp exists. Leave
   `hasActualLow` / low-source semantics (and so label color) unchanged — currentTemp still does not
   make the low "observed". The low *label* keeps using `effectiveLowForLabel`, untouched.
   Desktop: apply the same clamp where its `solidLow` is built.
2. **Rendering (Android `DailyBarRenderer.drawTodayTripleBar` + desktop `DailyForecastGraph`)**:
   defence in depth — bulb anchors at the bottom of the drawn stem (`max(y(high), y(low))`) regardless
   of value order.
3. **Tests**: estimator test for currentTemp < forecast low and currentTemp < actual low; renderer
   Robolectric test asserting the bulb center y ≥ the stem's bottom y with inverted input.
4. Build, install on the Pixel, screenshot; restart desktop.

## As implemented (deviation from step 1)
Clamping `solidLineLow` in the estimator would also have changed the low *label* value (it feeds
`effectiveLowForLabel`). Instead the clamp is geometry-only: shared
`DailyDayValueResolver.mercuryBottom(solidHigh, solidLow) = min(...)` drives the Android stem/bulb
(`DailyBarRenderer`), the desktop stem/bulb/today-bar extent (`DailyForecastGraph`), and — via a new
`solidHigh` param — `iconAnchorLow`, so the weather icon sits below the lowered bulb. Verified on the
Pixel 7 Pro (widget 88, Open-Meteo, obsHigh=45.6 obsLow=50.3): bulb now at the bottom, above the icon.
