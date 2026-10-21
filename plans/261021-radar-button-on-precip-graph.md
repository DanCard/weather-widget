# Radar button on the precipitation graph

**Date:** 2026-10-21 · **Platforms:** Android + desktop, logic in `:shared`
**Ask (user):** "something like radar.weather.gov" in the widget; "to activate it lets have a button
on rain hourly graph page."

## What exists to build on

- The precip graph already carries one in-plot button, **"🌧 Sim loop"**: `SimLoopPill.place`
  (`:shared`, free-space search via `GraphEmptySpaceFinder`, same slot on both platforms), Android
  `SimLoopTouchTarget` (a `RemoteViews` zone moved onto the drawn pill, API 31+), desktop
  `PrecipitationGraph` `.clickable { onOpenSimLoop() }`.
- `OpenMeteoRainLoopActivity` / `OpenMeteoRainLoopWindow` already animate a rain loop over an
  **offline Natural Earth base map** (`:shared` `BaseMap`, equirectangular lat/lon → x/y), with
  `RainLoopSpeed` and `RainLoopLoadError` (explicit HTTP/timeout/offline/429 messages).

So the radar view should mostly be a new *frame source*, not a new viewer.

## Data (probed 2026-10-21)

| Where | Feed | Measured |
|---|---|---|
| Inside `NwsCoverage` | NWS GeoServer WMS `conus/conus_bref_qcd` (the layer radar.weather.gov animates) — `GetMap`, `crs=EPSG:4326`, `transparent=true`, `time=` | 400×320 PNG 30 KB in 0.33 s, no key; `GetCapabilities` 8 KB lists frames every ~2 min for the last ~2 h; `time=` accepted |
| Elsewhere | RainViewer `weather-maps.json` + `tilecache.rainviewer.com/{path}/256/{z}/{x}/{y}/2/1_1.png` | 13 past frames (10 min, ~2 h), **0 nowcast frames**; free tier zoom ≤ 7 (third-party report, unverified here); Kyiv z7 tile 200 OK (334 B — empty: no rain or no coverage, can't tell) |

EPSG:4326 WMS images line up with `BaseMap`'s linear projection directly. RainViewer tiles are Web
Mercator: stitch the 2×2–3×3 tiles around the site and resample row-by-row to equirectangular
(lat → mercator y), so one renderer serves both.

**Radar is observed, not forecast.** The view says "Radar · past 2 h", never forecast language
(cf. the nowcast notes' "keep the two kinds apart").

## Design

### Button
- Second pill **"📡 Radar"** on the precipitation graph, offered whenever a location is set (radar
  doesn't depend on the displayed source).
- Placed by the same `SimLoopPill.place`, after the Sim-loop pill, with the Sim-loop pill passed in
  `drawnBounds` so they never overlap. If only one fits, **Radar wins** (it works everywhere the
  graph does; Sim loop is source/region-limited). Rename the helper's concept to "graph pill" only
  if the code reads worse without it — otherwise call `place` twice.
- Android: new `radar_touch_zone` in `widget_weather.xml`; `SimLoopTouchTarget.apply` gets the
  view id + request code as parameters (new `WidgetRequestCodes.radar`, **new code**, per the
  PendingIntent-flags memory) instead of a copy. API < 31 → no pill, as now.
- Desktop: `PrecipitationGraph` draws the second pill → `onOpenRadar()` → `RadarWindow`.

### `:shared` `com.weatherwidget.shared.radar`
- `RadarFeed` choice: `NwsCoverage.covers(lat, lon)` → NWS WMS, else RainViewer.
- `RadarFrames`: frame list (NWS: capabilities `time` dimension thinned to every 10 min, last 2 h →
  ~12 frames; RainViewer: `past`). Box: ±1.5° lat around the site, lon scaled by cos(lat) so the
  map is square-ish (~330 km, close to radar.weather.gov's zoom 7).
- `RadarRepository`: fetch frames (parallel, ≤ 4 at a time), disk cache keyed by feed+time+box,
  frames reused for 2 h, frame list refreshed when > 5 min old. Errors through `RainLoopLoadError`.
- `MercatorResample` for RainViewer tiles → equirectangular `RadarImage` (ARGB int array).
- Usage rows: `api_usage_stats` source `RADAR`, endpoints `nws/wms`, `rainviewer/tiles` (same
  classifier path as other endpoints). Logs `RADAR_FETCH feed=… frames=… cached=… ms=…`.

### Viewer
- Android `RadarActivity`, desktop `RadarWindow`: same layout as the Open-Meteo rain loop:
  base map, radar frame, site pin, timestamp ("14:20, 25 min ago"), play/pause, scrub,
  `RainLoopSpeed`. Auto-plays, ends on the newest frame and holds it.
- dBZ colour legend strip (NWS's own palette is already in the PNG; RainViewer scheme 2 = same
  "universal blue" family — legend from the scheme's published table).
- Attribution line: "Radar: NOAA/NWS" or "Radar: RainViewer".
- No coverage / all-empty: "No radar echoes in this area" — not an error, since a dry day is the
  common case.

### Cost
On demand only: nothing fetched until the button is tapped, so no background wakeups and no effect
on the battery cadence tables. A 12-frame open ≈ 12 × 30 KB (NWS) or 12 × ~9 tiles × ≤ 5 KB.

## Tests (automated first)
- `:shared` unit: `RadarFeed` choice at NWS edge / Kyiv / Berlin; capabilities parsing + 10-min
  thinning; box math; Mercator resample (a known tile row lands at the right latitude); repository
  cache reuse with a fixture HTTP engine; empty-frame → "no echoes" state.
- Pill placement: two pills never overlap; Radar kept when only one fits (shared, so both
  platforms).
- Android Robolectric: `radar_touch_zone` visible with the right PendingIntent target and request
  code; hidden on API < 31.
- Desktop: popup test that the Radar pill click calls `onOpenRadar`.
- Manual: Pixel + desktop, Mountain View (NWS) and a non-US location (RainViewer); screenshot both.

## Not in this change
- Radar thumbnail drawn in the widget itself (phase 2, if wanted).
- Radar extrapolation / forecast frames.

## Implementation notes (2026-10-21)

- **Shared rule, platform host** (CLAUDE.md, 2026-10-21): `:shared` `com.weatherwidget.shared.radar`
  owns feed choice, box, frame lists, thinning, Mercator resampling, cache, playback timing and the
  legend; each platform implements only `RadarImageDecoder` (Android `BitmapFactory`, desktop
  `ImageIO`). Pill placement for both pills is `SimLoopPill.placeRadarAndSimLoop`.
- **NWS has four mosaics**, not one: `conus`, `alaska`, `hawaii`, `carib` workspaces (all probed);
  `NwsCoverage.region` names them.
- **RainViewer free tier (read 2026-10-21):** zoom ≤ 7, Universal Blue (2) only, past 2 h at 10 min,
  **100 requests / IP / minute**. Hence zoom 6 × 512-px tiles (zoom-7 detail, ¼ the requests: 1–4
  tiles per frame), disk cache, no retries. London (4 tiles) = 52 requests, 13.8 s cold.
- **Live probe:** Mountain View 12 NWS frames / 3.2 s; Kyiv 13 RainViewer frames / 3.6 s, no
  echoes; London showers drawn over the Channel.
- **No NWS legend:** the WMS serves pre-coloured RGB on a continuous gradient and publishes no scale
  (`GetLegendGraphic` empty, `GetStyles` a bare RasterSymbolizer). RainViewer's Universal Blue table
  (`rainviewer_api_colors_table.csv`) is the only key shown.
- **Usage rows:** `NWS radar/list|radar/frame`, `RAINVIEWER radar/list|radar/frame`
  (`ApiUsageClassifier`), counted by each platform's existing HTTP observer.
- **Base map islands (pre-existing bug, fixed):** `scripts/pack_natural_earth.py`'s Douglas–Peucker
  divided by a zero-length segment on closed rings, so every island and lake ring was reduced to
  one point and dropped — no Great Britain, Ireland, Japan, Hawaii on the rain map either. Old
  script reproduces the committed `ne50m_lines.bin` byte for byte from today's Natural Earth;
  fixed script: 992 → 2821 lines, 140 → 260 KB. `BaseMapIslandsTest`.
- Playback: 500 ms per frame, newest held 2 s, no interpolation between frames (a blend would
  draw a cell nobody observed). Opens on the newest frame.
