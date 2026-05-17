# Fix: Daily View 73.5° vs Hourly Graph 73.1° — stop merging persisted into live blended

## Context

The Daily View shows `73.5°` for today's high while the Hourly Graph shows `73.1°` for the same day on the emulator. A prior fix (commits `910dfba`, `9a6a895`) was supposed to unify them by making `getDailyActualsWithLiveToday` use `ObservationBlender.blendObservationSeries` — the same blender the Hourly Graph uses. The blender call is in fact producing the correct value, but a downstream merge step replaces it with the legacy persisted value.

Proof from live emulator logs (`adb -s emulator-5556 logcat`):

```
ObservationRepository: getDailyActualsWithLiveToday: ...
  live=[NWS[blendedHigh=73.12499, blendedLow=58.067535, rows=291]]
  persistedToday=[NWS[high=73.48656, low=58.686295, ...]]
ObservationRepository: getDailyActualsWithLiveToday:
  mergedToday=[NWS[mergedHigh=73.48656, mergedLow=58.067535]]
```

And what reaches the Daily View:
```
DailyViewHandler: dailyTodayInputs: ... dailyActual.high=73.48656 ...
TODAY_BAR_DEBUG: ... trueHigh=73.48656 ...
```

The blender produced `73.12499°` (matches Hourly Graph). The merge step `mergeDailyActualsBySource(primary=blended, secondary=persisted)` returned `73.48656°` — the persisted value — because `ObservationResolver.mergeDailyActual` (`app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt:318-332`) uses `maxOf(primary.highTemp, secondary.highTemp)` / `minOf(lows)` "preserving the widest known bounds." That is the *pre-fix* semantic; it does not match the inline comment `// Prefer live blended results`.

The two values disagree because the persisted row is computed by a different algorithm:
- **Live blended** (`ObservationBlender.blendObservationSeries`): IDW-blend the 5 stations *at every observation timestamp*, then take `maxOf{ blended hourly temps }` → `73.12499°`. Same call the Hourly Graph makes.
- **Persisted** (`ObservationResolver.computeDailyExtremes` → `blendExtremes`): For each station compute its own daily max, then IDW-blend those 5 daily-max numbers → `73.48656°`. Written to `daily_extremes` table by `recomputeDailyExtremesForDay` every time a new observation arrives.

`maxOf(73.12499, 73.48656) = 73.48656` ⇒ persisted wins ⇒ Daily View shows `73.5°`.

The existing regression test `TemperatureUnificationRegressionTest.kt:36-109` passes because it constructs `dailyActuals` directly from the blender output (line 72-74) and never exercises the merge path that contains the bug.

User direction: **always blended, never merge, no fallback to persisted**. If the blender returns empty for a source, show nothing for that source's today actual — do not consult `daily_extremes`.

## Change

**File:** `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt` — `getDailyActualsWithLiveToday` (lines 325-426)

1. Delete the `persistedTodayExtremes` query (line 367-372) and the `persistedTodayActuals` derivation (line 406).
2. Delete the `mergedTodayActuals = ObservationResolver.mergeDailyActualsBySource(primary=todayBlendedActuals, secondary=persistedTodayActuals)` call (lines 407-410) and the related `mergedTodaySummary` logging (lines 411-420).
3. Replace the final return (lines 422-425) so today comes directly from `todayBlendedActuals`:
   ```kotlin
   return ObservationResolver.mergeDailyActualsBySource(
       primary = pastActuals,
       secondary = todayBlendedActuals,
   )
   ```
   (This merge is over disjoint date sets — past 30 days vs today — so `maxOf` is harmless. No semantic change to past-day behavior.)
4. Update the existing live/persisted log to drop the `persistedToday=` clause, or keep it as `persistedToday=ignored` only if useful for forensic comparison. Recommend dropping to reduce noise.

**File:** `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureUnificationRegressionTest.kt`

Leave as is — it still validates the blender→DailyActualsEstimator path. The new Robolectric test below covers the merge-path regression.

**New file:** `app/src/test/java/com/weatherwidget/data/repository/ObservationRepositoryDailyMergeTest.kt` (Robolectric + in-memory Room)

This test must fail on `main` today and pass after the fix. It reproduces the exact log scenario observed on `emulator-5556`.

Convention reference (already in the project):
- `app/src/test/java/com/weatherwidget/data/local/HourlyForecastDaoTest.kt` — Room in-memory pattern
- `app/src/test/java/com/weatherwidget/data/repository/WeatherGapIntegrationTest.kt` — repository-layer Robolectric test
- `app/src/test/java/com/weatherwidget/data/repository/WeatherRepositoryRateLimitIntegrationTest.kt` — repository red-then-green pattern

Test skeleton:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)  // or whichever the project uses; see neighbors
class ObservationRepositoryDailyMergeTest {

    private lateinit var db: AppDatabase
    private lateinit var observationDao: ObservationDao
    private lateinit var dailyExtremeDao: DailyExtremeDao
    private lateinit var appLogDao: AppLogDao
    private lateinit var repo: ObservationRepository
    private val lat = 37.422
    private val lon = -122.0841

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        observationDao = db.observationDao()
        dailyExtremeDao = db.dailyExtremeDao()
        appLogDao = db.appLogDao()
        repo = ObservationRepository(context, observationDao, dailyExtremeDao, appLogDao, mockk(relaxed = true))
    }
    @After fun tearDown() { db.close() }

    @Test
    fun `today high uses live blended value, ignoring stale persisted daily_extreme`() = runBlocking {
        val today = LocalDate.now()
        val todayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // Seed 2 NWS station observations: near (1km) at 73.1, far (10km) at 73.5.
        // Live IDW-blended series will produce ~73.1 (near station dominates).
        observationDao.insertAll(listOf(
            ObservationEntity(
                stationId = "KNEAR", stationName = "Near", timestamp = todayStart + 15.hoursToMs,
                temperature = 73.1f, condition = "Clear", locationLat = lat, locationLon = lon,
                distanceKm = 1f, stationType = "ASOS", api = WeatherSource.NWS.id,
            ),
            ObservationEntity(
                stationId = "KFAR", stationName = "Far", timestamp = todayStart + 15.hoursToMs,
                temperature = 73.5f, condition = "Clear", locationLat = lat, locationLon = lon,
                distanceKm = 10f, stationType = "ASOS", api = WeatherSource.NWS.id,
            ),
        ))

        // Seed a stale daily_extremes row at 73.5 (simulates persisted IDW-of-per-station-max).
        dailyExtremeDao.insertAll(listOf(
            DailyExtremeEntity(
                date = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                source = WeatherSource.NWS.id,
                locationLat = lat, locationLon = lon,
                highTemp = 73.5f, lowTemp = 58.7f, condition = "Clear",
                updatedAt = System.currentTimeMillis(),
            ),
        ))

        val hourlyForecasts = listOf(
            HourlyForecastEntity(dateTime = todayStart + 14.hoursToMs, temperature = 72f, source = WeatherSource.NWS.id, /* ... */),
            HourlyForecastEntity(dateTime = todayStart + 15.hoursToMs, temperature = 73f, source = WeatherSource.NWS.id, /* ... */),
            HourlyForecastEntity(dateTime = todayStart + 16.hoursToMs, temperature = 72f, source = WeatherSource.NWS.id, /* ... */),
        )

        val result = repo.getDailyActualsWithLiveToday(
            latitude = lat, longitude = lon,
            hourlyForecasts = hourlyForecasts,
            activeSourceList = listOf(WeatherSource.NWS.id),
        )

        val todayHigh = result[WeatherSource.NWS.id]?.get(today)?.highTemp
        // BEFORE fix: this is 73.5 (mergeDailyActual maxOf picks the persisted value).
        // AFTER fix: this is ~73.1 (live blended wins, persisted is not consulted).
        assertEquals("Today's high must come from live blender, not stale daily_extremes",
            73.1f, todayHigh!!, 0.1f)
    }
}
```

Notes on the skeleton:
- Field names and constructor parameters should match what's actually in the project — adjust by reading the production classes before writing. The shape above is illustrative.
- `WeatherSource.NWS.id` and `WidgetConstants.MS_IN_A_DAY` are already used in production code.
- Validate the failing assertion: run the test once on current `main` and confirm it reports `expected 73.1 but was 73.486...`. Then make the production change and confirm it goes green.

## Why this is safe

- The persisted `daily_extremes` table is still maintained for past days (called by `recomputeDailyExtremesForDay` on every backfill). Past-day reads through `pastActuals` are unchanged.
- The `mergeDailyActualsBySource` function itself is unchanged and continues to work for past-day callers (`extremesToDailyActualsBySource` consumers elsewhere).
- If `todayBlendedActuals` is empty for a source (e.g., zero observations today), that source simply has no today entry — `DailyActualsEstimator` already handles a missing `dailyActuals[today]` by falling back to `dashedLineHigh` (the forecast). User has confirmed this is desired.

## Critical files

- `app/src/main/java/com/weatherwidget/data/repository/ObservationRepository.kt:325-426` — the only production file to edit for the fix
- `app/src/main/java/com/weatherwidget/widget/ObservationResolver.kt:298-332` — `mergeDailyActualsBySource` / `mergeDailyActual` (read-only; semantics preserved)
- `app/src/main/java/com/weatherwidget/util/ObservationBlender.kt:86-231` — `blendObservationSeries` (read-only; produces the correct value already)
- `app/src/test/java/com/weatherwidget/widget/handlers/TemperatureUnificationRegressionTest.kt` — leave as is
- `app/src/test/java/com/weatherwidget/data/repository/ObservationRepositoryDailyMergeTest.kt` — **new file** (red on `main`, green after fix)

## Verification

1. **Prove the bug is real:** write the new Robolectric test first, run it on current `main`, confirm it fails with `expected 73.1 but was 73.486...`:
   ```bash
   ./gradlew testDebugUnitTest --tests "com.weatherwidget.data.repository.ObservationRepositoryDailyMergeTest"
   ```

2. Apply the production change to `ObservationRepository.kt`, rerun the new test, confirm it passes. Run the full suite:
   ```bash
   ./gradlew testDebugUnitTest
   ```

2. Install on the emulator that exhibits the bug (`emulator-5556`):
   ```bash
   ./gradlew installDebug
   adb -s emulator-5556 shell am broadcast -a com.weatherwidget.REFRESH_WIDGET
   ```

3. Pull logs and confirm the live blended value reaches the Daily View unchanged:
   ```bash
   adb -s emulator-5556 logcat -d | grep -E "TODAY_BAR_DEBUG|getDailyActualsWithLiveToday|TempExtrema.*ACTUAL_EXTREMA"
   ```
   Expected: `TODAY_BAR_DEBUG ... trueHigh=73.12...` matches `TempExtrema ACTUAL_EXTREMA highTemp=73.12...` (both should round to `73.1°`).

4. Visual check: take a screenshot and confirm the Daily View today-bar label matches the Hourly Graph actual-peak label.
   ```bash
   adb -s emulator-5556 exec-out screencap -p > /tmp/widget.png && convert /tmp/widget.png /tmp/widget.jpg
   ```
