# Open-Meteo rain map loop (for the Open-Meteo source; WeatherNext unchanged)

Plan: `plans/261020-open-meteo-rain-map-loop.md`.

- Pill routes by displayed source: Open-Meteo → new rain map (global, pin = graph); others →
  WeatherNext as before. User kept WeatherNext after I had recommended removing it.
- One multi-location Open-Meteo request (225 points), cached 1 h per zoom; start ±8°, Zoom in ±2°.
  Started at ±1.5°, widened twice at the user's request.
- Measured: Open-Meteo counts every point (441-point requests → HTTP 429 on the 3rd in a minute;
  limits 600/min, 5,000/h, 10,000/day per IP, shared with the widget's own fetch). Cold wide grids
  take 10–20 s: own 45 s timeout, no auto-retry (each retry cost 225 points again).
- Map: Natural Earth 1:50m lines bundled (143 KB), bicubic raster clamped to cell corners (bilinear
  drew plus signs), Amount / Chance layers, "Here" readout, legend in mm/h or in/h.
- Found on the way: desktop canvas unclipped, "860" pin label, empty ".004" legend swatch, cache not
  keyed by zoom; all fixed. Desktop UI-process client now records API usage.
- 5630 unit tests pass; emulator verified wide + zoom; desktop verified before the zoom button.
