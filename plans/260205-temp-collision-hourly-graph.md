# Fix Hourly Graph Temperature Label Overlap

## Problem
On Pixel 7 Pro and two emulators, temperature labels on the hourly graph overlap each other. The Samsung doesn't have this issue (likely larger widget area / different density gives more vertical spread). Additionally, a temperature label often appears near the "NOW" line, which isn't useful.

## Root Cause
In `HourlyGraphRenderer.kt` (lines 172-208), **5 "special" temperature labels** are drawn unconditionally with **no overlap detection**:
1. Daily high (max temp)
2. Daily low (min temp)
3. History high (max temp before current time)
4. Start point (index 0)
5. End point (last hour)

Labels are placed with fixed offsets (14dp below or 4dp above the curve) based on whether the point is in the upper/lower half of the graph. When the temperature range is narrow or the graph area is small, these labels stack on top of each other.

## File to Modify
- `app/src/main/java/com/weatherwidget/widget/HourlyGraphRenderer.kt`

## Plan

### 1. Reduce the number of special labels to just HIGH and LOW
The start/end/pastHigh labels add clutter without much value. The user primarily cares about the temperature range. Remove `pastHighIndex`, index `0`, and `hours.size - 1` from `specialIndices`.

**Before:**
```kotlin
val specialIndices = mutableSetOf<Int>()
if (dailyHighIndex >= 0) specialIndices.add(dailyHighIndex)
if (dailyLowIndex >= 0 && dailyLowIndex != dailyHighIndex) specialIndices.add(dailyLowIndex)
if (pastHighIndex >= 0 && pastHighIndex !in specialIndices) specialIndices.add(pastHighIndex)
specialIndices.add(0)
if (hours.size > 1) specialIndices.add(hours.size - 1)
```

**After:**
```kotlin
val specialIndices = mutableListOf<Int>()
// Low priority first so it gets drawn first and is never skipped
if (dailyLowIndex >= 0) specialIndices.add(dailyLowIndex)
if (dailyHighIndex >= 0 && dailyHighIndex != dailyLowIndex) specialIndices.add(dailyHighIndex)
```

### 2. Add overlap detection with LOW temperature priority
Change from `Set` to ordered `List` where low temp is first (highest priority). Track drawn label bounding rectangles and skip any label that would overlap a previously drawn one.

```kotlin
val drawnLabelBounds = mutableListOf<RectF>()

for (idx in specialIndices) {
    val sx = points[idx].first
    val sy = points[idx].second
    val label = String.format("%.0f°", hours[idx].temperature)
    val textWidth = tempLabelTextPaint.measureText(label)
    val textHeight = tempLabelTextPaint.textSize
    val clampedX = sx.coerceIn(textWidth / 2f, widthPx - textWidth / 2f)

    val graphCenter = graphTop + graphHeight / 2f
    val drawBelow = sy < graphCenter
    val labelY = if (drawBelow) sy + dpToPx(context, 14f) else sy - dpToPx(context, 4f)

    // Build bounding rect for this label
    val bounds = RectF(
        clampedX - textWidth / 2f,
        labelY - textHeight,
        clampedX + textWidth / 2f,
        labelY
    )

    // Skip if overlaps any already-drawn label
    val overlaps = drawnLabelBounds.any { RectF.intersects(it, bounds) }
    if (!overlaps) {
        canvas.drawText(label, clampedX, labelY, tempLabelTextPaint)
        drawnLabelBounds.add(bounds)
    }
}
```

### 3. Suppress temperature labels near the NOW line
After computing the NOW line X position, skip any label whose X coordinate is within ~1.5 hour-widths of the NOW line. This prevents the distracting label near the current time indicator (the current temp is already shown elsewhere in the widget).

```kotlin
// Compute NOW x position early (before label drawing)
val currentHourIndex = hours.indexOfFirst { it.isCurrentHour }
val nowX: Float? = if (currentHourIndex != -1) {
    val minutesOffset = Duration.between(hours[currentHourIndex].dateTime, currentTime).toMinutes()
    points[currentHourIndex].first + (minutesOffset / 60f) * hourWidth
} else null

// In label loop, add proximity check:
val tooCloseToNow = nowX != null && kotlin.math.abs(clampedX - nowX) < hourWidth * 1.5f
if (!overlaps && !tooCloseToNow) {
    canvas.drawText(label, clampedX, labelY, tempLabelTextPaint)
    drawnLabelBounds.add(bounds)
}
```

**Exception:** Always draw the low temp label even if near NOW (since user wants low prioritized). Only suppress high/other labels near NOW.

### 4. Keep the NOW line + "NOW" text (no temperature on it)
The NOW line itself (dashed orange + "NOW" text) stays as-is. There is no temperature label *on* the NOW line — the issue was that a *nearby* special index label appeared close to it. Step 3 above fixes this.

## Summary of Changes

| What | Action |
|------|--------|
| Start/end point labels | Remove |
| Past high label | Remove |
| Daily low label | Keep (highest priority, always drawn) |
| Daily high label | Keep (drawn if no overlap with low) |
| Overlap detection | Add bounding-rect collision check |
| Labels near NOW line | Suppress (except low temp) |
| NOW line + "NOW" text | Keep unchanged |

## Verification
1. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Test on Pixel 7 Pro — verify no label overlap in hourly view
3. Test on emulators (Foldable, Medium Phone) — verify no overlap
4. Test with narrow temperature ranges (e.g., 65-68°F) — these are the worst case for overlap
5. Test with wide temperature ranges — verify both high and low labels still appear
6. Verify NOW line still renders correctly without nearby temperature clutter
