# Configurable hourly zoom span (4–8h), default 5h

Date: 2026-08-09
Plan: [plans/260809-configurable-narrow-zoom-span-opus.md](../plans/260809-configurable-narrow-zoom-span-opus.md)

## What changed

**The structural core (`:shared`)** — `ZoomStage` was an enum with `backHours = 2` baked into its
constructor, so the tight view could never vary. It's now split:

- `ZoomStage` — identity only (persisted, cycled on tap, compared with `==`)
- `ZoomWindow` — resolved geometry, produced by `ZoomStage.window(narrowSpanHours)`

The five geometry properties were **deleted** from the enum, so asking a stage for hours is a
compile error. That's what forced all ~25 call sites to resolve a window instead of silently
reading a stale 2h/2h.

**Behavior**

- Span 4–8h, **default 5h** (3 back / 2 forward — back-heavy on odd spans)
- Scroll step: **1 hour at 4–6h, 2 hours at 7–8h**
- Setting sits directly above "Weather Data Sources" on both platforms; label reads just `"5 hours"`
- Desktop shares the rule via `:shared`; its continuous wheel zoom is untouched, the setting governs
  the stage a click snaps to

## Two things worth attention

**1. Existing installs move too.** The preference is absent everywhere, so current users go
4h → 5h, not just new installs. Recommended behavior (no migration code). A one-time backfill
pinning existing installs at 4h is still available if wanted.

**2. Footer-crowding risk was fixed, not deferred.** `NARROW` labelled *every* hour, which at 8h
would draw 8 `<hour><icon>` groups on a 2–3 column widget — where `WIDE` already thins itself to ~4
(24h ÷ `NARROW_WIDE_LABEL_INTERVAL`). `HourlyZoomRules.narrowWidgetLabelInterval` now returns 2 from
6h up, in all three hourly handlers. Justified from the codebase's own label budget, not from a
measured collision (Robolectric has no font engine), so **eyeball a narrow widget at 8h** to confirm.

## Verification

`:shared:test`, `:desktop:test`, `:app:testDebugUnitTest` and the instrumented source set all green;
`installDebug` onto 4 devices; settings screen screenshotted (renders correctly above Weather Data
Sources); desktop rebuilt and restarted via `scripts/buildStart-desktop.sh`.

The new tests were proved able to fail: pinning `HourlyTouchZoneMapper` back to a fixed 4h window
turned 5 assertions red across `WeatherWidgetProviderRobolectricTest` and
`TemperatureTouchRoutingRoboTest`; inverting the back/forward split turned 3 red in `ZoomWindowTest`.

## Notes for next time

- Four "unrelated" failures turned out to be genuine consequences worth knowing about:
  `CurrentTempUnificationIntegrationTest` broke because a `mockk` stub still answered `getZoomStage`
  while production had moved to `getZoomWindow` — a relaxed mock returns a default rather than
  failing, so the type split surfaced as a wrong *value*, not a compile error. That's the one class
  of breakage this refactor's compiler-enforcement can't catch.
- Three Compose desktop tests broke purely because the settings form got taller and controls fell
  outside the 1024×768 test surface. Two were fixable with the repo's existing `performScrollTo`
  pattern; the third couldn't scroll (its clock is frozen for debounce assertions, and
  `performScrollTo` hangs without frames — it locked a Gradle run for 10 minutes before being
  killed), so it now halves `LocalDensity` to fit the whole form.
- `CloudCoverViewHandler`'s repaint gate keys on `zoom.totalSpanHours * 60`, so changing the setting
  invalidates the cached bitmap for free — the span change propagates to a rebuild with no extra
  wiring.
