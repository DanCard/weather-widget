# WeatherNext loop player: one shared run (audit item H)

User, 2026-10-24: "commit and continue" — the next open item of
`plans/261023-duplicate-logic-audit-different-names.md` (from `261021-…-audit.md` H). Last two
`UI_FLOW` entries on `CrossPlatformDuplicateLogTagsTest`: `SIM_LOOP_ERROR`, `SIM_LOOP_FALLBACK`.

## Written twice today
Android `RainForecastActivity` (518 lines) and desktop `RainForecastWindow` (605) + the desktop log
callbacks in `DesktopUiApplication:1300–1315`:

| Step | Android | Desktop |
|---|---|---|
| Manifest load: known-gone check → fetch → clear / mark gone → `RainLoopLoadError` | `fetchManifest` | `LaunchedEffect(domain, manifestAttempt)` |
| Frames for a layer, empty → `noFrames(HOST, 0)` | `filterFramesForLayer` | `LaunchedEffect(manifest, selectedLayer)` |
| Prefetch: prune other runs, fetch each frame, decode, count, none → `noFrames(HOST, n)` | `prefetchFrames` | same effect, `scope.launch(IO)` |
| Ticker: 35 ms tick, 140 ms per frame at 1×, wrap to 0 past the last frame | `tickerRunnable` | `LaunchedEffect(isPlaying, …)` |
| Speeds 0.5 / 1 / 1.5 / 2.5 | `speeds` | inline `listOf` |
| `SIM_LOOP_ERROR` / `SIM_LOOP_FALLBACK` lines | `showLoadError`, `openOpenMeteo` | `onError`, `onUseOpenMeteo` |

## Divergences (bugs on desktop)
1. **Layer switch mixes layers.** Desktop's prefetch runs in the window's `rememberCoroutineScope`,
   not the effect, so a layer change does not cancel it: the old layer keeps writing
   `cachedBitmaps[idx]` into the map just cleared for the new one, and the loop can show frames of
   both. Android checks `selectedLayer.id != layerId` before and after each decode and stops.
2. **"Host gone" forgotten on restart.** A 404/410 marks the host gone for a day so the viewer opens
   straight into the error without asking again (`WeatherNextAvailability.knownGone`). Android keeps
   it in prefs (`WeatherNextGoneStore`); desktop in a `@Volatile` object (`WeatherNextDesktopState`),
   lost on every restart — and the desktop app restarts daily (autostart, `buildStart-desktop.sh`).

## Plan
`:shared` `com.weatherwidget.shared.rainmap.WeatherNextLoop` (beside `RainLoopLoadError`,
`RainLoopSpeed`):
- `TICK_MS`, `FRAME_STEP_MS`, `SPEEDS`; `advance(pos, frameCount, speed)` (wrap) and
  `step(pos, frameCount, ±1)` (clamped) — the ticker and the prev/next buttons.
- `framesFor(manifest, layerId)`.
- `GoneStore` interface (`goneAtMs`, `goneStatus`, `markGone(status, nowMs)`, `clear()`); Android
  `WeatherNextGoneStore` implements it over its prefs; desktop gets a file-backed one in
  `~/.local/share/weather-widget/` (fixes 2).
- `suspend fun loadManifest(client, domain, store, nowMs): Manifest | RainLoopLoadError` — the gone
  check, fetch, clear / mark.
- `suspend fun <T> prefetch(client, cacheDir, domain, layerId, manifest, frames, stillWanted, decode,
  onFrame): RainLoopLoadError?` — prune, per-frame fetch + decode, `stillWanted()` before and after
  each decode (a stale decode is dropped via `onDiscard`, Android recycles it), `noFrames` when none
  loaded. Desktop calls it with `stillWanted = { selectedLayer.id == layerId }` (fixes 1).
- `ERROR_TAG` / `FALLBACK_TAG` + `errorLine(error, domain)` / `fallbackLine(error)`; both tags leave
  the log-tag allowlist (→ 6 entries, adapters only besides `HEADER_FIT`).

Stays per platform: bitmap decoding (Android scaled `BitmapFactory`, desktop Skia), the UI, the
handler / `LaunchedEffect` that drives the ticker, the Open-Meteo hand-off itself.

## Tests
- `:shared` `WeatherNextLoopTest`: `advance` wraps and scales with speed; `step` clamps;
  `framesFor`; `loadManifest` — known gone → error without a fetch, 404 marks gone, success clears;
  `prefetch` — none decoded → `noFrames(n)`, `stillWanted` false mid-run → stops and discards the
  in-flight decode; the two log lines.
- Desktop regression for 1: a layer switch during prefetch leaves only the new layer's frames
  (fails on today's code).
- Desktop regression for 2: the file store survives a new instance.
- Full unit suite; desktop rebuilt and restarted.
