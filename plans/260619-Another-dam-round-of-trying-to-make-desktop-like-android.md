# Desktop daily-view cloud/rain bar parity — via shared split logic

## Context

In the **daily view forecast**, Android's forecast bars encode weather visually:
- **Bar split height** → cloud cover (a gold/clear top fading to an opaque bottom; more
  opaque = more clouds).
- **Bottom segment color** → rain: **blue** (`FORECAST_RAINY`) for chance-of-rain
  conditions, **grey** (`FORECAST_CLOUDY`) for merely-cloudy ones.

Desktop diverges in three ways, which the user has had to point out repeatedly:
1. **Today** column bars (snapshot + live-forecast) show no cloud/rain shading at all.
2. **History** days show no cloud/rain shading on the forecast overlay bar.
3. Even where desktop *does* shade (future days), it uses a **smooth grey-only gradient**
   that never turns blue for rain, and a smooth fade instead of Android's **two hard
   segments**.

The user's overriding directive: **Android and desktop should share the decision logic,
not reimplement it.** So the fix is not "make desktop look similar" — it's "extract the
split decision into `:shared` and have both platforms consume it," eliminating the source
of drift.

The cloud-cover data is already computed for every day (incl. today) on desktop:
`DesktopDailyForecastModel.resolveNoonCloudCoverRatio()` → `DesktopDailyDay.cloudCoverRatio`.

## What's already shared (reuse, don't duplicate)

- `shared/.../util/WeatherColors.kt` — `FORECAST_SUNNY`, `FORECAST_CLOUDY`,
  `FORECAST_RAINY` ARGB ints (Android's `WeatherConditionColors` colors are the same values).
- `shared/.../util/WeatherConditionResolver.kt` — `cloudRatioFromIcon(iconName)` (values
  **identical** to Android's `WeatherConditionColors.cloudRatio(iconRes)`), plus the
  `IC_*` icon-name constants and `resolveIconName(condition)`.

## What's NOT shared yet (the actual divergence)

- Android `app/.../util/WeatherConditionColors.kt`:
  - `resolveMixedBarSplit(iconRes, override)` — assembles `ratio / topColor / bottomColor /
    topFraction`.
  - `CHANCE_RAIN_ICONS` (resource-ID set) — decides blue vs grey bottom.
- Desktop `DailyForecastGraph.kt` `drawAdaptiveBar` — its own smooth, grey-only gradient.

## Approach — extract the split into `:shared`, both platforms delegate

### 1. Add the shared split primitive — `WeatherColors.kt`

The decision is pure math over a ratio + a rain flag; keep the per-platform "is this a
chance-rain icon" predicate (Android has resource IDs, desktop has names) but share the
assembly and the color choice so they can never drift:

```kotlin
data class MixedBarSplit(
    val ratio: Float,
    val topColorArgb: Int,     // FORECAST_SUNNY
    val bottomColorArgb: Int,  // FORECAST_RAINY (chance of rain) or FORECAST_CLOUDY
    val topFraction: Float,    // 1 - ratio (height of the clear/top segment)
)

/** Null when there's no cloud ratio (solid-color bar). */
fun mixedBarSplit(cloudRatio: Float?, isChanceOfRain: Boolean): MixedBarSplit? {
    val r = (cloudRatio ?: return null).coerceIn(0f, 1f)
    return MixedBarSplit(
        ratio = r,
        topColorArgb = FORECAST_SUNNY,
        bottomColorArgb = if (isChanceOfRain) FORECAST_RAINY else FORECAST_CLOUDY,
        topFraction = (1f - r).coerceIn(0f, 1f),
    )
}
```

### 2. Add a shared chance-of-rain icon-name set — `WeatherConditionResolver.kt`

Mirrors Android's `CHANCE_RAIN_ICONS` (note: only "chance rain", **not** "slight chance"):

```kotlin
val CHANCE_RAIN_ICONS = setOf(
    IC_PARTLY_CLOUDY_CHANCE_RAIN, IC_PARTLY_CLOUDY_CHANCE_RAIN_NIGHT, IC_CLOUDY_CHANCE_RAIN,
)
fun isChanceOfRainIcon(iconName: String): Boolean = iconName in CHANCE_RAIN_ICONS
```

### 3. Android delegates — `WeatherConditionColors.kt`

Keep Android's resource-ID `CHANCE_RAIN_ICONS` and `cloudRatio(iconRes)` (its inputs),
but route the assembly through the shared helper so the geometry/colors are single-sourced:

```kotlin
internal fun resolveMixedBarSplit(iconRes: Int, cloudRatioOverride: Float? = null): MixedBarSplit? {
    val ratio = cloudRatioOverride ?: cloudRatio(iconRes)
    val s = WeatherColors.mixedBarSplit(ratio, iconRes in CHANCE_RAIN_ICONS) ?: return null
    return MixedBarSplit(s.ratio, s.topColorArgb, s.bottomColorArgb, s.topFraction)
}
```

This is behavior-preserving for Android (`WeatherConditionColorsTest.kt` should stay green).
`drawWeatherAdaptiveBar` and `forecastBarGradient` are unchanged.

### 4. Desktop renders Android's hard two-segment bar — `DailyForecastGraph.kt`

Replace the smooth grey-only `drawAdaptiveBar` with a faithful port of Android's
`drawWeatherAdaptiveBar` geometry (bottom segment full-height in the split color, top
segment painted over it in the base color), consuming the shared split:

```kotlin
private fun DrawScope.drawAdaptiveBar(
    centerX: Float, highY: Float, lowY: Float, width: Float,
    baseColor: Color, cloudCoverRatio: Float?, iconCondition: String?,
) {
    val iconName = iconCondition?.let { WeatherConditionResolver.resolveIconName(it) }
    val ratio = cloudCoverRatio ?: iconName?.let { WeatherConditionResolver.cloudRatioFromIcon(it) }
    val isRain = iconName?.let { WeatherConditionResolver.isChanceOfRainIcon(it) } ?: false
    val split = WeatherColors.mixedBarSplit(ratio, isRain)
    if (split == null) {                       // solid bar (no cloud info) — e.g. rainy/clear
        drawRangeLineSolid(centerX, highY, lowY, baseColor, width); return
    }
    val barHeight = lowY - highY
    val topEndY = (highY + barHeight * split.topFraction).coerceIn(highY, lowY)
    // bottom (grey/blue) full bar, then top (baseColor) over the clear fraction — Android order
    drawSeg(centerX, highY, lowY, Color(split.bottomColorArgb), width)
    if (topEndY - highY > 0.5f) drawSeg(centerX, highY, topEndY, baseColor, width)
}
```

Then in the `displayDays.forEachIndexed` loop:
- **Today** branch: change the snapshot bar (`centerX - tripleOffset`) and the live-forecast
  bar (`centerX + tripleOffset`) from `drawRangeLine` → `drawAdaptiveBar(...,
  day.cloudCoverRatio, day.iconCondition)`. Pass `snapshotColor`/`baseColor` as the top
  color respectively. The thermostat/ghost/bulb (observed red) stay solid `drawRangeLine`.
- **Past** branch: change the forecast overlay bar (`centerX + tripleOffset`) → `drawAdaptiveBar`
  (top color `forecastColor(day)`). The observed actual bar stays solid `drawRangeLine`.
- **Future** branch: already calls `drawAdaptiveBar`; just add the `iconCondition` arg.

Preserve the existing null-guard behavior (skip a bar when its high/low is null, as
`drawRangeLine` does today). `forecastColor(day)` is unchanged — it still returns the solid
blue/grey for fully-rainy/cloudy days (where `mixedBarSplit` returns null and the bar stays
solid), matching Android.

### 5. Drift guard — shared contract test

Add a small contract test (in the `:shared` test source, in the spirit of the existing
`LocationMatchContract`/`ConditionFlags` tests) pinning `mixedBarSplit` outputs for a few
ratios and both rain flags, so Android and desktop can't silently diverge again.

## Files

- `shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherColors.kt` — add
  `MixedBarSplit` + `mixedBarSplit()`.
- `shared/src/main/kotlin/com/weatherwidget/shared/util/WeatherConditionResolver.kt` — add
  `CHANCE_RAIN_ICONS` + `isChanceOfRainIcon()`.
- `app/src/main/java/com/weatherwidget/util/WeatherConditionColors.kt` — delegate
  `resolveMixedBarSplit` to the shared helper (behavior-preserving).
- `desktop/src/main/kotlin/com/weatherwidget/desktop/DailyForecastGraph.kt` — hard
  two-segment `drawAdaptiveBar`; route today snapshot+forecast and past overlay through it.
- `shared/src/test/...` — new contract test for `mixedBarSplit`.
- No data/model/DAO change (`cloudCoverRatio` already populated for every day).

## Verification

1. `./gradlew testDebugUnitTest --tests "*WeatherConditionColors*"` and the shared module
   tests — confirm Android split unchanged + new shared contract green.
2. Build/restart desktop: `scripts/buildStart.sh`.
3. In the daily view confirm, matching the Android widget side-by-side:
   - **Today**: snapshot (left) + live-forecast (right) bars show the gold-top → grey/blue
     bottom split sized by cloud cover; thermostat bar stays solid red.
   - **History**: the forecast overlay bar shows the split; observed actual bar stays solid.
   - **Future**: unchanged shape but now **hard-segmented** and turns **blue** on
     chance-of-rain days (regression + new-behavior check).
   - Fully-cloudy day → solid grey bar; fully-rainy day → solid blue bar (no spurious split).
4. Pick a clear day, a partly-cloudy day, and a chance-of-rain day and confirm the three
   render distinctly (grey proportion + blue) identically on both platforms.
