# Fix Day/Hour Label Baseline Position Using Font Metrics

## Problem

Day and hour labels at the bottom of graphs have their descenders (y, g, p, q) cut off at the canvas edge. Current approach uses a hardcoded pixel offset that doesn't properly account for font metrics.

## Root Cause

- Text is drawn at baseline coordinate (`heightPx - 1`)
- Font descenders extend **below** the baseline
- Without accounting for `Paint.FontMetrics.descent` value, descenders get cut off

## Solution

Calculate proper baseline position using `Paint.getFontMetrics()`:
- `descent`: distance from baseline to bottom of text (positive value for descenders)
- Position text so descenders fit within canvas with small padding

### Formula

```
baseline_position = heightPx - font_metrics.descent - small_padding
```

This ensures:
- Descenders fit within canvas bounds
- Small breathing room below text
- Works across all font sizes and densities

## Changes

### 1. TemperatureGraphRenderer.kt - Day Labels

**Location:** Lines 238-241

**Current code:**
```kotlin
val dayLabelY = (heightPx - 1).toFloat()
canvas.drawText(day.label, centerX, dayLabelY, textPaint)
```

**New code:**
```kotlin
val fontMetrics = textPaint.fontMetrics
val dayLabelY = heightPx - fontMetrics.descent - 1f
canvas.drawText(day.label, centerX, dayLabelY, textPaint)

// Add logging
Log.d(TAG, "Day label: text='${day.label}' baseline=${dayLabelY}px descent=${fontMetrics.descent}px heightPx=$heightPx")
```

### 2. HourlyGraphRenderer.kt - Hour Labels

**Location:** Lines 179-182

**Current code:**
```kotlin
val labelY = (heightPx - 1).toFloat()
canvas.drawText(hour.label, x, labelY, hourLabelTextPaint)
```

**New code:**
```kotlin
val fontMetrics = hourLabelTextPaint.fontMetrics
val labelY = heightPx - fontMetrics.descent - 1f
canvas.drawText(hour.label, x, labelY, hourLabelTextPaint)

// Add logging
Log.d(TAG, "Hour label: text='${hour.label}' baseline=${labelY}px descent=${fontMetrics.descent}px heightPx=$heightPx")
```

### 3. Keep Existing Logging

- Font size logging remains in both files (already added in previous changes)
- These logs show calculated font sizes and scale factors

## Expected Outcome

- Text descenders properly fit within canvas
- Consistent spacing at bottom across all widget heights
- Logging provides visibility into actual baseline calculations for debugging
- No more cutoff of "y", "g", "p", "q" characters

## Testing

1. Resize widget to various heights (1x2, 1x3, 2x3, etc.)
2. Check that day labels at bottom don't have cutoff descenders
3. Check that hour labels in hourly view don't have cutoff descenders
4. Review logs to verify baseline calculations are correct
