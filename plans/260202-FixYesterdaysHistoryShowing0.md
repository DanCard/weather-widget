# Fix Yesterday's History Showing 0

## Problem

The widget displays `H=0` for yesterday (2026-02-01) because NWS observation fetch returns no data for recent dates.

**Root Cause**: The observation query uses UTC dates instead of local timezone dates.

### Evidence from Logs

```
No observations found for AW020 on 2026-02-02  (today)
No observations found for AW020 on 2026-02-01  (yesterday)
Got 122 observations for 2026-01-31            (2 days ago - works!)
Got 142 observations for 2026-01-30            (3 days ago - works!)
```

### Why It Fails

In `WeatherRepository.kt` lines 396-399:

```kotlin
val startTime = date.atStartOfDay(java.time.ZoneId.of("UTC"))
val endTime = date.plusDays(1).atStartOfDay(java.time.ZoneId.of("UTC"))
```

For Feb 1 local date (PST = UTC-8):
- Current query: `2026-02-01T00:00:00Z` to `2026-02-02T00:00:00Z`
- In PST: **Jan 31 4pm to Feb 1 4pm** (wrong window!)

For Jan 31 local date:
- Current query: `2026-01-31T00:00:00Z` to `2026-02-01T00:00:00Z`
- In PST: **Jan 30 4pm to Jan 31 4pm** (also offset, but still catches Jan 31 morning observations)

The older dates "work" because they're querying a window that still overlaps with the local day's observations (catching the morning high). But for recent dates, the offset window might not have observation data yet.

## Solution

Use local timezone when constructing the observation query time range.

### File to Modify

`app/src/main/java/com/weatherwidget/data/repository/WeatherRepository.kt`

### Change (lines 396-399)

**From:**
```kotlin
val startTime = date.atStartOfDay(java.time.ZoneId.of("UTC"))
    .format(java.time.format.DateTimeFormatter.ISO_INSTANT)
val endTime = date.plusDays(1).atStartOfDay(java.time.ZoneId.of("UTC"))
    .format(java.time.format.DateTimeFormatter.ISO_INSTANT)
```

**To:**
```kotlin
val localZone = java.time.ZoneId.systemDefault()
val startTime = date.atStartOfDay(localZone)
    .format(java.time.format.DateTimeFormatter.ISO_INSTANT)
val endTime = date.plusDays(1).atStartOfDay(localZone)
    .format(java.time.format.DateTimeFormatter.ISO_INSTANT)
```

This will query:
- For Feb 1 in PST: `2026-02-01T08:00:00Z` to `2026-02-02T08:00:00Z`
- Which covers Feb 1 midnight to Feb 2 midnight local time

## Verification

1. Build and install: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew installDebug`
2. Check logcat for observation fetch:
   ```bash
   adb logcat -c && adb logcat | grep -i "fetchDayObservations"
   ```
3. Force widget update by tapping on it
4. Verify logs show observations found for yesterday (Feb 1)
5. Verify widget displays actual high temp instead of 0
