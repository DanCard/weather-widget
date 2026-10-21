# Rain map loop from Open-Meteo, for the Open-Meteo source (WeatherNext unchanged)

## Decision (user, 2026-10-20)
- **Widget/popup showing Open-Meteo →** the pill opens a new **rain map loop built from Open-Meteo**,
  so the map and the graph come from the same model and agree at the user's location.
- **Any other source →** the pill opens the **WeatherNext loop, unchanged** (user: "I like it").
  An earlier draft removed WeatherNext; that was my recommendation, reversed by the user.
- Free sources only (user). Open-Meteo is already a source here, global, no key.

## Feasibility (measured 2026-10-20, Mountain View)
One `/v1/forecast` request with 225 coordinates (15×15 over ±1.5° lat, ~±165 km), `hourly=precipitation,
precipitation_probability`, `forecast_hours=48`: HTTP 200, 2–3 s, **25 KB gzipped** (364 KB raw).
Centre cell: 0 mm until ~22 h, then 0.4 → 1.1 mm/h, chance 1 → 18 %; grid max 23 mm/h (offshore
storm). No rate-limit headers: per-location quota cost is undocumented — assume ≤ 225 of ~10,000/day.
WeatherNext for comparison: ~100 JPEG frames per layer, megabytes.

## Design

### Data (`:shared`)
- `OpenMeteoApi.getRainGrid(centerLat, centerLon)`: one multi-location request, same model as the
  app's forecast (no `models=` → Open-Meteo's automatic choice), `timezone=GMT`, 48 h,
  `precipitation,precipitation_probability`. The **centre point is the exact coordinate the widget's
  own Open-Meteo fetch uses**, so Open-Meteo snaps both to the same model cell and the pin's value
  equals the graph's for the same run (it already returns e.g. 37.4162,-122.0803 for 37.4166,-122.0888).
- `RainGrid` model: lat/lon axes, hours, `mm[h][i][j]`, `chance[h][i][j]`, `fetchedAt`.
- Grid: 15×15 to start (`RainGridSpec`, one constant); longitude span scaled by cos(lat) so cells are
  square-ish. Count each request in `api_usage_stats` under Open-Meteo, endpoint `rain_grid`.
- Cache: one grid per (site, hour) on disk, reused for 60 min; no background fetch — opening the viewer
  is the only trigger (matches `SourceFetchGate` spirit: no cost for a feature not in use).

### Rendering (`:shared` math, platform draw)
- `RainGridRaster`: bilinear interpolation of a grid hour into an ARGB `IntArray` at view resolution;
  **temporal** interpolation between hours on values (not image cross-fade → no ghosting).
- Colour scale: transparent below 0.1 mm/h, then light green → green → blue → yellow → red, legend in
  mm/h or in/h by locale (the `formatPrecipAmount` locale rule). Second layer: **rain chance** %, the
  same quantity as the graph.
- Base map: **Natural Earth 1:50m coastlines + country/state borders** (public domain), bundled as a
  small simplified binary/GeoJSON asset (target ≤ 300 KB), clipped to the grid box and drawn as
  paths. Offline, free, no tile server. Pin at the centre with the place name.

### Viewer (both platforms)
- A **new** viewer (`OpenMeteoRainLoopActivity` / desktop `OpenMeteoRainLoopWindow`), separate from
  `RainForecastActivity` / `RainForecastWindow`, which stay as they are: map, layer toggle
  (Amount / Chance), play/pause, scrub bar starting at **now** with a "now" mark, speed, a readout
  "Here: 0.4 mm/h · 18 %" for the current frame, header "Map: Open-Meteo · fetched 15:20".
- Reuse the generic `rain_loop_*` strings (play/pause/speed/valid/loading); add the few new ones
  (Amount, Chance, Here, Map: Open-Meteo) in all 20 locales.

### Pill gating and routing
- Android `PrecipViewHandler` / desktop `PrecipitationGraph`, by displayed source:
  - `OPEN_METEO`: pill offered whenever a location exists (global); tap → Open-Meteo loop.
  - otherwise: today's rule, untouched (`WeatherNextAvailability`: on the map, site not gone); tap →
    WeatherNext loop.
- Placement and touch-zone code (`SimLoopPill`, `SimLoopTouchTarget`) unchanged; only the
  `PendingIntent` target / desktop callback and the availability check branch on source.
- Same pill label for both ("🌧 Sim loop"); the viewers' headers say which map it is.

### Untouched
All WeatherNext code, cache, availability/gone logic, tests and strings.

## Tests
| # | Kind | What |
|---|---|---|
| 1 | Unit (:shared) | `getRainGrid` builds 225 coords centred on the input, cos(lat) lon span; MockEngine response parsed into `RainGrid` (shape, nulls → 0) |
| 2 | Unit | Centre coordinate in the grid request == coordinate the forecast request uses (pin/graph match contract) |
| 3 | Unit | `RainGridRaster` bilinear: corner values exact, midpoints averaged; temporal interpolation halfway between hours |
| 4 | Unit | Colour scale thresholds; legend labels mm vs in by locale |
| 5 | Unit | Coastline asset loads, clips to box, non-empty for Mountain View and Kyiv |
| 6 | Robolectric | Open-Meteo displayed → zone launches the Open-Meteo viewer, also at a location WeatherNext does not cover; NWS displayed → zone launches `RainForecastActivity` as before |
| 7 | Robolectric | Viewer: no location → message, no fetch; MockEngine grid → readout text for frame 0 |
| 8 | Integration (2+ classes) | Grid fetch → raster → readout: centre readout equals the grid's centre values |
| 9 | Unit | Cache: second open within 60 min makes no request; after 60 min it does |
| 10 | Unit | `api_usage_stats` row for `rain_grid` |

## Manual verification
Pixel / Fold / emulator with Open-Meteo displayed: pill visible, tap → loop starts at now, pin
readout equals the graph's hourly chance at the same hour; switch to NWS → pill opens WeatherNext as
before. Desktop same.
Kyiv-style check: set a location in Europe → map renders with coastlines (global coverage).

## Implementation notes (deviations)
- Grid fetch lives in `:shared` `rainmap/RainGridRepository` (not `OpenMeteoApi`); raw reply cached
  on disk, keyed by centre **and grid geometry** (a ±1.5° and a ±4° grid both have 225 points).
- **Zoomed out to ±4° (~890 km), still 15×15 (~60 km cells)** — user: "more zoomed out to start".
  21×21 was tried: 14 s cold, and the next request got **HTTP 429**. Open-Meteo counts every
  location against its free per-IP limits, shared with the widget's own Open-Meteo fetch, so 441
  points per open is too many. User chose 15×15 at ±4°.
- Usage stats: one `forecast/grid` row per request (classifier now sees the query); the request
  weighs 225 locations at Open-Meteo. Desktop's UI-process client now records usage too (it
  carries the grid request).
- Raster 240 px with a fade-in below the first stop (hard threshold drew stair-stepped edges);
  inch legend shows ".004" for 0.1 mm/h (2 decimals printed an empty first swatch).
- Desktop: canvas clipped (coastlines ran outside the square); pin label joins a bare house number
  with its street ("860 Avery Drive", was "860").

## Verification (2026-10-20)
- `./scripts/unit-tests.sh`: 5625 passed.
- Emulator, Open-Meteo shown: pill `map=open_meteo` → `OpenMeteoRainLoopActivity`,
  `RAIN_GRID outcome=fetched points=225`; reopen → `outcome=cached`. NWS/Silurian → WeatherNext
  (Robolectric). Desktop: Meteo popup → "Rain Map · Open-Meteo" window, ±4° map renders.
- Raster is **bicubic (Catmull-Rom)**, clamped per pixel to its cell's four corners: exact at grid
  points, never above/below the surrounding forecast values. Bilinear drew isolated 60 km cells as
  plus signs (diagonal/axis ratio 0.83 at half a cell; now within 6 % of round — tested). Remaining
  artefact: where heavy rain borders a dry cell, the clamp can leave a straight edge along the cell
  boundary. An approximating B-spline would remove it at the cost of not passing through the grid
  values (the pin readout uses exact values either way).

## Follow-up: wide start + zoom in (user, 2026-10-20: ±4° "still seems too zoomed")
- Start `RainGridSpec.WIDE` ±8° (~1,800 km, ~120 km spacing); **Zoom in** fetches `DETAIL` ±2°
  (~30 km) on demand. Both 225 points; each zoom cached separately (expired files only are pruned).
  A zoom switch keeps the watched moment; a failed switch puts the button back (Android).
- Open-Meteo free limits per their terms: 600/min, 5,000/h, 10,000/day; per-location counting
  is undocumented but matched the measured 429 (3rd 441-point request within a minute).
- The wide grid timed out on the app client (cold: 10–20 s). Grid request now has its own 45 s
  timeout and **no automatic retry** (the shared clients retried timeouts twice, each retry costing
  225 points again). Failure log no longer dumps the 5 KB URL.
- Verified on the emulator: wide view Oregon–Baja, `spanDeg=8.0`; Zoom in `spanDeg=2.0`, same
  moment and pin readout; Zoom out served from cache. 5630 unit tests pass.
- Look: at 120 km spacing, isolated cells render as rounded squares (the corner clamp confines a
  cell's rain to the cells touching it).
