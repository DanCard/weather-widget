# Sim loop follow-ups: hide when useless, translate the viewer, fix bare-unit rain amounts

Follow-up to `plans/261020-sim-loop-pill-free-space-placement-and-review-fixes.md` (commit 24c530f4),
closing the items it left open. (Written alongside the work, not before it — the scope was the
already-agreed open list.)

## 1. Hide the pill when the loop cannot help (`:shared` `WeatherNextAvailability`)
- **Off the map:** `resolve` falls back to WIDE (10–75°N, 120–300°E) everywhere, so outside it the
  viewer plays a map with the pin off the edge. Pill shown only inside WIDE, 1° inset.
- **Site gone:** manifest 404/410 → `WeatherNextGoneException` → pill hidden 24 h, then shown so the
  next tap re-checks. No background probe. Other failures keep the pill (viewer Retry).
- Android: `WeatherNextGoneStore` (prefs), handler gate (`SIM_LOOP_PILL reason=unavailable`).
  Desktop: in-memory `WeatherNextDesktopState` (a restart shows the pill; next click re-checks).

## 2. Translate the viewer (Android)
31 `rain_loop_*` strings × 20 locales: chips, layer titles/subtitles/legends, domains, play/pause,
speed, scale, model run, valid. Shared enums keep English for desktop; Android maps them to
resources.

## 3. "Pred in" — bare unit (`DailyRainLabels.formatPrecipAmount`)
- 0.0004in rounded to "0.000", trimmed to "" → "in". Now "<.001in" / "<0.1mm"; zero → "0in"/"0mm".
- Also found: whole-mm amounts (≥ 10mm, no decimals) had trailing zeros trimmed — 20mm printed
  "2mm", 100mm "1mm". Zeros now trimmed only after a decimal point.

## Tests
- `WeatherNextAvailabilityTest` — covered/off-map cities, 24 h gone window, 404/410 vs 503.
- `DailyRainLabelsTest` — 20mm/100mm/10in, "<.001in", "<0.1mm", "0in", ".5mm".
- `RainForecastActivityRobolectricTest` — subtitle compared to the resource.

## Verification (2026-10-20)
- `./scripts/unit-tests.sh`: 5610 passed; Localization bucket `--fresh`: 26 passed.
- Emulator: precip graph shows "Pred <.001in" (was "Pred in"); viewer under a `de-DE` per-app locale
  fully German; it fetched the newer 08:00 run's frames. Pixel log: `rainAmountPlaced: "Pred <.001in"`.
- Desktop: pill drawn top-left above the morning 5% bump (only open space beside a 10% plateau);
  `xdotool` click → "WeatherNext 3 · Rain & Storm Forecast Viewer" window.
- Not exercised live: the 404/410 path (unit-tested with a mock engine only).
