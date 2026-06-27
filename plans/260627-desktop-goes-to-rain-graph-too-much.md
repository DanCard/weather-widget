# Desktop daily-column tap: match Android routing (share the decision logic)

## Context

On the desktop app, clicking **today** (or any day column) in the daily view opens the
**rain chance / precipitation** graph even when there's little or no rain. Android behaves
differently: a daily-column tap opens the **precipitation** graph **only** when the day reads as
rain *and* the daily precip probability is **≥ 16%**; otherwise it opens the **hourly temperature**
graph. Android also never opens the **cloud-cover** graph from a daily tap.

The root cause is that desktop's daily-tap handler reuses the wrong routing function. There are two
separate routing paths in the codebase:

- **Daily-column tap** — Android `DayClickHelper.resolveDailyTargetViewMode(iconRes, precipProbability)`
  (`app/.../widget/handlers/DayClickHelper.kt:46`). Returns only `PRECIPITATION` (rain icon **and**
  prob ≥ 16) or `TEMPERATURE`. Never `CLOUD_COVER`.
- **Bottom-row icon tap on the hourly graph** — `WeatherConditionResolver.resolveIconHome(iconName)`
  (`shared/.../util/WeatherConditionResolver.kt:155`). Returns `PRECIPITATION` / `CLOUD_COVER` /
  `HOURLY` purely from the icon name, with no probability gate.

Desktop's `dayClickConfig()` (`desktop/.../Main.kt:96`) wires the **daily** tap into
`WeatherIcon.resolveIconHome()` — the **bottom-row** logic — so it (a) ignores the 16% gate and
(b) routes cloudy days to cloud-cover. The user confirmed desktop should **match Android exactly**:
cloudy-day daily taps should open the temperature graph, and precip only for rainy + ≥16%.

The platforms can't share a return type (`ViewMode.TEMPERATURE` on Android vs `ViewMode.HOURLY` on
desktop) or icon type (`Int` drawable res on Android vs `String` icon name on desktop), so the
shared seam is the **decision predicate + threshold constant**; each platform maps the result to its
own enum. This follows the repo convention of extracting the decision into `:shared` and having both
platforms delegate (see memory: "Share Android/desktop logic").

## Changes

### 1. Shared: add the daily-click routing decision (`shared/.../util/WeatherConditionResolver.kt`)

Add the threshold constant and decision functions near the existing `IconHome` /
`resolveIconHome` (lines 153–159):

```kotlin
/** Minimum daily precip probability (%) for a daily-column tap to open the precipitation graph. */
const val DAILY_CLICK_PRECIP_THRESHOLD = 16

/**
 * Daily-column tap gate: open the precipitation graph only when the day reads as rain AND its daily
 * precip probability clears [DAILY_CLICK_PRECIP_THRESHOLD]. Platform-neutral so Android (Int res) and
 * desktop (icon name) feed it their own already-computed `isRainIndicator`.
 */
fun shouldDailyClickShowPrecip(isRainIndicator: Boolean, precipProbability: Int?): Boolean =
    isRainIndicator && (precipProbability ?: 0) >= DAILY_CLICK_PRECIP_THRESHOLD

/**
 * Name-based convenience for daily-column taps (desktop). Returns only [IconHome.PRECIPITATION] or
 * [IconHome.HOURLY] — unlike [resolveIconHome] (bottom-row taps), a daily tap never routes to
 * cloud cover, matching Android's [resolveDailyTargetViewMode].
 */
fun resolveDailyClickHome(iconName: String?, precipProbability: Int?): IconHome =
    if (iconName != null && shouldDailyClickShowPrecip(isRainIndicator(iconName), precipProbability))
        IconHome.PRECIPITATION
    else
        IconHome.HOURLY
```

### 2. Android: delegate to the shared gate (`app/.../widget/handlers/DayClickHelper.kt:46`)

Keep the Android `ViewMode` signature; route the decision through the shared predicate so the
threshold lives in one place:

```kotlin
fun resolveDailyTargetViewMode(iconRes: Int?, precipProbability: Int?): ViewMode {
    if (iconRes == null) return ViewMode.TEMPERATURE
    return if (WeatherConditionResolver.shouldDailyClickShowPrecip(
            WeatherIconMapper.isRainIndicator(iconRes), precipProbability)) {
        ViewMode.PRECIPITATION
    } else {
        ViewMode.TEMPERATURE
    }
}
```

Add the `import com.weatherwidget.shared.util.WeatherConditionResolver`. Behavior is identical to
today (still 16%); this is a refactor to share the constant/predicate. Existing
`DayClickHelperTest.kt` cases (e.g. rain + 15% → TEMPERATURE) must still pass unchanged.

### 3. Desktop: route daily taps through the shared daily-click logic (`desktop/.../Main.kt:96`)

Replace the `WeatherIcon.resolveIconHome(...)` call in `dayClickConfig()` with the daily-click
resolver, gating on the same precip the displayed icon used
(`forecast?.precipProbability`, with `snapshot?.precipProbability` as fallback — mirrors the model at
`DesktopDailyForecastModel.kt:250`/`:288`). `DesktopDailyDay` already carries `forecast` and
`snapshot`, so no new field is needed:

```kotlin
val clickedDay = days.find { it.date == clickedDate }
val precipProb = clickedDay?.forecast?.precipProbability ?: clickedDay?.snapshot?.precipProbability
val targetView = when (WeatherConditionResolver.resolveDailyClickHome(clickedDay?.iconName, precipProb)) {
    WeatherConditionResolver.IconHome.PRECIPITATION -> ViewMode.PRECIPITATION
    else -> ViewMode.HOURLY
}
```

Add `import com.weatherwidget.shared.util.WeatherConditionResolver`. This drops the cloud-cover
daily-tap path (cloudy days now open the hourly temperature graph) and adds the 16% gate — matching
Android. `WeatherIcon.resolveIconHome()` stays in place; it is still the correct routing for
bottom-row icon taps elsewhere.

## Tests

- **Shared** (`shared/src/test/.../WeatherConditionResolverTest.kt`, or a new small test): cover
  `shouldDailyClickShowPrecip` / `resolveDailyClickHome` — rain icon + 16 → PRECIPITATION;
  rain icon + 15 → HOURLY; rain icon + null → HOURLY; cloudy icon (e.g. `ic_weather_cloudy`) at any
  prob → HOURLY (never CLOUD_COVER); null icon → HOURLY.
- **Android**: existing `DayClickHelperTest.kt` should pass unchanged (confirms the refactor is
  behavior-preserving).
- **Desktop** (`desktop/src/test/.../DesktopUiTest.kt`, which already references `dayClickConfig`):
  add cases asserting `dayClickConfig(...)` returns `viewMode = HOURLY` for a cloudy today and for a
  low-prob rainy day, and `PRECIPITATION` for a rainy day with prob ≥ 16.

## Verification

1. `./gradlew :shared:test :app:testDebugUnitTest :desktop:test` — all green.
2. Rebuild + restart the desktop app per the repo workflow (`scripts/buildStart.sh`).
3. In the desktop daily view, click **today**:
   - Dry/cloudy day → opens the **hourly temperature** graph (framed midnight→midnight).
   - Rainy day with ≥16% chance → opens the **rain chance** graph.
4. Spot-check a future cloudy day column → temperature graph (no cloud-cover graph).
