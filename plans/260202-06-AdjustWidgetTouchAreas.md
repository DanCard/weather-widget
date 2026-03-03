# Plan: Fix Navigation Arrow Visibility for Incomplete Data

## Problem
When Saturday (Feb 7) is the last day with complete NWS data:
1. Right arrow still shows even though there's no more useful data
2. Clicking right arrow causes navigation, resulting in lost columns (widget shows 4 instead of 5 days)

## Root Cause
The current navigation check `maxDate > currentCenter` doesn't account for widget width.

For a 5-column widget showing offsets `[-1, 0, 1, 2, 3]`:
- Rightmost visible day = center + 3
- When center = Feb 4, rightmost = Feb 7 (Saturday)
- maxDate = Feb 7, currentCenter = Feb 4
- Check: Feb 7 > Feb 4 = TRUE → arrow shows, navigation allowed
- After navigation: center = Feb 5, tries to show Feb 4-8
- Feb 8 is incomplete → only 4 days shown → "lost column"

## Solution
The right arrow should only show if there's data for the rightmost day that WOULD be visible after navigation:
- newRightmostDay = currentCenter + 1 + maxOffset
- canRight = maxDate >= newRightmostDay

Similarly for left:
- newLeftmostDay = currentCenter - 1 + minOffset (minOffset is negative)
- canLeft = minDate <= newLeftmostDay

### Calculate maxOffset from numColumns
| numColumns | dayOffsets | maxOffset |
|------------|------------|-----------|
| 1 | [0] | 0 |
| 2 | [0, 1] | 1 |
| 3 | [-1, 0, 1] | 1 |
| 4 | [-1, 0, 1, 2] | 2 |
| 5 | [-1, 0, 1, 2, 3] | 3 |
| 6+ | [-1, 0, 1, 2, 3, 4+] | numColumns - 2 |

Formula: `maxOffset = if (numColumns <= 2) numColumns - 1 else numColumns - 2`

## Files to Modify

### 1. `WeatherWidgetProvider.kt`

**`setupNavigationButtons`** - Add numColumns parameter:
```kotlin
private fun setupNavigationButtons(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    stateManager: WidgetStateManager,
    availableDates: Set<String> = emptySet(),
    numColumns: Int = 3  // Add this parameter
)
```

Update navigation check:
```kotlin
// Calculate maxOffset based on widget width
val maxOffset = if (numColumns <= 2) numColumns - 1 else numColumns - 2
val minOffset = if (numColumns <= 2) 0 else -1

// Can go left if there's data for the new leftmost day
val newLeftmost = currentCenterDate.minusDays(1).plusDays(minOffset.toLong())
canLeft = minDate != null && minDate <= newLeftmost

// Can go right if there's data for the new rightmost day
val newRightmost = currentCenterDate.plusDays(1).plusDays(maxOffset.toLong())
canRight = maxDate != null && maxDate >= newRightmost
```

**Call site in `updateWidgetWithData`**:
```kotlin
setupNavigationButtons(context, views, appWidgetId, stateManager, availableDates, numColumns)
```

**`handleDailyNavigationDirect`** - Need to get numColumns or use conservative estimate:
- Option A: Query widget options to get numColumns (more accurate)
- Option B: Use conservative maxOffset = 3 (simpler, works for most widgets)

Going with Option B for simplicity:
```kotlin
// Conservative check: assume widget shows up to center + 3
val maxOffset = 3
val newRightmost = currentCenterDate.plusDays(1 + maxOffset.toLong())
val canNavigate = if (isLeft) {
    minDate != null && minDate <= currentCenterDate.minusDays(2) // center - 1 + minOffset(-1)
} else {
    maxDate != null && maxDate >= newRightmost
}
```

## Verification
1. Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Navigate to show Saturday as rightmost day
3. Verify: Right arrow should be HIDDEN
4. Verify: If arrow somehow appears, clicking it should do nothing
5. Check logs: `adb logcat | grep -E "canRight|canLeft|handleDailyNavigation"`
