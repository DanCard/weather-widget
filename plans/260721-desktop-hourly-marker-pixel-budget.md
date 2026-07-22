# Desktop hourly footer: pixel-budget label cadence

**Date:** 2026-07-21
**Status:** Implemented 2026-07-21. Default popup footer went from every-2h (5 labels) to
every-1h (~9 labels) on the same ~10h span, no icon crowding; verified in the running app.
Added `labelIntervalForWidth` unit tests (density scales with width, divides-24 invariant,
width-unknown fallback, narrow-window 24h cap). User flagged they'd prefer even more test
coverage in plans up front — noted for future plans.

## Motivation

The bottom hour-marker strip on the desktop temperature (and precip/cloud) hourly graph
is sparse: on a default-zoom popup (~10h visible) it shows only `2p 4p 6p 8p 10p` — five
labels across a wide window. The cadence comes from `DesktopGraphUtils.labelIntervalFor`,
a fixed table keyed **only on visible span-hours**, never on pixel width:

```kotlin
totalSpanHours <= 6  -> 1
totalSpanHours <= 14 -> 2   // ← default view lands here → every 2h
totalSpanHours <= 28 -> 4
...
```

This table was tuned for the physically tiny **Android widget**. The desktop popup reuses
the same hours→step rule but is 4–5× wider, so identical cadence leaves big empty gaps —
and it doesn't get denser when the user makes the window bigger.

## Decision

Make the clock-hour footer cadence **pixel-budget driven** on desktop: density scales to
the available graph width and to how the user resizes the window, instead of a constant
step per span. Desktop-only; the Android widget's own label logic is untouched.

## Scope

- **Desktop only.** `footerLabels`/`labelIntervalFor` live in `DesktopGraphUtils` (desktop
  module), not `:shared`. The Android widget uses `HourlyGraphViewCommon.resolveHourLabel`
  and is not affected.
- **Clock-hour mode only** (spans ≤ 48h, `!isDateMode`). The multi-day **date mode** branch
  (one centered date per day + its existing slanted-overlap fallback) is unchanged.

## Design

### 1. New pure function: `labelIntervalForWidth`

```kotlin
/** Clock-friendly hour steps; every value divides 24 so `hour % interval == 0` gating holds. */
private val LABEL_INTERVAL_STEPS = intArrayOf(1, 2, 3, 4, 6, 8, 12, 24)

/**
 * Densest clock-hour label step (a divisor of 24) whose labels still fit the pixel budget:
 * pick the smallest step where (spanHours / step + 1) labels each get >= minLabelSpacingPx.
 * Falls back to 24 when even that overflows a very narrow window.
 */
fun labelIntervalForWidth(spanHours: Int, widthPx: Float, minLabelSpacingPx: Float): Int {
    if (widthPx <= 0f || minLabelSpacingPx <= 0f) return labelIntervalFor(spanHours)
    val maxLabels = (widthPx / minLabelSpacingPx).toInt().coerceAtLeast(1)
    return LABEL_INTERVAL_STEPS.firstOrNull { step -> spanHours / step + 1 <= maxLabels } ?: 24
}
```

Adds `3` and `8` to the ladder for finer granularity (both divide 24, preserving the
`hour % interval == 0` invariant the draw loop depends on).

### 2. Shared min-spacing helper

Both call sites already have `footer: HourlyFooter` + `scale` in scope. Add:

```kotlin
/** Per-label footprint budget: weather icon + short label text + breathing gaps. */
fun footerMinLabelSpacingPx(footer: HourlyFooter, scale: Float): Float =
    footer.iconPx + /*≈3 chars*/ footer.labelFontSize.value * scale * 2.2f + 10.dp-ish gap
```

(Exact text estimate finalized in code; the icon term is what keeps icons from colliding —
important because clock-hour mode has **no** overlap guard, unlike date mode.)

### 3. `footerLabels` gains width + spacing params

```kotlin
fun footerLabels(
    points: List<HourlyForecast>,
    totalSpanHours: Int,
    zone: ZoneId,
    widthPx: Float,               // NEW
    minLabelSpacingPx: Float,     // NEW
): List<FooterLabel>
```

Only the clock-hour branch changes:
`labelIntervalFor(totalSpanHours)` → `labelIntervalForWidth(totalSpanHours, widthPx, minLabelSpacingPx)`.
Date-mode branch unchanged.

### 4. Thread width through the two call sites (must agree)

Both must compute the same labeled indices or ghost labels misalign:

| Call site | File:line | Width source |
|-----------|-----------|--------------|
| Footer strip draw | `DesktopGraphUtils.drawHourlyFooterStrip` ~L426 | `widthPx` param (already present) |
| Ghost-line label alignment | `TemperatureGraph.kt` ~L714 | `w` (graph width, already in scope as `graphWidthPx = w`) |

Both pass `footerMinLabelSpacingPx(footer, scale)`.

`drawHourlyFooterStrip` is shared by Temperature, Precip, and CloudCover graphs (via
`hourlyGraphCanvasGeometry`), so all three footers get the denser cadence for free.

## Files touched

- `desktop/.../DesktopGraphUtils.kt` — add `labelIntervalForWidth`, `footerMinLabelSpacingPx`,
  `LABEL_INTERVAL_STEPS`; extend `footerLabels` signature; update its clock-hour branch and
  the `drawHourlyFooterStrip` call.
- `desktop/.../TemperatureGraph.kt` — pass `w` + spacing to the ghost-label `footerLabels` call.
- `desktop/.../DesktopGraphZoomTest.kt` — update the two existing `footerLabels(...)` calls to
  the new signature (pass an explicit wide width so cadence stays deterministic); add new
  `labelIntervalForWidth` unit tests (density scales with width; result always divides 24;
  narrow-window fallback to 24; wide window goes down to 1h).

## Verification

1. `./gradlew :desktop:test` — unit tests pass.
2. `scripts/buildStart-desktop.sh` — rebuild + relaunch daily app.
3. `touch ~/.local/share/weather-widget/.show`, screenshot: confirm the default popup now
   shows ~hourly labels (roughly double), and that resizing the window narrower thins them
   back out without icon collisions.

## Risks / notes

- Clock-hour mode has no existing overlap guard; the icon term in `minLabelSpacingPx` is what
  prevents crowding — must not drop it.
- The two `footerLabels` callers MUST pass identical width/spacing or ghost-line labels stop
  landing on hour marks.
- Keep `labelIntervalFor` (still the narrow-fallback and referenced by an existing invariant
  test); it's just no longer the primary driver of the footer.
