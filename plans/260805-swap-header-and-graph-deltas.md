# Swap Forecast Delta and Yesterday Delta Display Sites

## Outcome

Swap which delta is shown where, on both platforms:

1. **Header** (Android hourly + daily + text-mode + partial ticks; desktop window header + genmon
   panel) shows the **delta from yesterday** (current observed temp minus blended actual at the same
   clock time 24 h earlier). Today it shows the forecast delta.
2. **Hourly graph annotation label** and the **large today-column overlay** show the **delta from
   forecast** (observed minus forecast at the current hour). Today they show the yesterday delta.

Explicitly unchanged:

- The hourly **ghost line / ghost label** (forecast curve shifted by `appliedDelta`) — that is a
  functional correction of the curve, not a delta readout, and still needs `appliedDelta`.
- `CurrentTemperatureDeltaStore` persistence and `CurrentTemperatureResolver` — `appliedDelta`
  keeps being computed for the ghost line; it just leaves the header.
- `YesterdayDeltaCalculator` itself — still the source of the yesterday delta, now for the header.
- Accuracy stats / forecast-history features (they use snapshots, not these deltas).

## Current Display-Site Inventory (verified in code)

### Forecast delta (`appliedDelta`, observed − forecast at current hour)

1. Android hourly header — `TemperatureStateResolver.kt` (delta at L242-249, `deltaText` /
   `isDeltaVisible` at L296-305, gated by shared `HeaderDeltaGate`).
2. Android daily header — `DailyHeaderResolver.kt` L192-204 (value + visibility), bound via
   `HeaderRemoteViewsBinder.bindDelta`; state carried on `DailyViewHandler.HeaderState`
   (`appliedDelta`/`deltaVisible`/`deltaText`, L785-788).
3. Android daily graph header-in-bitmap — `DailyGraphRenderer.kt` L228-231
   (`HeaderRenderData.deltaText` from `headerState.appliedDelta`).
4. Android hourly header partial updates — `TemperatureViewHandler.kt` L344-354 (15-60 min tick)
   and L435-455 (refinement), plus the change gate `shouldApplyRefinedHeaderUpdate` L469-498.
5. Desktop window header — `Main.kt` L1427-1491 (`deltaVal`/`deltaTemp`, also `HeaderDeltaGate`).
6. Desktop genmon/panel markup — `PanelIpcServer.kt` L84-134, fed by `DaemonProcess.kt` L95-100.

### Yesterday delta (`deltaFromYesterday`, obs now − blended actual at obs time − 24 h)

7. Android hourly graph annotation — `TemperatureGraphRenderer.kt` L293-296 →
   `TemperatureGraphAnnotationRenderer.placeYesterdayDeltaLabel`; text/placement from shared
   `YesterdayDeltaLabel` ("+0.4 from yesterday").
8. Android large today-column overlay — shared `TodayColumnOverlayContentResolver`
   (`deltaValueText` + `COMPACT_CAPTION` "yest"), consumed by
   `DailyGraphRenderer.buildTodayOverlayData` (L338-389) → `TodayColumnOverlayRenderer`.
9. Desktop hourly graph label — `TemperatureGraph.kt` L426 (compute) and L660-695 (draw).
10. Desktop today overlay — `DailyForecastGraph.kt` L700-703, same shared resolver as #8.

## Phases

### Phase 1 — `:shared` label plumbing

1. Generalize `YesterdayDeltaLabel` so the suffix and compact caption are parameters (or rename the
   object to a neutral `GraphDeltaLabel` — recommend rename; "Yesterday" in the name becomes wrong
   the moment it formats a forecast delta). New texts: suffix `" from forecast"`, compact caption
   `"fcst"`. Keep `formatValue`, color, and placement logic untouched.
2. Update shared tests: `YesterdayDeltaLabelTest`, `CelsiusDisplayTest` (assertions on
   "from yesterday" strings).
3. Run `:shared:testByDurationShared`.

### Phase 2 — Android hourly view

1. `TemperatureStateResolver.loadGraphHours`: compute `deltaFromYesterday` even when
   `useGraph == false` (the early return at the top currently skips it) so 1-row text-mode headers
   keep a delta. The `deferStartupGraphActuals` startup paint yields a null delta until the first
   real observation load — acceptable, matches today's cold-start behavior.
2. Header: feed `deltaText` / `isDeltaVisible` from `deltaFromYesterday` instead of `appliedDelta`.
   **Decided: drop the `HeaderDeltaGate` window rule for the header — the header delta always
   shows** (whenever a delta value exists and passes the existing `abs >= 0.1` noise threshold).
   The yesterday delta is pan-independent (per `YesterdayDeltaCalculator`'s contract), so the
   pan-based hide rule no longer applies. (`HeaderDeltaGate` itself stays in shared for any
   remaining forecast-delta uses; the header simply stops consulting it. The
   `isDeltaWindowVisible` plumbing in `ResolutionResult`/`TemperatureViewHandler` is removed or
   left unused accordingly.)
3. Graph annotation: `TemperatureGraphRenderer.renderGraph` already receives `appliedDelta` — pass
   it (not `deltaFromYesterday`) into `TemperatureGraphAnnotationRenderer.placeYesterdayDeltaLabel`
   (rename to `placeForecastDeltaLabel`), with the Phase 1 suffix. Placement/span gate (≤25 h)
   unchanged.
4. `TemperatureViewHandler` tick (L344-354) and refinement (L435-455) partials: bind the yesterday
   delta. These paths don't load observations today — thread the resolver-computed
   `deltaFromYesterday` through `ResolutionResult` and reuse it in both partial paths (and in
   `shouldApplyRefinedHeaderUpdate`'s change gate). No new DB queries on the tick path.
5. Update app tests: `TemperatureGraphRendererYesterdayDeltaTest`, any
   `TemperatureStateResolver`/`DailyHeaderBinder` delta assertions.

### Phase 3 — Android daily view + today overlay

1. `DailyHeaderResolver.resolveState` has no observation access today. Hoist one
   `repository.getObservationsInRange(now − 36 h, now)` query into `DailyViewHandler` and share it
   with `DailyGraphRenderer.buildTodayOverlayData` (which already runs that exact query at
   L346-352) — avoids double IO per render. Pass the observations (or the computed delta) into
   `resolveState`.
2. Header: `deltaVisible`/`deltaText` computed from the yesterday delta (same unit conversion via
   `useCelsius`, same threshold). Keep `appliedDelta` on `HeaderState` — the overlay swap needs it —
   and add `yesterdayDelta` to `buildHeaderStateLog` so the persisted header log stays auditable.
3. `DailyGraphRenderer` header-in-bitmap `deltaText` (L228-231): same yesterday-delta source.
4. `TodayColumnOverlayContentResolver`: replace the internal `YesterdayDeltaCalculator.computeDelta`
   call with a `forecastDelta: Float?` parameter; caption `"yest"` → `"fcst"`.
   `DailyGraphRenderer.buildTodayOverlayData` passes `headerState.appliedDelta` (unit-raw, °F —
   `formatValue` handles Celsius). Desktop caller updated in Phase 4.
5. Update daily-header/overlay tests.

### Phase 4 — Desktop parity

1. `DesktopWeatherRepository.resolveForForecastResult`: also compute `deltaFromYesterday`
   (observations are already in scope) and expose it on the forecast result.
2. `Main.kt` header (L1427-1491): show the yesterday delta; drop the `HeaderDeltaGate` window rule
   here too, matching the Phase 2 "always show" decision.
3. `PanelIpcServer` / `DaemonProcess`: pass the yesterday delta into the genmon markup (the orange
   span semantics change; color stays).
4. `TemperatureGraph.kt` label (L660-695): draw `appliedDelta` with the Phase 1 "from forecast"
   suffix; the `deltaFromYesterday` computation at L426 is no longer needed for the label.
5. Desktop today-overlay call site: pass the forecast delta into the shared resolver.
6. Update desktop tests (`PanelIpcServerTest`, graph label tests).

### Phase 5 — Verification (Evidence-First)

1. `:shared:testByDurationShared`, `:app:testShortDebugUnitTest` (plus Medium/Long buckets for
   touched classes), `:desktop:testByDurationDesktop`.
2. Emulator (`adb` screenshot + logcat): hourly widget shows header delta matching
   `YesterdayDeltaCalculator` output and a graph label reading "+x.x from forecast"; daily widget
   header matches yesterday delta; large-widget today overlay shows "fcst" caption.
3. Desktop: `./gradlew :desktop:run` smoke check of header + hourly graph label.

## Decisions

1. **Header window gate — DECIDED: drop it.** The header delta always shows (no pan-based hiding);
   only the existing `abs >= 0.1` noise threshold and data-availability gates remain.
2. **Text-mode hourly header — DECIDED: always show there too.** Compute the yesterday delta even
   when the graph is off (one extra obs query per resolve), per the same "always show" decision.
3. **Naming — DECIDED: rename** `YesterdayDeltaLabel` → `ForecastDeltaLabel` (and
   `placeYesterdayDeltaLabel` → `placeForecastDeltaLabel`), since after the swap every remaining
   call site shows the forecast delta. `YesterdayDeltaCalculator` keeps its name (it still computes
   the yesterday delta, now for the header).
