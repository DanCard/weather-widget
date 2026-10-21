# Open-Meteo rain map: slower default speed, speed slider instead of the cycling label

## Request (user, 2026-10-21)
> For open-meteo can we lower the default speed? Also have a different layout to speed controls, maybe a slider?

Scope: the **Open-Meteo rain map** viewer only (Android `OpenMeteoRainLoopActivity`, desktop
`OpenMeteoRainLoopWindow`). The WeatherNext viewer keeps its speed cycle.

## Today
- Both platforms: `FRAME_MS = 400` per forecast hour × `SPEEDS = 0.5/1.0/1.5/2.5`, default index 1 (1.0×):
  **0.4 s per forecast hour**, so a 48 h loop from now runs ~19 s.
- The control is a text label in the bottom-right ("1.0× (Snappy)"); a tap/click cycles to the next
  speed. You can't see the other speeds, and slowing down means cycling past the faster ones.

## Change
### `:shared` `RainLoopSpeed` (new, `shared/rainmap/`)
- The speed is stored as **seconds per forecast hour**, from `SLOWEST = 2.0 s` down to `FASTEST = 0.15 s`.
- **Default `0.8 s`**, twice as slow as today. It is today's "0.5× (Smooth)", so a 48 h loop takes ~38 s.
- The slider runs 0..1 on a **log** scale (`secondsAt(fraction)` / `fractionOf(seconds)`). That gives
  equal travel for each halving of the speed, so the slow end isn't squeezed into a sliver.
- `label(seconds)` → "0.8 s/h", for the readout beside the slider.
- Both tickers advance `TICK_MS / (seconds × 1000)` hours per tick; `FRAME_MS` and `SPEEDS` go.

### Layout (both platforms)
The bottom panel becomes two rows:
```
[──────────── timeline ────────────]
[⏸ Pause] [Now]   🐢 ━━━━●━━━━━ 🐇  0.8 s/h
```
- The speed slider fills the space between Now and the readout. Left is slow (🐢), right is fast (🐇).
- Dragging changes speed live. It never pauses playback and never moves the timeline position.
- **The chosen speed is remembered** for the next open: Android `weather_prefs` key
  `rain_map_seconds_per_hour`, desktop `DesktopConfig` field. Nothing else depends on it.
- Strings: the four `rain_loop_speed_*` strings stay (WeatherNext uses them). New strings:
  `rain_map_speed_slower` / `rain_map_speed_faster` (content descriptions) and `rain_map_speed_value`
  ("%1$s s/h"), in all 20 locales.

## Tests
- `:shared` `RainLoopSpeedTest`: default 0.8 s; the ends map to 2.0 / 0.15 s; `fractionOf(secondsAt(f)) == f`;
  the slider's midpoint is the geometric mean (~0.55 s); label formatting; out-of-range stored values
  are clamped.
- Android Robolectric: opens at the default, or at the stored speed; moving the slider updates the
  readout and the stored value, and leaves the timeline position and play state alone.
- Desktop: the config round-trips the speed; the ticker step comes from the shared rule.
- Run `./scripts/unit-tests.sh`, then `scripts/buildStart-desktop.sh`.
