# Desktop Location Picker Parallel Acquisition

## Summary

- Replace the separate "Finding Weather Location" window with a single location picker window.
- On first launch with no saved config, open the picker immediately.
- Start phone GPS acquisition and IP lookup in parallel while manual picker controls remain usable.
- Put the phone/IP progress log at the bottom of the picker.
- If phone GPS succeeds with a fresh fix, save it and close the picker automatically. If it fails or
  is stale, leave the picker open with the log visible and IP/timezone/manual options available.

## Key Changes

- In `Main.kt`, remove `acquisitionVisible` and `LocationAcquisitionStatus`; first launch should set
  `pickerVisible = true` immediately when config is absent.
- Extend `LocationPicker` to own startup acquisition state:
  - Start `resolver.fromPhone(log)` in a background coroutine.
  - Start `resolver.suggestPrefill(log)` in a separate background coroutine at the same time.
  - Keep address search, coordinate entry, suggested IP/timezone option, and phone button visible
    throughout.
- Add a bottom log panel in `LocationPicker`:
  - Show phone acquisition messages and IP/timezone prefill status.
  - Keep the log visible after failures.
  - Append a success message before auto-closing on phone success.
- Keep the existing manual "Use connected phone (GPS)" button, but route it through the same logged
  `resolver.fromPhone(log)` path.
- Preserve real-phone-only behavior from `PhoneLocator`: skip emulators and use `adb -s <serial>` for
  each real attached device.

## Behavior Rules

- Saved config present: do not run phone/IP acquisition; show weather popup.
- No saved config: show picker immediately, run phone GPS and IP lookup in parallel.
- Phone GPS fresh success: save config, close picker, open weather popup.
- Phone GPS absent/stale/fails: do not close picker; log the reason at the bottom.
- IP lookup success: prefill suggested location and coordinate fields only; do not auto-save or close
  picker.
- IP lookup failure: fall back to timezone prefill and log that fallback.
- Manual user selection always wins if selected before phone completes; save that selection and close
  picker.

## Test Plan

- Update/add desktop JVM tests for:
  - `PhoneLocator` still skips emulators and targets real phone serials.
  - Logged phone acquisition returns fresh phone location and marks it auto-selectable.
  - Stale phone location does not auto-select and leaves fallback available.
- Run:
  - `./gradlew :desktop:test :shared:test`
  - `./gradlew :desktop:build :shared:build`

## Assumptions

- IP lookup is only a prefill/fallback suggestion, not an auto-selected location.
- The log panel should be at the bottom of the picker and remain visible after failures.
- If phone acquisition succeeds after the user manually picks another location, the manual selection
  should not be overwritten.
