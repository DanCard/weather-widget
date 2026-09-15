# Fix Test Timezone Invariance (22 Short Test Failures)

**Date**: 2026-09-15  
**Goal**: Resolve the 22 failing short tests in `:app` and 1 in `:shared` caused by host timezone drift when running tests outside US Pacific time (`America/Los_Angeles`).

---

## 1. Root Cause Analysis

### 1.1 The Specific Assertion Failure
```
✗ DailyForecastIconResolverTest > nws chance light rain token stays slight chance at 49 percent daily pop
    java.lang.AssertionError: expected:<2131165333> but was:<2131165334>
```
- Resource ID `2131165333`: `R.drawable.ic_weather_partly_cloudy_slight_chance_rain` (day icon)
- Resource ID `2131165334`: `R.drawable.ic_weather_partly_cloudy_slight_chance_rain_night` (night icon)

### 1.2 Mechanism
1. `DailyForecastIconResolverTest` passes `now = LocalDateTime.of(2030, 6, 15, 12, 0)` (12:00 noon) at `latitude = 37.42, longitude = -122.08` (Mountain View, California).
2. `DailyForecastIconResolver.resolveIcon()` checks `SunPositionUtils.isNight(now, latitude, longitude)`.
3. In `SunPositionUtils.kt` (lines 245–251):
   ```kotlin
   // 11. Convert UTC to local time using the system default timezone offset.
   // Since the app only shows weather for the device's location, this is correct.
   val zoneOffset = ZoneId.systemDefault().rules.getOffset(dateTime).totalSeconds / 3600.0
   var localTime = utcT + zoneOffset
   ```
4. On a device, the system timezone naturally matches the device location. However, in unit tests, test fixtures hardcode California coordinates (SF: 37.7749, -122.4194; Mountain View: 37.42, -122.08) and assume the JVM timezone is `America/Los_Angeles` (UTC-7/UTC-8).
5. On this host system, local time is `EEST` (`UTC+03:00`). When Gradle forks test worker JVMs, they inherit `user.timezone = Europe/Kiev` (UTC+3, a 10-hour shift from California).
6. With `zoneOffset = +3.0` instead of `-7.0`, solar noon in California (~20:00 UTC) converts to ~23:00 (11:00 PM local). Consequently, `SunPositionUtils.isNight()` evaluates to `true` at 12:00 noon!
7. `DailyForecastIconResolver` therefore selects the night icon variant instead of the day icon variant, causing 12 assertion failures.
8. The exact same root cause accounts for:
   - 8 failures in `SunPositionUtilsTest` (`testIsNight_SanFrancisco_Noon`, `testIsNight_SanFrancisco_Sunset`, etc.)
   - 2 failures in `DayClickHelperTest` (`calculateNightCenterOffset SF tonight from afternoon...`, etc.)
   - 1 failure in `:shared` (`ObservationPoolDiagnosticsTest`), where `clock()` formats timestamps using `ZoneId.systemDefault()`.
   - **Total: 22 failures in `:app`, 1 in `:shared`.**

---

## 2. Proposed Fix Plan

Configure Gradle test tasks to pin `user.timezone` to `America/Los_Angeles` on all forked test worker JVMs so unit tests run deterministically and immune to host/CI timezone variations.

1. **`:app` (`app/build.gradle.kts`)**:
   Under `tasks.withType<Test>`:
   ```kotlin
   systemProperty("user.timezone", "America/Los_Angeles")
   ```

2. **`:shared` (`shared/build.gradle.kts`)**:
   Under `tasks.withType<Test>`:
   ```kotlin
   systemProperty("user.timezone", "America/Los_Angeles")
   ```

3. **`:desktop` (`desktop/build.gradle.kts`)**:
   Under `tasks.withType<Test>`:
   ```kotlin
   systemProperty("user.timezone", "America/Los_Angeles")
   ```

---

## 3. Verification Plan

1. Run `./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.util.DailyForecastIconResolverTest"` (verify all 52 tests pass, 0 failed).
2. Run `./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.util.SunPositionUtilsTest"` (verify all 20 tests pass, 0 failed).
3. Run `./gradlew :app:testDebugUnitTest --tests "com.weatherwidget.widget.handlers.DayClickHelperTest"` (verify all 48 tests pass, 0 failed).
4. Run `./gradlew :shared:testShortShared` (verify all 1,581 tests pass, 0 failed).
5. Run `./scripts/unit-tests.sh Short` (verify all 1,048 short tests pass with 0 failures).
