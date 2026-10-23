# Daily bar: noon cloud % decides the sky; soft gold/grey edge

## Why
The bar split (`:shared` `AdaptiveBarStrokes.plan`, `WeatherColors.resolveMixedBarSplit`) reads
the icon, "mixed" icon sets, a cloud ratio guessed from the icon, rain/chance-rain icon branches,
the bar's own icon-derived colour and an exception for grey. Most of it exists because the icon and
the cloud figure disagree (Google today: "Clear", noon cloud 91 %). User 2026-10-23: just the noon
cloud %.

## Rule
1. **Rain first** (user): daytime rain % ≥ 60 → blue bottom = rain %, the cap above it the sky
   (gold over grey by noon cloud %), as `rainDaySplit` today. Rain % from the row only, no icon.
2. **Otherwise** gold top = 100 − noon cloud %, grey bottom = noon cloud %. 0 % → all gold,
   100 % → all grey.
3. **No noon cloud %** (e.g. Google day 9, Sat 2026-10-31, past the 192 h download): one fallback —
   the provider's daily condition word as a cloud % (clear 0, mostly clear 20, partly cloudy 50,
   mostly cloudy 80, cloudy/overcast/fog 100; `cloudRatioFromIcon`-style table on the condition).
   Nothing → solid grey ("unknown"). Bar and icon then agree on the far day.
4. The icon no longer decides the bar. Gone: `isMixed` gate, `forecastColor` as the split's top,
   `splitTopArgb`'s grey exception, the icon cloud-ratio fallback, rain-icon branches.
5. Unchanged: today's "yesterday's forecast" bar keeps its bright-yellow top (not history — it is
   today's left bar); past days' bars untouched.

## Soft edge (user: "the line between grey and gold is rather harsh")
A real gradient shader was used until 2026-04-17 and replaced by hard segments because Samsung
launchers flattened it (`6deaeece`). So: no shader — the shared plan emits a short blend band of
solid strokes stepping gold → grey (e.g. 6 steps over ~6 dp, centred on the edge, clamped inside
the bar). Same for gold → grey → blue edges on rainy bars. Both platforms draw it unchanged.

## Tests
`:shared` `AdaptiveBarStrokesTest`: 91 % → 9 % gold; 0 / 100 %; rain ≥ 60 with and without cloud;
null cloud + condition fallback; null + nothing → solid grey; blend steps monotonic and inside the
bar. Existing Android/desktop renderer tests, `CrossPlatformDuplicateNamesTest`. Desktop restart +
screenshot; Android emulator screenshot.

## Amendments (user, 2026-10-23, same day)
- **Any rain chance is blue** — the 60 % gate is gone; a 20 % day is 20 % blue under its sky cap.
- **"Yesterday's forecast" bar takes its own rain chance** (`AdaptiveBarStrokes.priorBarRainPercent`):
  today the picked prior row's daytime chance, else its whole-day one; a past day (no prior chance
  kept) the day's own, as it already takes the day's icon. Without it a forecast of rain under heavy
  cloud drew mostly grey.
- The fallback reads the **icon name**, not the raw condition word: Android's `DayData` carries only
  the icon, and with no noon cloud % the icon is resolved from that word anyway
  (`WeatherConditionResolver.skyCloudRatio`).
- Dead code removed: `WeatherColors.MixedBarSplit` / `mixedBarSplit` / `rainDaySplit` /
  `segmentEdges` / `resolveMixedBarSplit` / `shouldDrawAdaptiveBar` / `splitTopArgb`; Android
  `WeatherConditionColors.forecastBarGradient` / `resolveMixedBarSplit` / `cloudRatio` (shader
  unused since 6deaeece) and their tests (`MixedBarSplitTest`, `WeatherConditionColorsRobolectricTest`).
