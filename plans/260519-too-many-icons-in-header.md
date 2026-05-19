# Fix middle-icon overlap on narrow widgets (Pixel 7 Pro, 412dp)

## Context

Commit `61e9729` relocated the graph-selector and home/history/stations icons
into a "middle header" cluster, all positioned with
`layout_gravity="top|center_horizontal"` and offset via `marginEnd`/`marginStart`.

On a Pixel 7 Pro (1080×2340 @ 420dpi → **412dp wide**), this floating cluster
sits ~50dp too far left and visibly collides with the left-side content
(`☀️ 85.0° ☁️ +3.8 🌡️`). Screenshot confirms the overlap.

### Why the inline fallback didn't kick in

`TemperatureTouchTargets.kt` already implements an inline fallback for narrow
widgets, but it's gated:

```kotlin
val useInline = widthDp < 420 && isPrecipVisible   // lines 312, 354
```

`isPrecipVisible` is only true when the **precip probability %** TextView is
visible — not when the **precip delta** ("+3.8") is visible. On Pixel 7 Pro in
the temperature view, only the delta is showing, so `isPrecipVisible == false`
and inline mode never activates despite `widthDp < 420`.

### Floating-cluster geometry on 412dp (FrameLayout center math)

In a FrameLayout, `marginEnd` on a `center_horizontal` child shifts it LEFT by
`marginEnd/2`; `marginStart` shifts it RIGHT by `marginStart/2`.

| Icon | margin | offset from center | center px | span (26dp) |
|---|---|---|---|---|
| `graph_selector_icon` | marginEnd=88 | −44dp | 162dp | 149–175dp |
| `weather_stations_icon` | marginEnd=44 | −22dp | 184dp | 171–197dp |
| `home_icon` | none | 0 | 206dp | 193–219dp |
| `history_icon` | marginStart=44 | +22dp | 228dp | 215–241dp |

Centers are 22dp apart, but icons are **26dp wide → each pair overlaps by 4dp**.
Left content ends ~200dp; cluster starts at 149dp. NWS sits ~330dp; cluster
ends at 241dp. So ~90dp of wasted space on the right and ~50dp of overlap on
the left.

## Recommended approach: drop the `isPrecipVisible` gate

Activate inline mode for **any** widget narrower than 420dp.

In inline mode (already implemented), the four icons render inside the
existing left LinearLayout, immediately after the precip text. Each icon
becomes a `40dp × 40dp FrameLayout` with a 26dp ImageView/TextView centered
inside, spaced by `marginStart="4dp"`. Total inline cluster width ≈ 160dp,
which fits cleanly between the precip text (~165dp from left) and NWS
(~330dp from left) on a 412dp screen.

## Files to modify

**`app/src/main/java/com/weatherwidget/widget/handlers/TemperatureTouchTargets.kt`**
- Line 312, inside `positionCenterIcons`:
  ```kotlin
  val useInline = widthDp < 420 && isPrecipVisible
  ```
  →
  ```kotlin
  val useInline = widthDp < 420
  ```
- Line 354, inside `setupGraphSelectorShortcut`: same change.

Both functions already do the right thing in inline mode — they swap
visibility of the floating vs inline icon/touch-zone pairs. The
`isPrecipVisible` parameter remains in the function signature (callers still
pass it; we just stop reading it for the inline decision). Leave the
parameter in place to keep the diff small and avoid touching the 5 callers
(`TemperatureViewBinder.kt`, `CloudCoverViewHandler.kt`, `PrecipViewHandler.kt`,
plus the calls within these handlers).

### Log message touch-up (optional, same file, line 315)

The existing log line references the boolean — leave it; it will simply log
`isPrecipVisible=false useInline=true` which is informative.

## Why this is safe

1. **Inline-mode code path is already exercised in production** for widgets
   that show precip%. We're broadening the trigger, not introducing new code.
2. **No risk of emulator launcher-band issue** (see
   `memory/emulator_widget_click_drops.md`) — that only affects
   `top|center_horizontal` touch zones. Inline icons live inside a
   `top|start` LinearLayout and bypass the band entirely.
3. **Wider widgets (≥420dp) are unaffected** — they continue to use floating
   mode. So tablets, foldable unfolded, and the 5-cell home widget keep
   today's visual.
4. **All view modes get the fix** — `TemperatureViewBinder`,
   `CloudCoverViewHandler`, `PrecipViewHandler` all call into
   `positionCenterIcons` and `setupGraphSelectorShortcut`, so the change
   propagates uniformly.

## Verification

After installing the build on the Pixel 7 Pro:

1. Build & install:
   ```
   ./gradlew installDebug
   ```
2. Trigger a widget update (tap any tap-target, or pull-to-refresh).
3. Confirm visual layout in the **hourly Temperature view**:
   - Icons (📊 cycle, 🌡️ stations, 🏠 home, 📈 history) appear inline after
     the "+3.8" delta on the left side.
   - No overlap with `☀️ 85.0° ☁️ +3.8` content.
   - NWS / ⚙️ remain in their existing top-right position with no collision.
4. Repeat for the **Cloud Cover view** and **Precipitation view** — same
   inline placement.
5. Capture a screenshot from each view:
   ```
   PIXEL='adb-2A191FDH300PPW-upCZMc._adb-tls-connect._tcp'
   adb -s "$PIXEL" exec-out screencap -p > /tmp/p7p_after.png
   convert /tmp/p7p_after.png /tmp/p7p_after.jpg
   ```
6. Tail logs to confirm `useInline=true`:
   ```
   adb -s "$PIXEL" logcat -d -s HomeShortcut | tail -20
   ```
   Expect: `widthDp=412 isPrecipVisible=false useInline=true`.
7. Regression check on a **wider device or layout** (Samsung Fold unfolded,
   foldable emulator, or 5-cell home placement at ≥420dp):
   - Floating mode (`useInline=false`) still active.
   - Icons still appear in the middle of the header.

No unit tests cover this layout decision directly; verification is visual.
