# Plan: Rain Chance Font Size — New Scale Table + Header Night Reduction

## Context

Two changes to how rain-chance text is sized across the widget:

1. **New probability scale table** — the current thresholds (15%, 25%) are not powers-of-two and produce scale values that are too large for low-probability rain. Replace with a powers-of-two table (0.3×–1.0×) so tiny-chance labels shrink more aggressively.

2. **Header night reduction** — the header shows the *next 8 hours* of max precip probability. If that rain is predominantly nighttime (more probability-weighted minutes fall after sunset / before sunrise), the label should be 0.72× smaller, matching the visual treatment already used on daily-bar night labels.

---

## File Changes

### 1. `HeaderPrecipCalculator.kt`
`app/src/main/java/com/weatherwidget/util/HeaderPrecipCalculator.kt`

**a) Update `getPrecipScaleFactor`** (lines 64–72):

```kotlin
fun getPrecipScaleFactor(precipProb: Int): Float = when {
    precipProb <= 1  -> 0.3f
    precipProb <= 2  -> 0.4f
    precipProb <= 4  -> 0.5f
    precipProb <= 8  -> 0.6f
    precipProb <= 16 -> 0.7f
    precipProb <= 32 -> 0.8f
    precipProb <= 64 -> 0.9f
    else             -> 1.0f
}
```

**b) Add `NIGHT_SCALE` constant and `isNext8HourPrecipPredominantlyNight` function:**

```kotlin
const val NIGHT_SCALE = 0.72f

fun isNext8HourPrecipPredominantlyNight(
    hourlyForecasts: List<HourlyForecastEntity>,
    displaySource: WeatherSource,
    referenceTime: LocalDateTime,
    sunriseHour: Double,   // from SunPositionUtils.SunTimes
    sunsetHour: Double,
): Boolean {
    if (sunsetHour >= 24.0) return false   // midnight sun — no nighttime
    if (sunriseHour <= 0.0) return true    // polar night — all night

    // Same candidate-resolution logic as getNext8HourPrecipProbability
    val sourceForecasts = hourlyForecasts.filter { it.source == displaySource.id && it.precipProbability != null }
    val candidateForecasts = if (sourceForecasts.isNotEmpty()) sourceForecasts
                             else hourlyForecasts.filter { it.source == WeatherSource.GENERIC_GAP.id && it.precipProbability != null }
    if (candidateForecasts.isEmpty()) return false

    val selectedForecasts = candidateForecasts
        .groupBy { it.dateTime }
        .mapValues { (_, items) -> items.maxOf { checkNotNull(it.precipProbability) } }

    var nightSum = 0f
    var daySum   = 0f
    for (minuteOffset in 0 until LOOKAHEAD_HOURS * MINUTES_PER_HOUR) {
        val sampleTime = referenceTime.plusMinutes(minuteOffset)
        val prob = interpolatePrecipProbabilityAt(selectedForecasts, sampleTime) ?: continue
        val hourOfDay = sampleTime.hour + sampleTime.minute / 60.0
        if (hourOfDay < sunriseHour || hourOfDay >= sunsetHour) nightSum += prob
        else                                                      daySum  += prob
    }
    return nightSum > daySum
}
```

The private `interpolatePrecipProbabilityAt` helper is already there and is reused directly.

---

### 2. `DailyViewHandler.kt`
`app/src/main/java/com/weatherwidget/widget/handlers/DailyViewHandler.kt`

After computing `precipProb` (around line 1258–1264), add a nighttime check using the already-computed `sunInfo`:

```kotlin
val isNightPrecip = precipProb != null && HeaderPrecipCalculator.isNext8HourPrecipPredominantlyNight(
    hourlyForecasts = hourlyForecasts,
    displaySource   = displaySource,
    referenceTime   = now,
    sunriseHour     = sunInfo.sunTimes.sunriseHour,
    sunsetHour      = sunInfo.sunTimes.sunsetHour,
)
```

Then apply the night factor when computing `precipTextSizeDp` (line 1054 area):

```kotlin
precipTextSizeDp = if (isPrecipVisible) {
    HeaderPrecipCalculator.getPrecipTextSize(ctx.precipProb ?: 0) *
        if (isNightPrecip) HeaderPrecipCalculator.NIGHT_SCALE else 1f
} else HeaderConstants.PRECIP_TEXT_BASE_SIZE_DP,
```

`sunInfo` is already in scope (used a few lines above for `isNight` on the weather icon).

---

### 3. `HeaderPrecipCalculatorTest.kt`
`app/src/test/java/com/weatherwidget/util/HeaderPrecipCalculatorTest.kt`

Update both `getPrecipScaleFactor` and `getPrecipTextSize` test cases to match the new table. Key boundary values to assert:

| prob | new scale | new text size (base 18) |
|------|-----------|------------------------|
| 0    | 0.3       | 5.4                    |
| 1    | 0.3       | 5.4                    |
| 2    | 0.4       | 7.2                    |
| 4    | 0.5       | 9.0                    |
| 8    | 0.6       | 10.8                   |
| 16   | 0.7       | 12.6                   |
| 32   | 0.8       | 14.4                   |
| 64   | 0.9       | 16.2                   |
| 65   | 1.0       | 18.0                   |
| 100  | 1.0       | 18.0                   |

Add tests for `isNext8HourPrecipPredominantlyNight`:
- Peak rain in daytime hours → false
- Peak rain in nighttime hours → true
- Spread evenly across day/night with slightly higher night weight → true
- Empty forecasts → false
- Midnight sun (`sunsetHour = 24.0`) → false
- Polar night (`sunriseHour = 0.0`) → true

---

## What Does NOT Change

- `DailyForecastRainLabelRenderer.kt` — its own `NIGHT_SCALE = 0.72f` for daily bar night labels is unchanged and unrelated
- `HeaderRenderData` — no new fields needed; the night scaling is baked into `precipTextSizeDp` before the data class is constructed
- `DailyForecastHeaderRenderer.kt` — no changes; it reads `precipTextSizeDp` as-is
- `PrecipViewHandler.kt` / `CloudCoverViewHandler.kt` — they call `getPrecipScaleFactor`/`getPrecipTextSize` and will automatically pick up the new table with no code changes

---

## Verification

```bash
./gradlew testDebugUnitTest --tests "com.weatherwidget.util.HeaderPrecipCalculatorTest"
```

Then visually: run the widget at ~8pm with a forecast showing 5–15% chance of rain in the next 8 hours — the header label should be noticeably smaller than before, and further shrunk if the rain peaks after sunset.
