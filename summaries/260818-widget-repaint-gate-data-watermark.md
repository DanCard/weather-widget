# Widget repaint gate: data watermark + paint-owed flag (2026-08-18)

Implemented, tested, and installed on the Samsung fold. Nothing committed.

Plan: [`plans/260818-widget-repaint-gate-data-watermark.md`](../plans/260818-widget-repaint-gate-data-watermark.md)

## Two defects the unit tests couldn't see, both caught by checking the device

The pure logic was right from the first compile; the wiring was wrong twice.

**1. The watermark was computed from an empty list.** The plan claimed `currentTemps` "already
flows" from `WidgetPaintCoordinator` to the handlers. It does — but whenever a `repository` is
present (every worker and UI path) that list is *empty*, and the observations actually drawn come
from `repository.getObservationsInRange(...)` inside the branch. The watermark persisted as `0`, so
`data_changed` could never fire. The whole change was inert and every test still passed.

**2. The interaction path clobbered it.** `GraphInteractionRenderer` (nav taps, refresh) calls the
same three handlers, never consults the gate, and was stamping the default over a good watermark.

### Fix 2 had a tempting wrong answer

The obvious move is "compute a watermark there too." But that path queries a *different* observation
set (`getMainObservationsWithComputedNwsBlend`, whole-day) than the gated path
(`getObservationsInRange`, window-around-now). Two watermarks from two queries are not comparable —
and a lower value from the wrong query would silently **suppress** a needed rebuild, which is the
exact bug being fixed. So the parameter became `Long?`, where null means "not measured — preserve
what is stored".

### The failure mode was silence

A watermark stuck at `0` produces no crash, no wrong pixel, no failing test — just a feature that
quietly does nothing. Reading the value out of the device's `shared_prefs` was the only thing that
would have caught it.

## Verification

- Full `:app:testDebugUnitTest` green. 7 new gate cases, 8 new watermark cases, 8 new Robolectric
  persistence cases.
- **Falsification checked** — disabling the `data_changed` clause fails exactly the two
  watermark-dependent tests (`new observation with unchanged temp string forces rebuild`,
  `data changed outranks temp changed`) and nothing else, so they can genuinely fail.
- **Device:** `widget_last_data_watermark_349 = 1787098800000` → 17:20:00, exactly AW020's newest
  NWS observation at that moment. Idle UI-only ticks still log `header_only_live`, so the over-fire
  regression that was the main risk did not materialise.

### Not yet observed on device

`data_changed` and `paint_owed` themselves. They need a new reading to land during a UI-only tick,
and a fetch completing while the screen is off, respectively. Both are unit-covered, but the
on-device confirmation is genuinely outstanding — grep those two reasons out of `app_logs` the next
day and it is settled.

## The load-bearing regression test

`ObservationWatermarkTest > advancing fetchedAt alone does not move the watermark`. If someone later
"simplifies" the helper to use `fetchedAt`, that test catches it before it restores blind 15-minute
repainting under a new name. `fetchedAt` carries *attempt* semantics — `INSERT OR REPLACE` refreshes
it for a byte-identical repeat, and `touchLatestFetchedAt` bumps it on an empty attempt — so a
watermark keyed on it would advance on essentially every fetch cycle regardless of whether anything
drawn changed.

## Files

**Production:** `ObservationWatermark.kt` (new), `GraphRepaintGate.kt`, `WidgetStateManager.kt`,
`WidgetPresentationStateStore.kt`, `WidgetPaintCoordinator.kt`, `WidgetRenderer.kt`,
`TemperatureViewHandler.kt`, `PrecipViewHandler.kt`, `CloudCoverViewHandler.kt`

**Tests:** `ObservationWatermarkTest.kt` (new), `WidgetRenderStateWatermarkRoboTest.kt` (new),
`GraphRepaintGateTest.kt` (extended)

## Still open

Defect A from the plan — nothing repaints on unlock, because `ACTION_USER_PRESENT` is
manifest-declared and undeliverable at targetSdk 26+ — is untouched by design. This change makes
recovery *deterministic*; it does not shorten the unlock-latency window.
