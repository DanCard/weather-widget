# Fix: HourlyBottomTouchZoneInstrumentedTest Failing on Gone Container

## Problem
Two instrumented tests failed on the emulator:
```
HourlyBottomTouchZoneInstrumentedTest > graphBottomHourZones_startsAtGraphBodyBoundary
    AssertionError: ...begin exactly where the zoomable graph body ends... expected:<0> but was:<853>
HourlyBottomTouchZoneInstrumentedTest > graphBottomHourZones_matchesReservedFooterHeight
    AssertionError: ...height must match the reserved footer height expected:<0> but was:<176>
```
Both assertions had `expected:<0>` — i.e. `graph_interaction_body.bottom` and
`graph_bottom_reserved_space.height` measured to **0**, while the sibling
`graph_bottom_hour_zones` measured normally (height 176, top 853).

## Root cause (NOT the label work)
These tests inflate the raw `R.layout.widget_weather` XML and measure view geometry. They are pure
layout assertions and are unaffected by Kotlin drawing changes.

The regression came from commit **`cfa1cae` "Fix zoom functionality and resolve touch zone
overlaps"**, which added `android:visibility="gone"` to `graph_interaction_container`
(`widget_weather.xml:605`). That container wraps `graph_interaction_body` and
`graph_bottom_reserved_space`; `graph_bottom_hour_zones` is an outside sibling.

At **runtime** this is correct — the view binders flip the container visible when the hourly /
temperature view is active, so `gone` is just the initial XML state. But the test inflates the raw
layout and only set the **leaf** views visible (`reservedFooter`, `bottomHourZones`), never the
parent container. A `gone` parent is not measured/laid out, so its children collapse to 0 — hence
`expected:<0>`, while the outside `graph_bottom_hour_zones` measured fine.

`cfa1cae` verified other touch tests (PrecipTouchRouting, DailyGraphTouchZoneAlignment,
DailyMainColumnVsBottomIconClickTarget, TemperatureHomeTouchRouting) but not this one, so the
breakage slipped through.

## View hierarchy (widget_weather.xml)
```
graph_interaction_container   (line 602, visibility="gone" since cfa1cae)
├── graph_interaction_body          (613)
└── graph_bottom_reserved_space     (733)
graph_bottom_hour_zones        (1095)  ← outside sibling, measured non-zero
```

## Fix
`app/src/androidTest/java/com/weatherwidget/widget/HourlyBottomTouchZoneInstrumentedTest.kt` —
in both test methods, set the parent container visible before `measureAndLayout`, so the test
reproduces the runtime condition it is meant to guard (hourly view active):
```kotlin
root.findViewById<View>(R.id.graph_interaction_container).visibility = View.VISIBLE
```
This keeps the regression guard meaningful rather than masking it. The app itself was never broken;
only the test's setup was stale relative to the new default visibility.

## Verification
```
./scripts/emulator-tests.sh -c com.weatherwidget.widget.HourlyBottomTouchZoneInstrumentedTest
→ Total: 2, Passed: 2  ✓ All tests passed
```
(Used the emulator-only script per project rule — never `connectedDebugAndroidTest`, which would
strip widgets from the attached physical devices.)

## Files Touched
- `app/src/androidTest/java/com/weatherwidget/widget/HourlyBottomTouchZoneInstrumentedTest.kt`

## Status
Change staged in working tree, not committed. Surfaced while verifying the actual-low label work
(see `session-logs/260526-actual-low-label-and-value-ordering.md`) but is an independent,
pre-existing regression from `cfa1cae`.
