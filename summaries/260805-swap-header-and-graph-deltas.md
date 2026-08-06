# Swap header delta ↔ graph/overlay delta

*2026-08-05 · plan: `plans/260805-swap-header-and-graph-deltas.md`*

The user doesn't use the header's forecast delta much and prefers the delta from yesterday
there. The two deltas swapped display sites on both platforms:

- **Header** (Android hourly + daily + text mode; desktop window header + genmon panel) now shows
  the **delta from yesterday** (latest observed temp minus the blended actual at the same clock
  time 24h earlier).
- **Hourly graph annotation label** and the **large today-column overlay** now show the **delta
  from forecast** (observed minus forecast at the current hour — the same value that shifts the
  ghost line).

Decisions made with the user: the header delta **always shows** — no `HeaderDeltaGate` pan-based
hiding (the yesterday delta is pan-independent), including in 1-row text mode; only the existing
±0.1° noise threshold and data-availability gates remain. The ghost line itself is unchanged (it is
a curve correction, not a delta readout).

## What changed

1. **`:shared`**
   - `YesterdayDeltaLabel` renamed to `ForecastDeltaLabel`; suffix `" from forecast"`, compact
     caption `"fcst"` (was "yest").
   - `TodayColumnOverlayContentResolver` takes a `forecastDelta: Float?` parameter instead of
     computing the yesterday delta internally.
   - `ForecastResult` gains `deltaFromYesterday: Float?`.
   - `HeaderDeltaGate` deleted (no callers left after the header dropped the gate).
2. **Android (`:app`)**
   - Header (hourly + daily) shows the yesterday delta, always visible past the threshold.
     `TemperatureStateResolver` computes it even when the graph is off (text mode gets a small 30h
     observation query). `DailyViewHandler` runs one 36h observation query per render, shared with
     the today-column overlay via `ctx.headerObservations` (no duplicate IO).
   - Hourly graph label shows the forecast delta (`placeForecastDeltaLabel`, fed by the existing
     `appliedDelta`); obstacle type `YESTERDAY_DELTA` → `FORECAST_DELTA`.
   - Today-column overlay shows the forecast delta with the "fcst" caption.
   - Partial header updates (tick + refinement) no longer touch the delta view — the
     pan-independent value persists from the last full render instead of flickering.
   - Header state logs record both `appliedDelta` (forecast; ghost line/overlay provenance) and
     `headerDelta` (yesterday; displayed); the "scrolled_into_past" hidden-reason is gone.
3. **Desktop (`:desktop`)**
   - `DesktopWeatherRepository.resolveForForecastResult` returns `ResolvedCurrentTemp` (display
     temp, forecast delta, yesterday delta); `ForecastResult.deltaFromYesterday` populated on
     load/refresh.
   - Window header (`Main.kt`) and genmon panel (`PanelIpcServer`/`DaemonProcess`) show the
     yesterday delta, always visible (threshold only).
   - Hourly graph label draws the forecast delta via `ForecastDeltaLabel`; the overlay passes
     `forecast.appliedDelta`.

## Tests

- Updated: `ForecastDeltaLabelTest` (renamed from `YesterdayDeltaLabelTest`), `CelsiusDisplayTest`,
  `TodayColumnOverlayContentResolverTest`, `TemperatureGraphRendererForecastDeltaTest` (renamed),
  `DailyViewHandlerTest` (mock repository supplies a 24h-ago observation),
  `DesktopDailyForecastModelTest` (overlay now needs `appliedDelta`).
- `TemperatureDeltaVisibilityRoboTest` rewritten for the new semantics: adds "hidden when no
  yesterday observation exists" and pins "stays visible when scrolled fully into the past" (the old
  gate's hide case, now inverted).
- `:shared:testByDurationShared`, `:desktop:testByDurationDesktop`,
  `:app:testByDurationDebugUnitTest`, and `:app:assembleDebug` all pass.

## Runtime evidence (emulator-5554)

- Daily widget: header shows `-0.6` (yesterday delta); today-column overlay shows `-4.3 fcst`
  (forecast delta).
- Hourly widget: header `-0.5`; graph label `-4.2 from forecast`.
- `TODAY_OVERLAY` rows in `app_logs` show the overlay delta flipping from `-0.6` (pre-install) to
  `-4.3` (post-install) on the same observation — the swap is confirmed in data, not just pixels.
