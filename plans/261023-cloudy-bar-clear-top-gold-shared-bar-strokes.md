# Cloudy daily bar: clear top in gold; one shared stroke plan

## Problem (2026-10-23, desktop, Google)
Today's live-forecast bar was solid grey. The newest Google row had `noonCloudPercent = 91`,
20 % rain by day → cloud icon → bar colour `FORECAST_CLOUDY`. The two-segment cloud split painted
its 9 % clear top "in the bar's own colour" (only a rainy split's gold overrode it), so grey on a
grey bottom. The snapshot bar beside it, same 91 %, showed its 9 % in snapshot yellow.

The split → strokes logic (gate, colours, segment ends, draw order) was written twice, in
Android `DailyBarRenderer.drawWeatherAdaptiveBar` and desktop `DailyForecastGraph.drawAdaptiveBar`,
with small drifts (desktop had no `iconName == null` / `isMixed || cloud > 0` / ≤ 1 px gates).

## Change
- `:shared` `WeatherColors.splitTopArgb`: the top is the bar's own colour, except gold over a rainy
  split (as before) **or a cloud-grey bar** (RGB compare; keeps the bar's alpha).
- `:shared` `AdaptiveBarStrokes.plan(icon, cloud, day %, barArgb, highY, lowY, inkRadius)` → the
  ordered strokes (colour + clip bottom). Both renderers only stroke it.
- Desktop now takes Android's gates: no icon → solid; not mixed and no cloud share → solid; a bar
  ≤ 1 px → solid. A today snapshot bar with no snapshot icon is therefore solid, as on Android.

## Tests
`:shared` `AdaptiveBarStrokesTest` (the 91 % case fails on the old rule: top was grey); existing
`MixedBarSplitTest`, Android `DailyForecastGraphRenderer*`, `WeatherConditionColorsTest`, desktop
`DailyForecastGraph*`, `CrossPlatformDuplicateNamesTest`.
