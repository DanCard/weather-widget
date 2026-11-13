# Button press feedback on full-screen activities

## Request

"When I hit buttons when widget is in full screen mode, I'd like an animation to indicate the button
was pressed. For example on settings screen: when refresh data button is pressed I'd like to see an
animation."

## Evidence

1. `refresh_data_button` (`activity_settings.xml:55`) sets
   `android:background="@drawable/rounded_button_green"`.
2. All five `rounded_button_{green,blue,gray,navy,yellow}.xml` are plain `<shape>` drawables
   (solid colour + 12dp corners) with no `<ripple>` and no `state_pressed` item.
3. Setting `android:background` replaces the theme's default button background, which is what
   carries the ripple. So these 21 buttons (settings, main/config, bug report, app logs, observations,
   history of forecasts, API key rows) draw identically pressed and unpressed.
4. `AppLogsActivity.kt:91/94` swaps the same drawables at runtime, so it inherits whatever we do.
5. Buttons on `selectableItemBackground(Borderless)` (24) and the `backgroundTint` buttons in the
   rain screens (7) already ripple; they get no scale bounce today.
6. Desktop: `WeatherButtons.kt` uses Material3 `Button`, which already has a ripple state layer, but
   no scale bounce.

## Root cause

Custom solid-shape backgrounds replaced the ripple-bearing default background, removing all press
feedback.

## Proposed fix

1. **Ripple on every rounded button (Android).** Turn each `rounded_button_*.xml` into a `<ripple>`
   whose content is the existing shape and whose `@android:id/mask` is the same rounded rect, so the
   ripple stays inside the 12dp corners. Ripple colour: translucent white (`#55FFFFFF`) on the dark
   fills (green/blue/gray/navy), translucent black (`#33000000`) on yellow where white won't show.
   One edit per drawable covers all 21 buttons plus `AppLogsActivity`'s runtime swap.
2. **Press "squish" (Android).** Add `res/animator/button_press_scale.xml`, a `StateListAnimator`
   that scales to 0.93 over 100 ms on `state_pressed` and springs back over 250 ms with a small
   overshoot on release (a quick ~100 ms tap still gets ~350 ms of visible motion). It keeps the
   framework's 2dp/4dp press elevation so button shadows don't change.
   Apply it app-wide through the theme's `buttonStyle` / `imageButtonStyle` (parent AppCompat
   styles), so every `Button`/`ImageButton` in every activity gets it without per-layout edits.
3. **Desktop parity.** In `WeatherButtons.kt`, collect the button's `MutableInteractionSource`
   pressed state and animate a `graphicsLayer` scale to 0.93 with the same timings. That covers
   every desktop action button, and the existing Material ripple stays as it is.
4. **Shared values.** `WeatherThemeTokens` holds `BUTTON_PRESS_SCALE`, `BUTTON_PRESS_IN_MS`,
   `BUTTON_PRESS_OUT_MS`, and `BUTTON_DISABLED_ALPHA`; a JVM parity test parses the animator and drawable XML
   and checks that they match these values.
5. **Refresh Data specifically:** it already goes `isEnabled = false` while the refresh runs, but
   the solid shape has no disabled look, so that part is not visible. The ripple plus squish gives
   the tap feedback asked for. A `state_enabled=false` dimmed fill
   (≈50 % alpha) is added to the ripple content, so a running refresh also *looks* busy. Desktop matches: the disabled fill is the
   same colour at 50 % alpha instead of Material's stock grey.

## Decisions (2026-11-13)

- Scale 0.93, 100 ms in / 250 ms overshoot out. Dimming while disabled: included.

## Out of scope

- The home-screen widget itself (RemoteViews can't run view animations or ripples on custom
  backgrounds across launchers).
- Restyling colours, sizes, or padding.

## Tests

1. Robolectric (`SettingsActivity`): `refresh_data_button.background` is a `RippleDrawable` and
   `stateListAnimator` is non-null; same check for a button in a second activity, to prove the theme
   wiring reaches it.
2. Robolectric: setting `isPressed` and jumping the `StateListAnimator` to its end state gives
   scale 0.93 on Refresh Data, the back `ImageButton`, and a borderless button. This proves the theme wiring
   reaches every button style used, not just that some animator exists.
3. JVM parity test: the animator/drawable XML values equal the `WeatherThemeTokens` values; desktop
   pure tests cover the press target and the overshoot easing.
4. Emulator: `adb shell input motionevent DOWN` on Refresh Data → `screencap` → `UP`, then compare
   the pressed and idle frames (ripple and shrink visible). Also exercise the App Logs verbose toggle.
5. Desktop: rebuild, restart, click Settings → Refresh Data, and confirm visually.

## Docs

No living doc covers button styling. Update the `WeatherButtons.kt` KDoc to mention the shared
press animation.
