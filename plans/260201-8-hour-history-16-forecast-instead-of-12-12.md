# Plan: Shift Hourly Widget "Now" Position

## Current Behavior
- 24 hours total displayed
- **12 hours history** + **12 hours forecast**
- "Now" is positioned in the **middle** (50%)

## Requested Change
- 24 hours total displayed (unchanged)
- **8 hours history** + **16 hours forecast**
- "Now" positioned at **1/3 from left** (~33%)

## Files to Modify

### 1. `app/src/main/java/com/weatherwidget/widget/WeatherWidgetProvider.kt`

**Lines 1096-1100** - Change hour range calculation:
```kotlin
// FROM:
val alignedCenter = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
val startHour = alignedCenter.minusHours(12)
val endHour = alignedCenter.plusHours(12)

// TO:
val alignedCenter = centerTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
val startHour = alignedCenter.minusHours(8)
val endHour = alignedCenter.plusHours(16)
```

**Lines 283-286** - Update navigation center calculation to match:
```kotlin
// FROM:
val startTime = centerTime.minusHours(12).format(...)
val endTime = centerTime.plusHours(12).format(...)

// TO:
val startTime = centerTime.minusHours(8).format(...)
val endTime = centerTime.plusHours(16).format(...)
```

### 2. `app/src/main/java/com/weatherwidget/widget/WidgetStateManager.kt`

**Lines 40-44** - Adjust navigation limits:
```kotlin
// FROM:
const val MIN_HOURLY_OFFSET = -6   // Only 6 hours back
const val MAX_HOURLY_OFFSET = 18   // 18 hours forward

// TO:
const val MIN_HOURLY_OFFSET = -4   // Reduced (since we show 8h history by default)
const val MAX_HOURLY_OFFSET = 12   // Adjusted for new layout
```

## Verification
1. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Add hourly widget to home screen
3. Verify "NOW" indicator appears approximately 1/3 from left edge
4. Verify 8 hours of history visible on left of NOW line
5. Verify 16 hours of forecast visible on right of NOW line
6. Test navigation arrows still work correctly within new bounds
