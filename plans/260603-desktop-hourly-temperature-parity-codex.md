# Desktop Hourly Temperature Android Parity

## Goal

Bring the Compose desktop hourly temperature graph closer to the Android hourly widget, with matching navigation controls, header behavior, and graph semantics where practical in the desktop codebase.

## Current Findings

1. Android hourly rendering flows through `TemperatureStateResolver`, `TemperatureViewBinder`, and `TemperatureGraphRenderer`.
2. Desktop hourly rendering flows directly from `WidgetPopup` to `TemperatureGraph`.
3. Desktop already persists `hourlyOffset`, but hourly mode does not expose Android-style back/forward controls.
4. Desktop graph rendering has basic actual/forecast lines, cloud/precip overlays, current marker, and high/low labels, but it lacks Android's day labels, label cadence, offset-centered window semantics, and closer footer/header behavior.

## Implementation Plan

1. Add hourly navigation controls to desktop hourly mode.
   - Reuse Android's six-hour wide-mode step.
   - Clamp to Android's hourly range: -720 to 720 hours.
   - Overlay left/right controls like daily mode and disable them at the bounds.

2. Align hourly header behavior.
   - Keep the desktop header Compose-native, but make hourly mode show the Android-equivalent elements: source selector, current weather icon, current temperature, date/source context, settings/location, and H/D graph selector.
   - Use the offset-aware header date when the graph is scrolled away from now.

3. Tighten graph parity.
   - Treat the graph window as Android wide zoom: 12 hours back and 12 hours forward from the selected center time.
   - Use Android's narrow/wide label cadence ideas for bottom hour labels.
   - Add day labels at the left/right edge, highlighting today.
   - Keep actual observations solid pink and forecast line dashed/solid around now.
   - Preserve current desktop overlays for cloud cover and precipitation.

4. Add focused tests.
   - Test hourly nav left/right updates `hourlyOffset` by six hours.
   - Test nav disables at min/max bounds.
   - Keep graph rendering smoke coverage.

5. Verify.
   - Run `./gradlew :desktop:test`.
   - Run `./gradlew :desktop:build` if tests pass.
