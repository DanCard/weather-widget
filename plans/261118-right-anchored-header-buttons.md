# Simplify Header Button Placement: Right-of-Center Anchoring

## Problem & Evidence

On Pixel 7 Pro (`373dp` wide, verified in the touch-zone screenshot earlier today):
- The left cluster (`icon + 53.3° + -7.2 + 72%`) takes `~150dp` (~40% of the header), while the right cluster (`NWS + gear`) takes `~78dp` (~21%).
- The free corridor is `[156dp, 289dp]` (`133dp` wide), well to the right of the widget's center (`186.5dp`).
- Yet the current placement stack (`HeaderFit` + `HeaderLine` + `floatingSeamOffset` + `centreButtonsLayout` + `INLINE` fallback) tries to center buttons around `width / 2`:
  - `floatingSeamOffset` targets `width / 2 + buttonsWidth / 2`, then reserves a hypothetical 4-button width + nominal date + cross-source reserves (`HeaderLine`).
  - `HeaderFit` has three placement rungs (`CENTER/RIGHT_OF_MIDDLE`, `CENTER/MIDDLE`, `INLINE`), a 24-iteration bisection for `centreZone`, a separate `squeeze()` for `INLINE`, and `buttonsTouchSpan()` that stretches the outer buttons asymmetrically (`extraStartDp=7dp, extraEndDp=28dp`).
  - On Android, `hourly_center_header_container` uses `layout_gravity="top|center_horizontal"` with `MARGIN_LEFT = offsetDp`, which halves the shift in `FrameLayout` — leaving buttons crowded against `72%` on the left with a large unused gap before `NWS`.
  - Five duplicate `*_inline` button views exist in `widget_weather.xml` just for the `INLINE` fallback.

## Proposed Replacement

Replace the seam/centering/inline machinery with one simple **right-anchored (right-of-center)** rule shared across `:shared`, `:app`, and `:desktop`:

### 1. `:shared` — Simplify `HeaderFit`, remove `HeaderLine`

- **Keep the exact same priority order**:
  1. Right cluster (source label + gear) — always shown
  2. Current temperature
  3. Rain %
  4. Delta from yesterday
  5. Action buttons (`baseButtons`, then `home` if wanted, then `months` if wanted)
  6. Weather icon
  7. Date (Daily only)
  8. `"from yest"` caption (never beside rain %, never narrows buttons)
- **Single button placement rule**:
  - Buttons sit **right-aligned** against the right cluster: `buttonsRight = width - rightCluster - gap`, growing leftward (`buttonsLeft = buttonsRight - buttonsWidth`).
  - Because `HOME` is leftmost and `HISTORY` is rightmost (`HeaderButtons.ORDER`), adding/removing `HOME` or switching Daily ↔ Hourly grows/shrinks leftward into the open space without moving `HISTORY` or `OBSERVATIONS`.
  - If `count * buttonWidth` exceeds the available corridor `(rightEdge - gap) - (leftEnd + gap)`, buttons narrow evenly by direct division (`corridor / count`) down to `minButtonWidth` — no bisection, no `INLINE` rung, no `Seam` enum.
- **Date placement (Daily only)**:
  - Pinned to the widget's horizontal center (`dateLeft = (width - date) / 2f`) whenever it fits cleanly between `leftEnd + gap` and `buttonsLeft - gap`; dropped otherwise — so toggling API sources, rain %, or the `Home` button never shifts the date.
- **Stable right cluster across API toggles**:
  - `rightCluster` uses the widest enabled source label (pure in-memory list of visible sources, no DB query), so `marginEnd` and button positions stay pinned when toggling between short (`NWS`) and wider (`Meteo` / `Google`) API labels.
  - On narrow widgets (Pixel 7 Pro `373dp`): `[Temp -Delta Rain%]     [Home Obs History] [NWS ⚙]`
  - On wide widgets (Fold / Desktop): `[Temp -Delta Rain%]   [Wed 18]   [Home Obs History] [NWS ⚙]`
- **Remove dead complexity**:
  - Delete `HeaderLine.kt` and `HeaderLineTest.kt`.
  - Remove `Seam`, `ButtonPlacement.INLINE`, `floatingSeamOffset`, `buttonsTouchSpan`, `sourceLeft`, and `inline*` fields/math from `HeaderFit.kt`.

### 2. `:app` (Android) — Single `top|end` button row

- **`app/src/main/res/layout/widget_weather.xml`**:
  - Change `hourly_center_header_container` from `layout_gravity="top|center_horizontal"` to **`layout_gravity="top|end"`** (with static fallback `layout_marginEnd="80dp"` for API 26–30).
  - Remove the 5 duplicate `*_inline` button views inside `current_weather_container`.
- **`TemperatureTouchTargets.kt` & `HeaderWidthChecker.kt` & callers**:
  - Position `hourly_center_header_container` with a single `setViewLayoutMargin(MARGIN_END, (measured.rightCluster + measured.gap) / density)` on API 31+.
  - Size each visible button zone uniformly to `zoneDp` (remove `CentreButtonsLayout`, `extraStartDp`/`extraEndDp`, and all inline binding/visibility code).
  - Update `DailyForecastHeaderRenderer.kt` so the painted date checks `dateRightBoundary = buttonsLeft - gap` when buttons are shown.
  - Remove `*_inline` entries from `DebugTouchZoneOverlay.kt`.

### 3. `:desktop` — Match the same right-anchored layout

- **`DesktopWidgetHeader.kt`**:
  - Use the simplified `HeaderFit.resolve(measured)` directly.
  - Place the right cluster (`sourceLabel` + `gear`) at the right edge of the header `Row`.
  - Place buttons right-aligned immediately left of the right cluster (at `buttonsSpan`).
  - Place the Daily date centered in the space between the left cluster and the buttons (at `dateLeft`).

### 4. Tests

- Rewrite `HeaderFitTest.kt` for the simplified right-anchored model; delete `HeaderLineTest.kt` and `HeaderWidthCheckerLineTest.kt`.
- Update affected Android and Desktop header tests (`PositionDailyIconsRoboTest`, `DailyIconZoneWidthTest`, `HeaderWidthCheckerHourlyFitTest`, `DailyForecastHeaderDateSlotTest`, `DailyForecastGraphRendererRoboTest`, `DailyForecastHeaderDeltaLabelTest`, `WeatherObservationsShortcutTest`, `DesktopHeaderFitTest`, `DesktopHeaderDateIntegrationTest`, `HeaderRainChanceNoWrapTest`).

## Verification

1. Run `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh` (cross-platform change).
2. Enable `com.weatherwidget.debug.TOUCH_ZONES` on Pixel 7 Pro and visually inspect both Daily and Hourly views (with and without `Home`) via screenshot, then turn the overlay back off.
3. Verify the desktop app launches and renders the header cleanly.
