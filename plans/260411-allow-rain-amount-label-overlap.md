# Allow Rain Amount Labels to Overlap Probability Labels

## Problem

Rain amount annotations (e.g., `.083in`) are being suppressed on smaller widgets because they overlap with probability % labels in `drawnLabelBounds`. Log evidence shows:

- Small widget (height=337px): always `rainAmountSkipped: overlaps=true`
- Larger widget (height=397px): sometimes placed, sometimes skipped
- The 97% probability label itself is also `out_of_bounds` (too close to graph top)

Rain amount is high-value information that only appears for 97%+ probability periods. It should be shown even when probability labels occupy the same vertical space.

## Solution

Change the rain amount label collision check from `drawnLabelBounds` (contains both probability labels and rain amount labels) to a **separate rain-amount-only bounds list**. This means:

- Rain amount labels **can overlap** probability % labels (visually distinct: white bold 10dp with shadow vs. smaller colored percentage text)
- Rain amount labels **cannot overlap each other** (prevents two rain amount annotations from stacking)
- The `outOfBounds` check stays (must fit within graph area)

## Changes

### PrecipitationGraphRenderer.kt

1. Add `val rainAmountBounds = mutableListOf<RectF>()` before the rain period loop
2. Change overlap check from `drawnLabelBounds.any { RectF.intersects(it, bounds) }` to `rainAmountBounds.any { RectF.intersects(it, bounds) }`
3. Add placed rain amount bounds to `rainAmountBounds` instead of `drawnLabelBounds`
4. Add `widgetSize=${widthPx}x${heightPx}` and `drawnLabelCount` to rain amount debug logs

### No test changes needed

Existing tests that check for `rainAmountPlaced` will still pass — they use wide bitmaps (1000x400) where overlap with probability labels was never the issue. The collision that blocks placement only happens on smaller widget sizes (337px height).

## Why not try alternative positions first?

A fallback-position approach (try center, then lower, then force) adds complexity with inconsistent visual results. The rain amount label has a shadow layer (`#88000000`) designed for readability over other content, so overlap with probability labels is visually acceptable. Keeping rain-amount-on-rain-amount collision detection preserves readability where it matters most.