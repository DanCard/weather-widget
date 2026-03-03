# Fix Text Size Too Big

## Problem
The user reported that the text size is "way too big" after recent changes.
Inspection of `TemperatureGraphRenderer.kt` reveals:
- Day Labels: `24dp` (scaled)
- Temp Labels: `22dp` (scaled)

These values are indeed quite large for widget labels (standard is usually 12-14dp). It's possible they were increased previously or simply look too large in context with the new icons.

## Goal
Reduce text sizes to approximately half, or more standard legible sizes, as requested by the user.

## Implementation Plan

### 1. Update `TemperatureGraphRenderer.kt` (Daily View)
- Reduce `baseDayLabelSize`: `24f` -> `13f`
- Reduce `baseTempLabelSize`: `22f` -> `12f`
- This brings them closer to standard Android text sizes (12sp/14sp).

### 2. Update `HourlyGraphRenderer.kt` (Hourly View)
- Reduce hour label text size: `20f` -> `12f`
- Reduce temp label text size: `18f` -> `12f`
- Reduce "NOW" label text size: `16f` -> `10f`

### 3. Verification
- Verify that `dpToPx` is correctly applied (it appears correct).
- Ensure the new sizes are legible but not overwhelming.

## Files to Modify
- `app/src/main/java/com/weatherwidget/widget/TemperatureGraphRenderer.kt`
- `app/src/main/java/com/weatherwidget/widget/HourlyGraphRenderer.kt`
