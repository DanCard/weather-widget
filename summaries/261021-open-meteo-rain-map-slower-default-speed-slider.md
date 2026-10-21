# Open-Meteo rain map: slower default speed, speed slider

Plan: plans/261021-open-meteo-rain-map-slower-default-speed-slider.md

- `:shared` `RainLoopSpeed`: seconds per forecast hour, 2.0 (slow) … 0.15 (fast), log-scale slider;
  default 0.8 s/h (was 0.4 s/h: a 48 h loop now ~38 s instead of ~19 s).
- Both Open-Meteo viewers: the cycling "1.0× (Snappy)" label is replaced by 🐢 [slider] 🐇 "0.8 s/h"
  on the Pause / Now row. Live while dragging; never pauses or moves the timeline.
- Remembered: Android `weather_prefs` `rain_map_seconds_per_hour` (saved on release), desktop
  `DesktopConfig.rainMapSecondsPerHour` (saved on release, source `rain-map-window`). Clamped on read.
- WeatherNext viewer unchanged (keeps its four-speed cycle and strings).
- 3 new strings × 20 locales.
- Tests: `RainLoopSpeedTest`, `RainMapSpeedConfigTest` (desktop), Open-Meteo slider case in
  `RainForecastActivityRobolectricTest`. Full suite: 5,647 passed.
