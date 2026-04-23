# Factor Out Duplicate Code Across Widget View Handlers

## Problem
20 duplicated code patterns exist across DailyViewHandler, CloudCoverViewHandler,
PrecipViewHandler, TemperatureViewBinder, TemperatureViewHandler,
TemperatureTouchTargets, HeaderWidthChecker, and TemperatureStateResolver.
Some patterns are copied 4 times with slight variations. Future bugs will
diverge across copies if not consolidated.

## Strategy
1. Expand existing shared functions in TemperatureTouchTargets.kt (already has
   canonical versions of many setup helpers)
2. Make HeaderWidthChecker measurement functions internal (delete DailyViewHandler copies)
3. Create HeaderRemoteViewsBinder.kt for header RemoteViews binding patterns
4. Add computeBitmapDimensions() to WidgetSizeCalculator
5. Move getCurrentHourForecast() to shared utility
6. Extract shared hourly data loop into HourlyDataBuilder.kt
7. Add checkAndRenderBlockingWarning() to ApiSourceWarningHelper

---

## Batch 1 — Expand TemperatureTouchTargets.kt (high impact, low risk)

These functions already exist as top-level `internal` functions in
TemperatureTouchTargets.kt. Private copies in other handlers should be deleted
and replaced with calls to the canonical versions.

### 1.1 setupApiToggle (4 copies)
- TemperatureTouchTargets.kt:140 (canonical)
- CloudCoverViewHandler.kt:514
- PrecipViewHandler.kt:478
- DailyViewHandler.kt:839 (also binds `text_mode_*` views)

**Change:** Add `includeTextMode: Boolean = false` parameter. When true, also
bind `text_mode_api_source_container`, `text_mode_api_touch_zone`, and set
`text_mode_api_source` text size. Delete 3 private copies.

### 1.2 setupSettingsShortcut (4 copies)
- TemperatureTouchTargets.kt:242 (canonical)
- CloudCoverViewHandler.kt:591
- PrecipViewHandler.kt:583
- DailyViewHandler.kt:827 (also binds `text_mode_*`)

**Change:** Add `includeTextMode: Boolean = false` parameter. Delete 3 private copies.

### 1.3 setupNavigationButtons / hourly (3 copies)
- TemperatureTouchTargets.kt:74 (canonical)
- CloudCoverViewHandler.kt:448
- PrecipViewHandler.kt:410

**Change:** Delete 2 private copies, call the canonical function directly.

### 1.4 setupZoomTapZones + HOUR_ZONE_IDS (3 copies)
- TemperatureTouchTargets.kt:30-72 (canonical)
- CloudCoverViewHandler.kt:402-439
- PrecipViewHandler.kt:374-408

**Change:** Make `HOUR_ZONE_IDS` internal in TemperatureTouchTargets. Delete
2 private copies of both the list and the function.

### 1.5 setupHistoryShortcut (3 copies)
- TemperatureTouchTargets.kt:166 (canonical, binds `history_touch_zone_inline`)
- CloudCoverViewHandler.kt:536 (also sets icon/touch_zone VISIBLE)
- PrecipViewHandler.kt:504 (also sets icon/touch_zone VISIBLE)

**Change:** Add `setVisibility: Boolean = false` parameter. When true, set
`history_icon` and `history_touch_zone` to VISIBLE. Delete 2 private copies.

### 1.6 setupHomeShortcut (3 copies)
- TemperatureTouchTargets.kt:200 (canonical, binds `home_touch_zone_inline`)
- CloudCoverViewHandler.kt:569 (also sets icon/touch_zone VISIBLE)
- PrecipViewHandler.kt:539 (also sets icon/touch_zone VISIBLE)

**Change:** Same as 1.5 — add `setVisibility: Boolean = false`. Delete 2 private copies.

### 1.7 setupWeatherStationsShortcut (2 copies)
- TemperatureTouchTargets.kt:221 (canonical, binds `weather_stations_touch_zone_inline`)
- PrecipViewHandler.kt:561 (also sets icon/touch_zone VISIBLE)

**Change:** Add `setVisibility: Boolean = false`. Delete 1 private copy.

**Estimated savings:** ~350 lines deleted across 3 files.

---

## Batch 2 — Make HeaderWidthChecker measurement functions internal

### 2.1 6 private functions duplicated between HeaderWidthChecker and DailyViewHandler

| Function | HeaderWidthChecker | DailyViewHandler |
|----------|-------------------|-----------------|
| `measurePaint` | line 22 | line 1063 |
| `resolveLeftClusterRightPx` | lines 90-114 | lines 1032-1051 |
| `resolveApiLeftPx` | lines 116-125 | lines 1053-1061 |
| `dpToPx` | lines 127-133 | lines 1088-1089 |
| `textWidthPx` | lines 135-142 | lines 1065-1073 |
| `currentTempTextWidthPx` | lines 144-151 | lines 1075-1087 |

**Change:** Make all 6 functions `internal` in HeaderWidthChecker. Delete all
6 copies from DailyViewHandler. Update DailyViewHandler's calls to reference
HeaderWidthChecker directly.

**Estimated savings:** ~50 lines.

---

## Batch 3 — New HeaderRemoteViewsBinder.kt

### 3.1 bindCurrentTemp (3 copies)
- DailyViewHandler.kt:353-361
- CloudCoverViewHandler.kt:237-249
- PrecipViewHandler.kt:190-203

### 3.2 bindPrecipProbability (3 copies)
- DailyViewHandler.kt:375-385
- CloudCoverViewHandler.kt:258-267
- PrecipViewHandler.kt:216-225

### 3.3 bindDelta (2 copies)
- DailyViewHandler.kt:387-402
- TemperatureViewBinder.kt:56-64

### 3.4 applyDisclosureToRemoteViews (4 copies)
- DailyViewHandler.kt:416-423
- CloudCoverViewHandler.kt:281-292
- PrecipViewHandler.kt:239-250
- TemperatureViewBinder.kt:87-93

**New file:** `HeaderRemoteViewsBinder.kt` in `handlers/` package with:
```kotlin
object HeaderRemoteViewsBinder {
    fun bindCurrentTemp(context, views, currentTemp, numColumns, isStaleEstimate)
    fun bindPrecipProbability(context, views, precipProbability)
    fun bindDelta(context, views, deltaText, deltaVisible)
    fun applyDisclosure(views, disclosure, isDeltaVisible, isPrecipVisible)
}
```

**Estimated savings:** ~80 lines.

---

## Batch 4 — WidgetSizeCalculator.computeBitmapDimensions (4 copies)

- DailyViewHandler.kt:531,588-592
- CloudCoverViewHandler.kt:314-322
- PrecipViewHandler.kt:277-286
- TemperatureStateResolver.kt:225-230

All 4 compute the same thing with magic numbers `24` and `16`:
```kotlin
val widthDp = dimensions.widthDp - 24
val heightDp = dimensions.heightDp - 16
val (widthPx, heightPx) = WidgetSizeCalculator.getOptimalBitmapSize(context, widthDp, heightDp)
val rawWidthPx = WidgetSizeCalculator.dpToPx(context, widthDp).coerceAtLeast(1)
val rawHeightPx = WidgetSizeCalculator.dpToPx(context, heightDp).coerceAtLeast(1)
val bitmapScale = min(widthPx.toFloat() / rawWidthPx.toFloat(), heightPx.toFloat() / rawHeightPx.toFloat())
```

**Change:** Add to WidgetSizeCalculator:
```kotlin
data class BitmapDimensions(val widthPx: Int, val heightPx: Int, val bitmapScale: Float)
fun computeBitmapDimensions(context: Context, widgetWidthDp: Int, widgetHeightDp: Int,
    widthPaddingDp: Int = 24, heightPaddingDp: Int = 16): BitmapDimensions
```
Replace 4 copies with single call. Centralizes the magic numbers.

**Estimated savings:** ~18 lines + eliminates magic number duplication.

---

## Batch 5 — Move getCurrentHourForecast to shared utility (3 exact copies)

- CloudCoverViewHandler.kt:388-400
- PrecipViewHandler.kt:359-372
- TemperatureStateResolver.kt:484-497

**Change:** Move to `WeatherTimeUtils.kt` (already handles hourly key computation)
as `getCurrentHourForecast()`. Make it `internal`. Delete 3 private copies.

**Estimated savings:** ~24 lines.

---

## Batch 6 — ApiSourceWarningHelper.checkAndRenderBlockingWarning (3 copies)

- DailyViewHandler.kt:245-273
- CloudCoverViewHandler.kt:152-169
- PrecipViewHandler.kt:106-123

**Change:** Add to ApiSourceWarningHelper:
```kotlin
fun checkAndRenderBlockingWarning(
    context, views, appWidgetId, numRows, appLogDao, displaySource,
    hasSelectedSourceData, callerTag: String
): Boolean  // true = warning rendered, caller should return
```
Also calls `setupApiToggle` and `hideSourceWarning` internally.
Replace 3 blocks with a single call + early return.

**Estimated savings:** ~30 lines.

---

## Batch 7 — Current temp resolution + delta state persist (3 copies)

- DailyViewHandler.kt:323-341
- CloudCoverViewHandler.kt:220-235
- PrecipViewHandler.kt:172-188

**Change:** Create `CurrentTempResolutionHelper.kt`:
```kotlin
fun resolveAndPersistDelta(
    now, displaySource, hourlyForecasts, lastObservedTemp, observedAt,
    stateManager, appWidgetId, lat, lon, smoothedForecasts?
): Pair<CurrentTemperatureResolution, Long>  // resolution + resolveMs
```
Handles the resolve + persist delta state + timing pattern.

**Estimated savings:** ~30 lines.

---

## Batch 8 — HourlyDataBuilder (Cloud vs Precip) (2 copies, ~80 lines each)

- CloudCoverViewHandler.kt:606-683 (buildCloudHourDataList)
- PrecipViewHandler.kt:601-685 (buildPrecipHourDataList)

The time window, forecast grouping, loop, sun/icon resolution, and label logic
are identical. Only the output data class and a few fields differ.

**Change:** Extract shared intermediate data class and builder loop into
`HourlyDataBuilder.kt`. Each handler maps from intermediate to its specific type.

**Estimated savings:** ~80 lines.

---

## Batch 9 — Widget init boilerplate (4 copies)

- DailyViewHandler.kt:188-197
- CloudCoverViewHandler.kt:118-123
- PrecipViewHandler.kt:74-79
- TemperatureViewHandler.kt:56-58

**Change:** Create `WidgetUpdateContext` data class with a `create()` factory.
Pass it into handler methods instead of reconstructing each variable.

**Estimated savings:** ~18 lines + cleaner method signatures.

---

## Priority Order

| Batch | Impact | Risk | Savings |
|-------|--------|------|---------|
| 1 | Highest (7 functions, 4 handlers) | Low | ~350 lines |
| 2 | High (6 functions, 2 files) | Low | ~50 lines |
| 3 | High (4 patterns, 4 handlers) | Medium | ~80 lines |
| 4 | Medium (1 pattern, 4 files) | Very Low | ~18 lines |
| 5 | Medium (1 function, 3 files) | Very Low | ~24 lines |
| 6 | Medium (1 pattern, 3 files) | Low | ~30 lines |
| 7 | Low-Medium (1 pattern, 3 files) | Medium | ~30 lines |
| 8 | Low (2 files only) | Medium | ~80 lines |
| 9 | Low (4 lines each) | Very Low | ~18 lines |

**Total estimated savings: ~680 lines**

## Implementation Notes

- Each batch should be a separate commit for easy rollback
- Build after each batch to verify no regressions
- Batch 1 is the most impactful and should be done first
- Batches 4 and 5 are trivial and can be done together
- Batch 8 has the highest risk since the hourly data logic is complex
