# Rain-day daily bar: grey cap over blue, blue = daytime rain %

## Problem

Emulator, Silurian, daily view, Wed 10/21: `condition=Rain`, daytime precip 85 %, noon cloud 99 %.
The icon is the full rain icon and `isBarRainy` passes (85 ≥ 60), yet the bar is grey.

Cause: any day with a cloud ratio takes the adaptive split (`shouldUseAdaptiveSegments`). The split's
bottom is blue only for a *chance-of-rain* icon (`isChanceOfRainIcon`); `IC_RAIN` is not one, so the
bottom (99 % of the bar) is cloud-grey, and the top 1 % is drawn in the bar's own colour — blue.
Effectively inverted. Since noon cloud is on every row (Room 78), almost every rain day hits this.
Desktop shares `WeatherColors.resolveMixedBarSplit` and has the same gap.

## Decision (user, 2026-10-19)

A full precipitation icon (rain / storm / snow) whose daytime % clears `isDaytimeRain` (≥ 60, or
unknown) draws **grey on top, blue below** — no gold:

- blue height = daytime precip % (Wed: 85 % blue, 15 % grey cap) — matches the % label above it;
- unknown % (snapshot bars pass null) → blue height = the cloud ratio, as before.

Chance-rain icons (8–79 %) keep gold over blue/grey: their icon shows sun.

## Change

- `:shared` `WeatherColors.MixedBarSplit` gains `topOverridesBar: Boolean` (default false). The
  ordinary split keeps drawing its top in the bar's own colour (snapshot yellow etc.); a rain-day
  split's grey top must win over the bar's blue.
- `resolveMixedBarSplit`: precipitation icon + `isDaytimeRain` → `rainDaySplit`.
- Android `DailyBarRenderer.drawWeatherAdaptiveBar` and desktop `drawAdaptiveBar` paint the top in
  `split.topColor` when `topOverridesBar`.

## Tests

- `MixedBarSplitTest`: rain icon + 99 % cloud + 85 % day → grey top, blue bottom, ratio 0.85;
  rain icon + null % → ratio = cloud; rain icon + 15 % day → unchanged grey split.
- Visual check on the emulator (Silurian) and desktop.
