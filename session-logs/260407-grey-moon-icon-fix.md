# Fix: Moon Icon Tint (Gold → Grey)

**Date:** 2026-04-07
**Branch:** main
**Status:** Complete, deployed to emulator

## Summary

The crescent moon icon shown for "Today" when it's nighttime was being tinted gold (`#FFD60A`), the same color used for sunny/clear daytime icons. A gold moon is visually incorrect — moons should be grey/silver. Fixed by removing `ic_weather_night` from `WeatherIconMapper.isSunny()`.

## Conversation Prompts

1. **User:** "emulator: icon for today is gold moon. I would have expected a grey color moon."

2. **Claude:** Captured a screenshot, confirmed the gold moon. Launched an Explore agent to trace the icon tinting pipeline.

3. **Explore agent findings:**
   - `WeatherIconMapper.isSunny()` explicitly included `ic_weather_night` alongside `ic_weather_clear` and `ic_weather_mostly_clear`
   - Icon tinting in both `DailyForecastGraphRenderer.drawWeatherIcon()` (line 326) and `DailyViewHandler` (line 845) applies gold tint when `isSunny` is true
   - The moon had `isSunny=true` → gold tint path taken
   - `ic_weather_partly_cloudy_night` was already in `isMixed()` so that icon was unaffected (no tint applied, draws with native colors)

4. **Claude:** Wrote plan to plan file, exited plan mode for approval.

5. **User:** *(rejected ExitPlanMode — switched model to Sonnet 4.6)*

6. **User:** "Add a test for this and implement"

## Root Cause

**`WeatherIconMapper.kt:98-102`** — before fix:
```kotlin
fun isSunny(iconRes: Int): Boolean {
    return iconRes == R.drawable.ic_weather_clear ||
           iconRes == R.drawable.ic_weather_mostly_clear ||
           iconRes == R.drawable.ic_weather_night  // <-- caused gold moon
}
```

`ic_weather_night` was presumably included so clear nights would get a "warm" tint rather than grey. But a gold moon is visually wrong — moons are silver/grey.

## Tinting Pipeline (Two Code Paths)

Both code paths check `isSunny()` to decide between gold and grey:

**1. `DailyForecastGraphRenderer.kt:326`** (Canvas-rendered widget, 2x3+ sizes):
```kotlin
if (!day.isRainy && !day.isMixed) {
    val tint = if (day.isSunny) Color.parseColor(COLOR_SUNNY) else Color.parseColor(COLOR_LABEL_GRAY)
    drawable.setTint(tint)
}
```

**2. `DailyViewHandler.kt:845`** (RemoteViews widget, smaller sizes):
```kotlin
val tintColor = if (WeatherIconMapper.isSunny(iconRes)) {
    context.getColor(R.color.sunny_yellow)   // #FFD60A
} else {
    context.getColor(R.color.weather_icon_tint_default)  // #AAAAAA
}
views.setInt(ids.icon, "setColorFilter", tintColor)
```

## Impact Analysis

After removing `ic_weather_night` from `isSunny()`, for a clear night: `isSunny=false`, `isMixed=false`, `isRainy=false`.

| Code path | Before | After | Correct? |
|-----------|--------|-------|----------|
| Icon tint | Gold `#FFD60A` | Grey `#AAAAAA` | Yes |
| `forecastColor(false,false,false,false)` | Gold (isSunny=true) | Gold (else branch default) | Yes — daily bar stays gold |
| Hourly line | Night hours use `isNight=true` → silver | Unchanged | Yes |

No negative side effects on bar colors or hourly line colors.

## Files Modified

### `app/src/main/java/com/weatherwidget/util/WeatherIconMapper.kt`

Removed `ic_weather_night` from `isSunny()`:
```kotlin
// Before
fun isSunny(iconRes: Int): Boolean {
    return iconRes == R.drawable.ic_weather_clear ||
           iconRes == R.drawable.ic_weather_mostly_clear ||
           iconRes == R.drawable.ic_weather_night
}

// After
fun isSunny(iconRes: Int): Boolean {
    return iconRes == R.drawable.ic_weather_clear ||
           iconRes == R.drawable.ic_weather_mostly_clear
}
```

### `app/src/test/java/com/weatherwidget/util/WeatherIconMapperTest.kt`

Added two new tests:
```kotlin
@Test
fun testMoonIsNotSunny() {
    // Clear night uses the moon icon — should NOT be classified as sunny (would tint it gold)
    assertFalse(WeatherIconMapper.isSunny(R.drawable.ic_weather_night))
}

@Test
fun testMoonIsNotMixedOrRainy() {
    // Moon should fall through to grey tinting, not skip tinting entirely
    assertFalse(WeatherIconMapper.isMixed(R.drawable.ic_weather_night))
    assertFalse(WeatherIconMapper.isRainy(R.drawable.ic_weather_night))
}
```

The second test (`testMoonIsNotMixedOrRainy`) documents the intended grey tinting behavior: for tinting to apply at all, the icon must NOT be mixed or rainy. If the moon were accidentally added to `isMixed()`, it would skip tinting entirely and render with its native drawable color instead of the intended grey.

## Testing

- **Unit tests:** All pass (`./gradlew testDebugUnitTest`)
- **Visual verification:** Screenshot confirmed moon is now grey/silver for "Today" at nighttime

## Plan File

Saved to `plans/260407-gradient-forecast-bars.md` (plan file was reused from prior session and updated for this fix).
