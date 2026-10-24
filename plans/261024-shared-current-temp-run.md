# Current temperature: one shared run (audit item E)

User, 2026-10-24: "yes" → continue the audit (`261023-duplicate-logic-audit-different-names.md`) with E
(from `261021-android-desktop-shared-code-audit.md`). Both platforms feed the header number from the
same shared pieces, but the glue that strings them together is written 1× on desktop and ~6× on
Android, and the copies disagree.

## The pipeline (shared pieces, per-platform glue)
1. Window: `CurrentTemperatureResolver.buildCurrentTempResolutionWindow` (now −12 h … +3 h).
2. Observed now: `ActualsAggregator.resolveCurrentObservation` (the blend at now).
3. Header number: `CurrentTemperatureResolver.resolve` — forecast at now + (observed − forecast at the
   observation's time), the "delta".
4. "From yesterday": `YesterdayDeltaCalculator.computeDelta`.

Desktop: one function, `DesktopWeatherRepository.resolveForForecastResult`. Android: window 6×,
`resolve` 5× (`TemperatureViewHandler` ×3, `TemperatureStateResolver`, `CurrentTempResolutionHelper`),
yesterday delta 3×, plus `ObservationResolver.resolveObservedCurrentTemp` as a fallback in 5 places.

## Divergences found
1. **Stored delta, Android only.** Android persists the last delta per widget + source
   (`widget_current_temp_delta_*` prefs) and passes it to `resolve`; desktop passes `null`. It matters
   only when no observation reaches the window: Android shows forecast + the last stored delta,
   desktop the bare forecast. `resolve` never ages it. Measured on the Pixel, 10-21 23:58 → 10-22
   01:45: header 60.1 °F = forecast 58.2 + a held delta of 1.91; desktop's rule gives ~58.2. The
   Pixel also holds deltas weeks old (OWM's from ~10-03) that would apply unchanged.
2. **Raw-observation fallback, Android only.** When the blend gives nothing,
   Android falls back to the newest raw row (NWS_BLEND preferred,
   `resolveObservedCurrentTemp`) as the observed temperature; desktop has no fallback.
3. **Android-internal:** `WidgetRenderer`'s no-repository branch hard-codes `lookbackHours = 12L,
   lookaheadHours = 2L`; the constant is 3 h (`RESOLUTION_LOOKAHEAD_HOURS`), which every other
   caller uses — a different blend window, and a cache key that never matches the others'.

## Decisions (user, 2026-10-24)
First answers were "keep the stored delta with a 3 h age limit" and "raw fallback on both" — on my
recommendation, which was wrong on both counts (a side-agent review caught the first):
- **Stored delta: dropped on both.** It is reached only when no reading reaches the 12 h window, so
  its source reading is > 12 h old: a 3 h limit never fires, and a longer one applies a correction
  from another time of day. The 10-21 Pixel case was not "no readings" — Synoptic had 692 in the
  window; the start-up paint passed none to `resolve` (Android glue).
- **Raw fallback: dropped on both.** It fires only when the blend accepted no reading, and it ignores
  the blend's QC and personal-weight filters, so it could only surface a rejected reading. It never
  ran on either phone (79/79 headers `graph_style`); on production paths its input list was empty.

## Done (phase 1)
- `:shared` `CurrentTempRun` (window → blend at now → `resolve` → yesterday delta). Desktop
  `resolveForForecastResult` and the Android Months screen run it (Months printed the newest raw row
  as its current temperature, not the widget header's number).
- `CurrentTemperatureResolver.resolve` takes no stored delta (no `storedDeltaState`, `currentLat/Lon`,
  `updatedDeltaState`, `shouldClearStoredDelta`); `CurrentTemperatureDeltaState` deleted. Android's
  delta prefs are no longer read or written; `CurrentTemperatureDeltaStore` keeps only the legacy
  un-suffixed coordinates (`WidgetLocationStore`) and per-widget key removal. Not purged: a purge
  could not tell a widget's only coordinates from a delta's.
- Android raw fallback removed (`ObservationResolver.resolveObservedCurrentTemp`, 5 call sites), and
  with it a now-dead observation query on every hourly-graph interaction (`GraphInteractionRenderer`).
- `WidgetRenderer`'s no-repository branch uses the shared window constants (was 12 h / 2 h).
- Tests: `CurrentTempRunTest` (blend anchors; a QC-failed reading alone → bare forecast; nothing in
  the window → bare forecast), `CurrentTemperatureResolverTest` (no observation → bare forecast),
  stored-delta tests removed or reduced to their fresh-observation assertions, a legacy-location test.

## Phase 2 (open)
Android's widget header paths still string the shared pieces together themselves
(`TemperatureViewHandler` ×3, `TemperatureStateResolver`, `CurrentTempResolutionHelper`,
`DailyHeaderResolver`, `WidgetRenderer`): same rules now, glue still per path. Move them onto
`CurrentTempRun`; the start-up paint that passed no observation (above) is part of that.
