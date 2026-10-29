# Shared daily rain-label layout

## Problem

Fold 4, history Tue→Wed: the night "10%" sat under Wed's `45.7°`, in the day-name row. Desktop draws
the same label between Tue's `48.0°` and Wed's `45.8°`, which the user prefers.

Logcat (2026-10-29 09:21):

```
nightRainLabel position: date=2026-10-27 text="10%" ... homeTop=386.97 placement=down offsetDp=(0.0,10.0) obstacles=6
```

The collision search (`DailyRainLabelPlanner.placeNightLabel`) was already shared, but each platform
built its inputs separately (~80 lines each), and they had drifted:

| Input | Android | Desktop |
|---|---|---|
| Night bottom limit | `heightPx − 6 dp`: inside the day-name row | top of the day-name row |
| Night anchor | higher low label's **computed** baseline + paint descent | higher low label's top + left label height |
| Edge margin | 4 dp | 2 px × scale |
| Day label above the ceiling | drawn there anyway ("forced") | clamped to the ceiling |
| Peak ceiling | bitmap top (0) | label ceiling |
| No high label | day label skipped | drawn at `top + 10` |

Nothing kept the night label from sinking below the lower neighbour's low label.

## Change

1. New `:shared` runner `DailyRainLabelLayout` owns the whole rule for the day %, the peak hour and
   the night %: font scale, anchors, gaps, ceiling, edge fit (0.85 fallback, then column centre),
   collision search, bottom limit and the trace. Each platform's `Host` only measures text at a
   kind's base size times a scale; the platform draws the returned boxes.
2. **"In between" rule.** The night label's bottom never goes below the bottom of the lower of the
   two neighbouring low labels, and never into the day-name row. Inside that band the search keeps
   its least-move / least-overlap choice, so a slight overlap beats sinking below both lows (user
   2026-10-29: "slight overlap ok").
3. Unified inputs: night anchor = bottom of the higher **drawn** low label. Bottom limit =
   `DailyBottomStack.dayLabelTop`. Edge margin = `RAIN_LABEL_EDGE_MARGIN_DP`. The day % clamps to the
   ceiling, and its unclamped top still feeds `DailyTopStack` headroom. The peak is drawn only when
   it clears the ceiling. No high label = no day %.
4. The trace moves to `:shared` (`DailyRainLabelLayout` tag, `platform=`), and the night line now
   lists its obstacle boxes, so a placement can be explained from logcat alone.

## Tests

- `:shared` `DailyRainLabelLayoutTest`: the Fold geometry. It fails on the old code (night label
  drops under the lower low) and passes on the new. Also covers day clamp/raw top, peak skip, no-high
  skip, and the edge-fit fallbacks.
- The existing Android Robolectric rain-label tests and desktop tests still pass.

## Verify

Install on the Fold. Restart the desktop app. Screenshot the Tue–Wed history on both, and read the
`nightRainLabel position` lines.
