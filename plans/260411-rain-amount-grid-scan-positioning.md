# Rain Amount Grid-Scan Positioning

## Problem
Rain amount text (e.g., "0.12 in") overlaps with % probability labels (e.g., "37%") and the "NOW" label in the precipitation graph. The current positioning logic is too constrained and has no awareness of the NOW indicator.

## Root Cause
1. **Constrained positioning**: Rain amount locks horizontally to the center of the rain period and only tries 5 vertical ratios (0.75, 0.5, 0.85, 0.35, 0.95) in the fill area below the curve. Never considers other open positions.
2. **No NOW awareness**: `drawNowIndicator` is called *after* rain amount placement (line 646) without `drawnBounds`, so neither element knows about the other.
3. **Bounds not tracked**: Rain amount `finalBounds` is never added to `drawnLabelBounds`, so cloud icon watermark and day labels could overlap it too.
4. **Fallback skips collision check**: The fallback at line 606 draws at 0.75 ratio regardless of overlaps.

## Solution
Adopt the same top-to-bottom, left-to-right grid-scan approach already used by the cloud icon watermark (lines 723-774).

### Step 1: Extract NOW bounds computation in `GraphRenderUtils.kt`
Add a `computeNowLabelBounds()` function that returns a `RectF?` for the NOW label position without drawing it. This lets `PrecipitationGraphRenderer` pre-compute where NOW will appear.

### Step 2: Pre-compute NOW bounds before rain amount loop
In `PrecipitationGraphRenderer`, call `computeNowLabelBounds()` and add the result to a combined collision set: `drawnLabelBounds + nowBounds`.

### Step 3: Replace rain amount positioning with grid scan
Replace lines 567-611 with a grid scan:
- Outer loop: `yFractions` (top to bottom: 0.12, 0.25, 0.38, 0.5, 0.65, 0.8)
- Inner loop: `xFractions` (left to right: 0.15, 0.3, 0.45, 0.6, 0.75)
- Each candidate: check collision against combined bounds, check within graph area
- First clear spot wins
- If no clear spot: draw at the position with minimum overlap area (least-overlapping fallback)

### Step 4: Track rain amount bounds
After placing rain amount text, add its bounds to `drawnLabelBounds` so cloud icon watermark and day labels avoid it.

### Step 5: Remove old fallback
Remove the fallback at line 606 that draws at 0.75 ratio without collision checking.

## Files to Modify
| File | Change |
|------|--------|
| `GraphRenderUtils.kt` | Add `computeNowLabelBounds()` helper |
| `PrecipitationGraphRenderer.kt` | Pre-compute NOW bounds, grid-scan rain amount, track bounds |

## Testing
1. Build and install on emulator
2. Verify rain amount text no longer overlaps % labels or NOW label
3. Verify rain amount still appears in open areas
4. Verify cloud icon still finds clear positions
