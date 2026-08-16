# Default hourly zoom: 24h → 18h (12h back / 6h forward)

Date: 2026-08-16

Request: change the default zoom level for the hourly view from 24 hours to 18 — 12 hours back from
now, 6 hours forward. Applied to both the Android widget and the desktop app (user chose parity).

## What changed

`shared/src/main/kotlin/com/weatherwidget/shared/graph/ZoomStage.kt` — the `WIDE` stage (the default)
is now `backHours = 12`, `forwardHours = 6`. That single edit drives the Android widget, and every
consumer that resolves geometry through `ZoomStage.window()`: query windows, touch zones, label
cadence, smoothing.

`desktop/src/main/kotlin/com/weatherwidget/desktop/DesktopGraphUtils.kt` — desktop needed more than a
constant. Its window comes from one continuous `zoomFactor` fed through two separate geometric curves
(back 2→720h, forward 2→168h), so no factor on the old curves could produce 12/6 — at the default it
drew 12 back / **8** forward. `forwardHoursFor` is now piecewise-geometric between anchors taken from
the shared stages, so the default factor renders exactly 12/6 and `THREE_DAY` renders its exact 48/24
(it had been drawing 22). `DEFAULT_ZOOM_FACTOR` is unchanged at 0.304 — it was always the WIDE
back-hours inversion; only what that factor draws forward changed.

### Notes on the desktop curve

- The anchor set has a deliberate low-end exception: below the NARROW band's ceiling the curve keeps
  its *original* shape. That plain curve happens to split every configurable 4–8h span exactly the
  ceil/floor way `ZoomStage` does; a first attempt anchored the widest NARROW window instead and
  squeezed the 5h and 7h spans off the curve entirely — `NarrowZoomSpanDisplayedHoursTest` caught it
  immediately.
- Anchors are stored as un-rounded floats. Rounding an anchor would put a visible discontinuity at
  the seam, since neighbouring segments interpolate *from* that value.
- `HourlyTouchZoneMapper` already carried an `asymmetryShift` term for `THREE_DAY`, so tap zones
  followed the new split for free — the 13 zones now map −12…+6 instead of −12…+12, which is why the
  middle zone resolves to −3h rather than 0.

## Day tap

Tapping a day in the daily view jumps to the hourly graph at the WIDE stage, centered on that day's
noon — so the old 12/12 framed it midnight→midnight. Per the user's call, the tap stays on WIDE with
the 12/6 split around the now-line: the left edge is still that day's midnight, the right edge is now
6pm, and the evening is one nav press (6h) away. `DesktopGraphUtils.dayViewZoomFactor` /
`DAY_VIEW_SPAN_HOURS` still compute a 24h factor and remain the seam if a full-day tap is ever wanted
back; they are used only by tests.

## Follow-up: nav arrow step is 3h

Scrolling back felt like too much at the narrower window. The arrow was stepping 6h on Android (the
hardcoded `WIDE.navJump`, a third of the new span) and 9h on desktop (`HourlyZoomRules.navJumpHours`
derived half-a-span). Both are now **3 hours**: `WIDE.navJump = 3`, and the above-the-narrow-band
rule changed from `span / 2` to `span / 6`.

A sixth is the fraction that makes the continuous desktop rule reproduce both fixed stages' own
jumps — 18/6 = 3 (WIDE) and 72/6 = 12 (THREE_DAY) — so the platforms genuinely agree at every span
now, which the desktop doc had been claiming without it being true at WIDE. The band edge stays
continuous: spans 9–11 land back on 1h, same as the narrow band. Full zoom-out (888h) steps 148h
instead of 444h.

## Tests

All green: 1933 Android unit tests, 267 desktop, 826 shared. Twelve tests had 12/12 or 24h baked in;
each now derives from the window or documents the new number. Added three desktop tests: the default
factor renders WIDE's window, every stage factor reproduces its *forward* hours (not just back), and
the forward curve stays monotone across all 1000 steps so no anchor seam steps backwards.

Confirmed on the Samsung device by the user. The desktop app was rebuilt and relaunched. One note:
the saved desktop `zoomFactor` is `0.0` (the tightest NARROW view), so the popup must be cycled round
to WIDE to see the 18h window.
