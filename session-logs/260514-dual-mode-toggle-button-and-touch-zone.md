# Session Log: Dual-Mode Toggle Button on Daily Header (2026-05-14)

## Overview

User requested a new button on the daily view's header row to toggle dual-source mode (the "show two bars" preference previously only configurable in Settings). The session evolved through icon brainstorming → bitmap-based implementation → multiple iterations to fix touch-zone alignment between the bitmap-drawn pill and the RemoteViews touch target. Final implementation: a `‖` (double vertical bar) glyph drawn into the bitmap header, with a wide (80dp) RemoteViews touch zone overlaid at a stable position 150dp from the widget's right edge. Active state shows a filled white pill with dark glyph; inactive state shows the glyph alone on transparent background. Tapping the button flips the global `KEY_SHOW_TWO_BARS` SharedPreference and refreshes the widget.

The session also included two adjacent UX tweaks: (1) increasing the Settings checkbox font for the same preference by ~30%, and (2) adding contextual right margin to the API label so it has breathing room from the gear icon in single-source mode.

A persistent friction point throughout the session was the bitmap-rendered visual not aligning with the dp-based touch zone. The root cause was identified as `fitCenter` scaling in `graph_view` plus `bitmapScale` ≠ 1, which introduced a ~30dp drift between where the pill *appears* on screen and where `marginEnd=150dp` lands. Final mitigation: widen the touch zone from 48dp to 80dp so the visible pill sits comfortably inside with ~30dp tolerance on each side.

## 1. User Prompts (Verbatim, Chronological)

For an unambiguous record of the brief evolution:

1. **Initial ask:**
   > I'd like to add a button on the daily view, for dual mode. Can you brainstorm on icons that suggest dual?

2. **Refining toward Unicode glyphs:**
   > Can you show me a brainstorm list if ansi characters that suggest dual?

3. **Picking the glyph + active state + settings font:**
   > Lets use ‖ for dual mode button on header row if there is room. Maybe between date and API. Show in inverted color if active, unless you have a better idea. Increase the font size on setting screen for dual mode setting. Maybe increase by 30%.

4. **(after a rejected `AskUserQuestion` prompt about active-state style):**
   > continue

5. **First on-device failure report + reposition request:**
   > Add tests for tap on dual mode in header. Doesn't work for me when double bar mode is active on emulator. Instead of deactivating dual api, I get sent to setting screen. Can we have the dual mode button be tied more to middle or date? When dual mode is activated the button moves. I'm hoping that putting it closer to date or middle might fix that.

6. **API spacing discussion:**
   > What do you think about moving the api button on header row to be more spaced from gear icon if there is only one api shown? Alternatively what do you think of centering the api text between the dual mode button and gear button?

7. **Picks via `AskUserQuestion`:**
   - "Which option should I plan?" → **Option A (Recommended)** — extra margin from gear in single-source mode only
   - "How much extra spacing for Option A?" → **~12dp (subtle)**

8. **Second on-device failure report + diagnostic request:**
   > On pixel 7 pro: the dual mode button in the header mostly doesn't work: 1) clicking on it usually activates api change. 2) when dual mode is active, clicking on it goes to temperate graph 3) Consider adding tests for this. View logs and or add logging if that helps.

9. **Session log request:**
   > write detailed session log to session-logs/ dir . Include all prompts

## 2. Icon Brainstorm Phase

User explicitly asked for two rounds of brainstorming before any code was written:

### Round 1 — general "dual" icons
Surfaced split-circles (`◐◑`, `☯`), split-squares (`◧◨◫`), parallel bars (`‖`, `⚌`), linked rings (`⚭`), letter pairings (`N+M`, `II`), comparison arrows (`⇄`), and layered shapes. Recommendation focused on three with strong silhouette at widget scale: stacked bars, split rectangle, "N+M" textual.

### Round 2 — narrowed to Unicode glyphs
User asked specifically for "ansi characters" (interpreted as Unicode glyphs since Android widgets don't render terminal ANSI). Top 5 picks:
1. `◫` — split square (best "two views, one container" semantics)
2. `⚌` — Tai Xuan Jing double bar (literally named "double")
3. `‖` — parallel vertical bars (minimal silhouette)
4. `◐◑` — paired half-circles (playful)
5. `⚭` — linked rings (best "joined sources" but shaky font fallback)

User picked `‖` and specified placement "between date and API" with "inverted color if active." The session then offered three active-state visuals via `AskUserQuestion` (filled pill, color swap, alpha change); the question was rejected and the user wrote "continue", so the implementation went with the recommended filled-pill (white pill, dark glyph) for active.

## 3. Initial Implementation

### Architecture decisions

- **Where to draw:** the daily header is rendered into a bitmap by `DailyForecastHeaderRenderer.drawHeader` (not via RemoteViews TextViews). The `‖` was added to the bitmap drawing, consistent with how the API label and gear icon are drawn.
- **Touch target:** a new `FrameLayout @+id/dual_touch_zone` was added to `widget_weather.xml`, positioned at `marginEnd=150dp` to overlay the pill. RemoteViews touch zones are how all clickable areas in the bitmap header work (`api_touch_zone`, `settings_touch_zone`, etc.).
- **State:** the preference is global, not per-widget — reuses the existing `WidgetStateManager.isShowTwoBarsEnabled()` / `setShowTwoBarsEnabled()` (added in 2026-05-13 for the dual-source bars feature). Tapping the button on any widget instance affects all of them, matching the existing Settings checkbox behavior.
- **Visibility:** the button shows only in graph mode, only when a distinct alternate source exists (`stateManager.getNextDisplaySource(appWidgetId) != displaySource`), and only when header `disclosure` is `FULL` or `NO_ICON` (the levels that fit a date).

### Files modified (initial implementation)

**Production:**
- `WidgetActions.kt` — new `ACTION_TOGGLE_DUAL_BARS` constant
- `WidgetRequestCodes.kt` — new `BASE_DUAL_TOGGLE = 350` + `dualToggle(id)` allocator
- `widget_weather.xml` — added `dual_touch_zone` `FrameLayout` (initially 48dp wide × 50dp tall at `marginEnd=150dp`); inserted after `settings_touch_zone` for top z-order priority
- `activity_settings.xml` — bumped `show_two_bars_checkbox` `textSize` from `14sp` → `18sp` (~29% increase, matching user's "~30%" ask)
- `DailyForecastGraphRenderer.kt` — added `showDualButton: Boolean` and `dualActive: Boolean` fields to `HeaderRenderData`
- `DailyForecastHeaderRenderer.kt` — new `drawDualButton(canvas, paints, widthPx, labelScale, density, active)` method; added `dualPaint`, `dualActivePaint`, `dualPillPaint` to `HeaderPaintSet` cache; updated date-placement logic to use `dualLeftEdge` as the right boundary instead of `apiLeft` when the button is shown
- `TemperatureTouchTargets.kt` — new `setupDualToggle(context, views, appWidgetId)` helper that builds the `ACTION_TOGGLE_DUAL_BARS` PendingIntent and binds it to `R.id.dual_touch_zone`
- `WidgetIntentRouter.kt` — new `handleToggleDualBars(...)` that flips `setShowTwoBarsEnabled` and re-renders via `refreshDailyView` + `triggerUiOnlyUpdate` (the latter ensures sibling widgets also update since the preference is global)
- `WeatherWidgetProvider.kt` — new `handleToggleDualBarsAction` and a corresponding `when` arm in `onReceive`
- `HeaderRemoteViewsBinder.kt` — added `dual_touch_zone` to `hideIconWidthControls` (hides on 1-icon-wide widgets)
- `DailyVisibilityManager.kt` — added `dual_touch_zone` to `setTextModeViews` (hides in text-only mode)
- `DailyViewHandler.kt` — wires up the click handler, computes `showDualButton`, sets `dual_touch_zone` visibility, populates `HeaderRenderData.showDualButton` / `dualActive`

**Tests (new file):**
- `app/src/test/java/com/weatherwidget/widget/DualToggleTouchZoneRoboTest.kt` — Robolectric tests verifying touch zone position, z-order vs `api_touch_zone` and `settings_touch_zone`, initial GONE visibility, `setupDualToggle` PendingIntent wiring (intent action + widget-ID extra), and request-code uniqueness

### Active-state visual

Implemented via the filled-pill approach:
- **Inactive:** glyph drawn in `HEADER_TEXT_COLOR` (white), no background, transparent.
- **Active:** white filled rounded rect (corner radius 6dp) behind the glyph; glyph drawn in `~#333333` (dark gray) for contrast.
- Paint constants:
  - `DUAL_GLYPH = "‖"`
  - `DUAL_TEXT_SIZE_DP = 20f`
  - `DUAL_PILL_PADDING_X_DP = 5f`, `Y = 2f`
  - `DUAL_PILL_CORNER_DP = 6f`
  - `DUAL_BUTTON_MARGIN_END_DP = 150f` — the pill's right edge sits this many dp from the bitmap's right edge

## 4. Touch-Zone Alignment Iterations (the core difficulty of the session)

### Iteration A: Initial position — pill drifts with API label width

**Symptom (reported as "When dual mode is activated the button moves"):** the pill was drawn at `apiLeft - gap`, i.e., relative to the API label's *left edge*. When dual mode flipped the label from `"Meteo"` (short, ~50dp) to `"Meteo - Silur"` (long, ~110dp), the API label expanded leftward and pushed the pill with it.

**Fix:** anchor the pill to a fixed offset from `widthPx` (the bitmap's right edge), independent of the API label:

```kotlin
val pillRight = widthPx - (DUAL_BUTTON_MARGIN_END_DP * labelScale).dp(density)
```

Also forced the date to skip its centered placement when the button is shown (since the pill at a fixed position can collide with a centered date on smaller widgets), and updated the touch zone XML to `marginEnd=150dp, width=48dp` to match.

### Iteration B: `labelScale` clamps to ≤ 1.0 — wrong when `bitmapScale > 1`

**Symptom:** in some configurations the pill drifted *toward* the right edge of the widget, away from the touch zone.

**Root cause:** `val labelScale = layout.bitmapScale.coerceAtMost(1f)` clamps the scale used for *visual sizing* to ≤ 1.0, but I was also using it for the *position offset*. When `bitmapScale > 1` (high-DPI bitmap), the clamped `labelScale = 1` under-shot the offset by a factor of `bitmapScale`.

**Fix:** use `bitmapScale` for the position offset, keep `labelScale` for sizing:

```kotlin
val pillRight = widthPx - (DUAL_BUTTON_MARGIN_END_DP * bitmapScale).dp(density)
```

### Iteration C: Date placement disappears

**Symptom:** with the pill now at a fixed position, the date "Thu 14" stopped rendering on the emulator's 4-col widget.

**Root cause:** my fix in iteration A had unconditionally forced the date to right-mode when the button was shown. Right-mode anchors at `widthPx - DATE_RIGHT_MARGIN_DP` (≈ `widthPx - 112dp`), which is *to the right of* the pill at `widthPx - 162dp` — i.e., the date would have to overlap the pill to fit there. The fit-check failed, so the date was hidden entirely.

**Fix:** restore the centered placement as the first choice, and when falling back to a right-anchored placement, anchor the date's right edge to just *left of* the pill (not to the regular `DATE_RIGHT_MARGIN_DP` band):

```kotlin
val rightX = if (header.showDualButton) {
    dateRightBoundary - gapPx - dateWidth / 2f   // just left of pill
} else {
    widthPx - (HeaderConstants.DATE_RIGHT_MARGIN_DP * labelScale).dp(layout.density)
}
```

### Iteration D: Touch zone misalignment on Pixel 7 Pro & emulator

**Symptom (from user):**
> 1) clicking on it usually activates api change. 2) when dual mode is active, clicking on it goes to temperature graph

This was the most frustrating iteration. Repro on emulator confirmed: empirically, taps in the visible pill area mostly fired `ACTION_TOGGLE_API` or `ACTION_DAY_CLICK`, not `ACTION_TOGGLE_DUAL_BARS`.

**Diagnostic approach:**

1. Added a `Log.d` in `drawDualButton` to print `widthPx`, `bitmapScale`, `density`, computed `pillRight` in bitmap-px.
2. Cleared logcat, broadcast `ACTION_REFRESH`, captured logs:
   ```
   drawDualButton: widthPx=731.0 bitmapScale=0.488 density=2.625
                   MARGIN_DP=150.0 pillRight_bmp=538.6
   ```
3. Probed the touch zone empirically by tapping at multiple x-coordinates and watching which intent action fired:

   | Tap x | Action fired |
   |---|---|
   | 1050 | `ACTION_DAY_CLICK` |
   | 1100 | (in temp view) `ACTION_CYCLE_ZOOM` |
   | **1130** | **`ACTION_TOGGLE_DUAL_BARS` ✓** |
   | 1160 | `ACTION_TOGGLE_DUAL_BARS` ✓ |
   | 1190 | `ACTION_TOGGLE_DUAL_BARS` ✓ |
   | 1220 | `ACTION_TOGGLE_DUAL_BARS` ✓ |

   So the touch zone's left edge was at ~x=1115 (between 1100 and 1130), right edge at ~x=1241 (48dp width).

4. Zoomed into the rendered pill via `convert ... -crop 400x100+1000+85 -scale 800x200` and visually measured the pill at screen `x≈1115-1155`.

**Root cause:** `graph_view` uses `scaleType="fitCenter"` with `bitmapScale=0.488`. The bitmap is rendered at `widthPx=731` (downscaled for memory) and stretched to fill `graph_view` on screen. After the scaling math, the pill ends up at ~198dp from `widget_root.inner_right` (the bitmap's "150dp from its right edge" lands at ~198dp from the widget's content-area right edge once the bitmap scale, `graph_view`'s 4dp margin, and `widget_root`'s 8dp padding are accounted for).

The 48dp-wide touch zone at `marginEnd=150dp` covers 150–198dp from `widget_root.inner_right` — its left edge sits *exactly at* the pill's left edge. Any tap a single dp to the left of the pill missed the zone entirely and fell through to whatever zone sat underneath:
- The api_touch_zone (declared at `marginEnd=20dp, width=90dp` → covers 20–110dp from right) was *not* in the overlap area, but for taps further left of the pill, the `graph_day_zones` LinearLayout (covering the bitmap content area, including the header strip) caught them and fired `ACTION_DAY_CLICK`.
- Tapping just barely on the pill's right edge worked because that fell inside the touch zone's right edge.

So the user's two failure modes mapped exactly to the two zones underneath:
- Tap slightly **right** of pill → empty area → falls to `api_touch_zone` → `ACTION_TOGGLE_API`
- Tap on or **left** of pill → falls to `graph_day_zones` → `ACTION_DAY_CLICK` → navigates to hourly temperature view

**Fix:** widened the touch zone from `width=48dp` to `width=80dp`. Same `marginEnd=150dp`. Touch zone now covers 150–230dp from `widget_root.inner_right`, with the visible pill at ~183–198dp comfortably centered with ~30dp tolerance on each side.

The wider zone stops *exactly* at the api_touch_zone's right edge (110dp from right) with no overlap — verified by a new test `dual_touch_zone does not overlap api_touch_zone`.

**Post-fix probe:**

| Tap x | Action fired |
|---|---|
| 1040, 1060 | `ACTION_TOGGLE_DUAL_BARS` ✓ (was failing before) |
| 1080, 1100, 1120, 1140, 1160 | `ACTION_TOGGLE_DUAL_BARS` ✓ |
| 1300, 1400, 1500 | `ACTION_TOGGLE_API` ✓ (api zone unchanged) |

## 5. API Breathing Room (Option A, +12dp single-source margin)

Adjacent UX request triggered by the dual-button refactor: in single-source mode, the gap between API text ("NWS" / "Meteo") and the gear icon felt cramped (only ~14dp of visible space). In dual-source mode the gap is fine because the long label fills the space.

User asked between two options:
- **A:** more margin from gear when only one API shown
- **B:** center API between dual button and gear

Recommended **A** because:
- Keeps API right-anchored (a learned convention)
- Solves the cramping without introducing position variability
- Option B would put ~41dp gap from gear in single mode — felt "floating" rather than balanced

User picked A at "+12dp (subtle)".

### Implementation

- New constant `API_SINGLE_SOURCE_EXTRA_MARGIN_DP = 12f` in `HeaderConstants.kt`.
- `DailyForecastHeaderRenderer.drawHeader`: detect single vs dual via `apiSourceText?.contains(" - ")` and apply extra margin only when single. `apiMarginEndPx` is reused for both the draw position (`apiX`) and the `apiLeft` used as a date boundary, so they stay in sync.
- `HeaderWidthChecker.resolveApiLeftPx`: mirrors the same single-vs-dual margin so disclosure-level decisions agree with the renderer's actual placement (otherwise `resolveHeaderDisclosure` could say FULL fits when the renderer would render it cramped, or vice versa).
- No XML changes: `api_touch_zone` at `marginEnd=20, width=90` (covers 20–110dp from right) already covers the new single-mode position (~44–94dp from right for "Meteo").

## 6. Automated Tests

`app/src/test/java/com/weatherwidget/widget/DualToggleTouchZoneRoboTest.kt` (new) — 9 Robolectric tests:

1. `dual_touch_zone is top end with expected margin and size` — asserts gravity = `TOP|END`, `rightMargin = 150*density`, `width = 80*density`, `height = 50*density`.
2. `dual_touch_zone touch zone margin matches renderer constant` — couples the XML `marginEnd` value to `DailyForecastHeaderRenderer.DUAL_BUTTON_MARGIN_END_DP` so they can't drift independently.
3. `dual_touch_zone is wide enough to tolerate bitmap-to-screen offset` — asserts width ≥ 64dp. This is the regression guard against the original 48dp narrow zone that caused the failure modes.
4. `dual_touch_zone is declared after api_touch_zone for touch priority` — z-order check: declared *after* means *on top* in FrameLayout dispatch order.
5. `dual_touch_zone is declared after settings_touch_zone` — same reason.
6. `dual_touch_zone starts hidden` — defaults to `GONE`; `DailyViewHandler` flips it to `VISIBLE` only when conditions are met.
7. `dual_touch_zone does not overlap api_touch_zone` — guards against future over-widening; checks `dualLeftFromRight >= apiRightFromRight` where both are computed from layout params.
8. `setupDualToggle wires a broadcast pending intent for ACTION_TOGGLE_DUAL_BARS` — exercises the helper, looks up the registered PendingIntent via `FLAG_NO_CREATE`, and asserts its saved intent has the correct action and widget-ID extra.
9. `dualToggle request code is distinct from neighboring toggles` — guards against accidental collision with `apiToggle`, `precipToggle`, `cycleZoom`.

All 9 pass.

## 7. Architectural Patterns Reused

- **Touch zone + bitmap-drawing pattern**: every other clickable header element (`api_touch_zone`, `settings_touch_zone`, `current_temp_zone`, `precip_touch_zone`) uses the same pattern — a transparent `FrameLayout` overlay in `widget_weather.xml` at the position where the bitmap draws a visual. The dual button follows this exact pattern.
- **`HeaderRenderData` field extension**: added `showDualButton` and `dualActive` with defaults so existing call sites and tests continue to work unchanged.
- **`HeaderPaintSet` cache**: added three new paints (`dualPaint`, `dualActivePaint`, `dualPillPaint`) to the existing paint-cache structure, keyed by the same `labelScale-deltaColor-...` cache key.
- **Intent action + request-code allocator**: `WidgetActions.ACTION_TOGGLE_DUAL_BARS` follows the existing `ACTION_TOGGLE_*` convention; `WidgetRequestCodes.dualToggle(id) = id*10000 + 350` follows the existing base-offset pattern (`BASE_DUAL_TOGGLE = 350` slotted between `BASE_PRECIP_TOGGLE = 300` and `BASE_CYCLE_ZOOM = 400`).
- **`WidgetIntentRouter.handleToggleDualBars`**: mirrors `handleToggleApi`'s structure (try/catch wrapper → internal handler → refresh + log).
- **Z-order priority via XML declaration order**: `dual_touch_zone` declared last in `widget_weather.xml` so it wins overlap dispatch.

## 8. Files Touched

**Production:**
- `app/src/main/java/com/weatherwidget/widget/WidgetActions.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetRequestCodes.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/HeaderConstants.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/HeaderRemoteViewsBinder.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/HeaderWidthChecker.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyVisibilityManager.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/TemperatureTouchTargets.kt`
- `app/src/main/java/com/weatherwidget/widget/handlers/WidgetIntentRouter.kt`
- `app/src/main/java/com/weatherwidget/widget/DailyForecastGraphRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/DailyForecastHeaderRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`
- `app/src/main/res/layout/widget_weather.xml`
- `app/src/main/res/layout/activity_settings.xml`

**Tests:**
- `app/src/test/java/com/weatherwidget/widget/DualToggleTouchZoneRoboTest.kt` (new, 9 tests)

**Plan file (working notes):**
- `~/.claude/plans/i-d-like-to-add-squishy-charm.md` — used twice during the session (once for the API breathing-room sub-task, once briefly for considering session-log structure)

## 9. Verification Commands

```bash
# Unit tests for the new touch zone
./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.widget.DualToggleTouchZoneRoboTest"

# Sanity: existing settings-related touch zone tests still pass
./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.widget.SettingsTouchZoneRoboTest"

# Build + install on emulator
./gradlew :app:installDebug

# Force re-render of dual-mode widget
adb -s emulator-5554 shell am broadcast -a com.weatherwidget.ACTION_REFRESH

# Probe touch zone empirically (showed which x-coords fire which action)
adb -s emulator-5554 logcat -c
for X in 1040 1080 1120 1160 1200 1300 1400 1500; do
  adb -s emulator-5554 logcat -c
  adb -s emulator-5554 shell input tap $X 110
  sleep 3
  adb -s emulator-5554 logcat -d | grep -oE "action=com.weatherwidget.ACTION_[A-Z_]+" | head -1
  adb -s emulator-5554 shell input keyevent KEYCODE_BACK
done

# Screenshot + crop header band
adb -s emulator-5554 exec-out screencap -p > /tmp/header.png
convert /tmp/header.png -crop 1768x200+0+0 +repage /tmp/header.jpg
```

## 10. Known Limitations & Future Work

- **Bitmap-rendered visual + dp-based touch zone is fragile**: the alignment drift between bitmap coords and screen dp (driven by `fitCenter` scaling and `bitmapScale ≠ 1`) is masked here by the wide 80dp touch zone but never *eliminated*. A more architecturally correct fix would be to render the pill as a real RemoteViews TextView with a background drawable — then the visible widget *is* the touch target, no alignment math required. Considered in-session but deferred because the wider zone is sufficient and lower-risk for now.
- **Pixel 7 Pro verification not directly tested in this session** — the emulator repro is consistent with the user's report, and the dp-based touch zone tolerance is the same across densities, so the fix should transfer. A direct on-device check with the Pixel 7 Pro is recommended before considering this fully closed.
- **Active-state contrast** depends on the system theme — the white-pill / dark-glyph combination is well-readable against the glass-aesthetic widget background but hasn't been tested against unusual home-screen wallpapers. The widget's own dark background drawable mitigates most cases.

## 11. Metadata

- **Date:** 2026-05-14
- **Devices used:** emulator-5554 (1768×2208, density 420), Pixel 7 Pro (user-reported, not directly accessible in this session)
- **Status:** Feature complete on emulator. 9 new tests passing. The Pixel 7 Pro repro that prompted iteration D should now be resolved by the widened touch zone (same dp-scale tolerance applies on that device's density).
- **Iterations driven by:** on-device testing, logcat probing of intent actions per tap x-coordinate, and ImageMagick crops to measure visible pill position at pixel precision.
- **Companion feature:** the dual-source bars themselves (the *bars*, not the *toggle*) were implemented in the previous session — see `session-logs/260513-dual-source-daily-bars.md`. This session adds the per-widget toggle button for that feature.
