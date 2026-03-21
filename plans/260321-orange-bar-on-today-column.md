# Plan: Relax Orange Snapshot Bar Selection for Today

## Context

The orange "snapshot" bar on today's column shows what was predicted 24+ hours ago. Currently `DailyViewLogic.kt:274-278` **strictly requires** a snapshot older than 24 hours — if none exists, no bar is shown. This means newly installed apps or emulators that haven't been running long enough never see the bar.

**Fix:** Prefer a 24h+ old snapshot, but fall back to the **oldest available** snapshot if nothing qualifies. This ensures the bar appears as soon as any historical snapshot exists, using the most temporally distant prediction available.

## Change

**File:** `app/src/main/java/com/weatherwidget/widget/handlers/DailyViewLogic.kt` (lines 274-278)

### Current code:
```kotlin
val snapshot = forecasts
    .filter { it.source == displaySource.id }
    .filter { it.highTemp != null && it.lowTemp != null }
    .filter { LocalDateTime.ofInstant(...it.fetchedAt...).isBefore(yesterdaySameTime) }
    .maxByOrNull { it.fetchedAt }
```

### New code:
```kotlin
val candidates = forecasts
    .filter { it.source == displaySource.id }
    .filter { it.highTemp != null && it.lowTemp != null }
val snapshot = candidates
    .filter { LocalDateTime.ofInstant(...it.fetchedAt...).isBefore(yesterdaySameTime) }
    .maxByOrNull { it.fetchedAt }
    ?: candidates.minByOrNull { it.fetchedAt }  // fallback: oldest available
```

Logic:
1. First try: most recent snapshot that is 24h+ old (existing behavior)
2. Fallback: oldest available snapshot of any age (new)

Using `minByOrNull` for the fallback ensures we pick the prediction most distant from now — maximizing the forecast-vs-actual comparison value.

## Verification
1. Build and install on emulator: `./gradlew installDebug`
2. Screenshot widget — orange bar should now appear on today's column
3. Check logcat for `prepareGraphDays: today snapshot hit`
