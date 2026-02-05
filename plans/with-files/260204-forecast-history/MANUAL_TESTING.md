# Manual Testing Guide: Forecast History Feature

## Prerequisites

✅ App installed on devices (emulators + Pixel 7 Pro)
✅ Database migration v11→v12 completed successfully
✅ Widget added to home screen (various sizes recommended)

---

## Test 1: Database Migration Verification

**Goal**: Confirm migration succeeded and app is stable

1. **Check app launches without crash**
   ```bash
   adb logcat -c
   adb logcat | grep -E "Migration|Room|WeatherDatabase"
   ```
   - Should NOT see "migration required but not found"
   - Should see normal database operation

2. **Verify data fetching**
   - Wait for widget to update (or manually trigger)
   - Check logs for "Saved X forecast snapshots"
   - Verify X > 1 (should save multiple future days)

**Expected**: No crashes, forecasts saved for all future days

---

## Test 2: Forecast Snapshot Collection

**Goal**: Verify all future days are being saved

1. **Clear logs and trigger fetch**
   ```bash
   adb logcat -c
   # Trigger widget update (resize widget or wait for auto-update)
   ```

2. **Check repository logs**
   ```bash
   adb logcat -d | grep "Saved.*forecast snapshots"
   ```

   **Expected output**:
   ```
   Saved 7 forecast snapshots (NWS): 2026-02-04 forecast for dates 2026-02-05 to 2026-02-11
   Saved 14 forecast snapshots (OPEN_METEO): 2026-02-04 forecast for dates 2026-02-05 to 2026-02-18
   ```

3. **Verify database directly** (optional)
   ```bash
   adb shell "run-as com.weatherwidget cat /data/data/com.weatherwidget/databases/weather_database" | strings | grep forecast_snapshots
   ```

**Expected**: Multiple snapshots per day, covering 7+ future days

---

## Test 3: Widget Click Handlers - Text Mode

**Goal**: Verify left/right split in text mode

**Setup**: Widget with 1 row (text mode), 6 columns

1. **Test left-half clicks** (yesterday, today, tomorrow)
   - Tap "Yesterday" day
   - Tap "Today" day
   - Tap "Tomorrow" day

   **Expected**: Opens ForecastHistoryActivity for that date

2. **Test right-half clicks** (future days)
   - Tap day 4, day 5, day 6

   **Expected**: Opens SettingsActivity

3. **Verify correct date passed**
   - When ForecastHistoryActivity opens, check title bar shows correct day
   - Example: "Friday, Feb 5" for tomorrow

**Note**: Midpoint calculation: `visibleDays.size / 2`
- 6 days visible → midpoint = 3
- Days 0-2 (left) → ForecastHistory
- Days 3-5 (right) → Settings

---

## Test 4: Widget Click Handlers - Graph Mode

**Goal**: Verify left/right split in graph mode with zones

**Setup**: Widget with 2+ rows (graph mode), 6 columns

1. **Verify graph_day_zones visible**
   - Graph should render normally
   - Transparent zones overlay graph

2. **Test left-half zone clicks**
   - Tap left third of graph (days 0-2)

   **Expected**: Opens ForecastHistoryActivity

3. **Test right-half zone clicks**
   - Tap right third of graph (days 3-5)

   **Expected**: Opens SettingsActivity

4. **Test various widget sizes**
   - 4 columns (4 days) → midpoint = 2
   - 5 columns (5 days) → midpoint = 2
   - 8 columns (7 days) → midpoint = 3

**Expected**: Touch targets work correctly, zones don't interfere with graph rendering

---

## Test 5: Forecast History Activity - Past Date

**Goal**: Verify activity displays forecast evolution for a past date

**Requirement**: App has been running for 24+ hours to have yesterday's forecasts

1. **Tap yesterday's day in widget**

2. **Verify activity layout**:
   - ✅ Back button (top-left)
   - ✅ Title: "Forecast History"
   - ✅ Subtitle: "Monday, Feb 3" (or correct day)
   - ✅ Summary card:
     - "Actual: 67° / 42°" (or actual temps)
     - "14 forecasts from NWS, 12 from Open-Meteo" (or actual counts)
   - ✅ Legend: Blue (NWS), Green (Open-Meteo), Orange (Actual)
   - ✅ "High Temperature" label
   - ✅ High temp graph with:
     - Blue curve (NWS predictions over time)
     - Green curve (Open-Meteo predictions)
     - Orange dashed horizontal line (actual high)
     - X-axis labels: "7d", "6d"... "1d"
     - Y-axis labels: temperature scale
   - ✅ "Low Temperature" label
   - ✅ Low temp graph (same structure)

3. **Verify graph details**:
   - Curves should be smooth (bezier)
   - Points visible on curves
   - Actual line labeled "Actual: 67°" (right side)
   - Multiple fetches per day show as clustered points

4. **Test back button**:
   - Returns to home screen

**Expected**: Complete forecast evolution displayed with actual values

---

## Test 6: Forecast History Activity - Future Date

**Goal**: Verify activity handles future dates (no actual data)

1. **Tap tomorrow's day in widget** (or any future day)

2. **Verify activity layout**:
   - ✅ Same layout as Test 5
   - ✅ Summary card shows only forecast counts (no "Actual" line)
   - ✅ Graphs show NWS and Open-Meteo curves
   - ⚠️ NO orange dashed line (no actual data yet)

3. **Check logs for errors**:
   ```bash
   adb logcat -d | grep -E "ForecastHistoryActivity|Error"
   ```

   **Expected**: No errors, clean handling of null actual data

---

## Test 7: Hourly Mode Unchanged

**Goal**: Verify hourly mode still works (graph_day_zones hidden)

**Setup**: Toggle widget to hourly mode (tap current temp)

1. **Verify hourly graph displays**
   - 24-hour timeline with NOW indicator
   - Smooth temperature curve

2. **Verify click handlers**:
   - Tap anywhere on graph → Opens Settings
   - graph_day_zones should be GONE (not interfering)

3. **Toggle back to daily mode**:
   - Tap current temp again
   - Daily forecast returns

**Expected**: Hourly mode unaffected by new feature

---

## Test 8: Multiple Fetches Per Day

**Goal**: Verify fetchedAt allows multiple snapshots per day

**Requirement**: Trigger multiple fetches on same day

1. **Force multiple fetches**:
   - Resize widget (triggers fetch)
   - Wait 30 minutes
   - Resize widget again
   - Repeat 2-3 times

2. **Check database growth**:
   ```bash
   adb logcat -d | grep "Saved.*forecast snapshots"
   ```

   Should see multiple "Saved" logs for same forecastDate

3. **Open forecast history for tomorrow**:
   - Graph should show multiple points for "today's" column
   - Points distributed horizontally within the day

**Expected**: Multiple snapshots coexist, no conflicts, graph shows density

---

## Test 9: Accuracy Calculation Still Works

**Goal**: Verify AccuracyCalculator uses latest forecast

**Requirement**: App has run for 2+ days to have actual data

1. **Open StatisticsActivity**:
   ```bash
   adb shell am start -n com.weatherwidget/.ui.StatisticsActivity
   ```

2. **Verify accuracy stats display**:
   - NWS accuracy score
   - Open-Meteo accuracy score
   - Daily breakdown

3. **Check logs**:
   ```bash
   adb logcat -d | grep "AccuracyCalculator"
   ```

   Should use maxByOrNull logic (no errors)

**Expected**: Accuracy calculations work, use latest forecast when multiples exist

---

## Test 10: Widget Sizes & Configurations

**Goal**: Verify feature works across all widget sizes

Test matrix:

| Size | Mode | Left Click | Right Click |
|------|------|------------|-------------|
| 1x1 | Text | ForecastHistory | Settings |
| 1x3 | Text | ForecastHistory (days 0-1) | Settings (day 2) |
| 2x3 | Graph | ForecastHistory (zones 0-1) | Settings (zones 2-5) |
| 2x4 | Graph | ForecastHistory (zones 0-1) | Settings (zones 2-3) |
| 2x6 | Graph | ForecastHistory (zones 0-2) | Settings (zones 3-5) |
| 2x8 | Graph | ForecastHistory (zones 0-3) | Settings (zones 4-6) |

**Expected**: All combinations work correctly

---

## Test 11: Navigation & API Toggle Unaffected

**Goal**: Verify existing functionality still works

1. **Test navigation arrows**:
   - Left arrow: Navigate to past
   - Right arrow: Navigate to future

2. **Test API toggle**:
   - Tap "NWS" (top-right)
   - Should switch to "Meteo"
   - Widget updates with Open-Meteo data

3. **Test current temp toggle**:
   - Tap current temp (top-left)
   - Should toggle hourly ↔ daily mode

**Expected**: All existing touch targets work, no conflicts with new zones

---

## Test 12: Edge Cases

### Empty Data
1. Fresh install, no data yet
2. Tap day in widget
3. **Expected**: ForecastHistoryActivity opens with empty graphs (no crash)

### Single Source Only
1. Device with only NWS data (no Open-Meteo)
2. Tap day
3. **Expected**: Graph shows only blue curve

### Very Old Date
1. Tap navigation to go 30 days back
2. Tap old day
3. **Expected**: Activity opens, may show "No forecasts available" if beyond retention period

---

## Performance Checks

### Database Growth
```bash
adb shell "run-as com.weatherwidget du -sh /data/data/com.weatherwidget/databases"
```

Monitor over 1 week:
- Initial: ~100KB
- After 1 day: ~150KB
- After 7 days: ~500KB
- After 30 days: Should stabilize (cleanup working)

**Expected**: Database size stabilizes due to 30-day cleanup

### Bitmap Memory
Check logcat for memory warnings when opening ForecastHistoryActivity:
```bash
adb logcat | grep -E "OutOfMemory|Bitmap"
```

**Expected**: No memory issues (bitmaps are reasonably sized: ~800x300px)

---

## Regression Tests

Run quick checks on existing features:

- [ ] Widget updates every hour
- [ ] Forecast accuracy dot/bar modes work
- [ ] Climate normals display for far-future dates
- [ ] Settings activity opens and saves preferences
- [ ] GPS location works
- [ ] Zip code location works
- [ ] Yesterday's actual data displays correctly

---

## Success Criteria

✅ Database migration completes without data loss
✅ All future days saved as forecast snapshots
✅ ForecastHistoryActivity opens from widget day taps
✅ Graphs render correctly with NWS (blue), Open-Meteo (green), Actual (orange)
✅ Left/right split works in both text and graph modes
✅ Multiple fetches per day work (no conflicts)
✅ Accuracy calculations use latest forecast
✅ Existing features unaffected (navigation, API toggle, hourly mode)
✅ No crashes, no memory issues
✅ Database cleanup keeps size manageable

---

## Troubleshooting

**Problem**: Activity doesn't open on day tap
- Check logcat for PendingIntent errors
- Verify manifest registration
- Check request code uniqueness

**Problem**: Graphs show empty/blank
- Verify snapshots exist in database
- Check logs for EvolutionRenderer errors
- Confirm actual data exists for past dates

**Problem**: Wrong date displayed
- Check date calculation in setupGraphDayClickHandlers
- Verify EXTRA_TARGET_DATE passed correctly

**Problem**: Duplicate snapshots with same fetchedAt
- Should not happen (single fetch uses same timestamp)
- If seen, check saveForecastSnapshot logic

---

## Log Commands Cheat Sheet

```bash
# Clear logs
adb logcat -c

# Watch forecast saves
adb logcat | grep "Saved.*forecast"

# Watch activity launches
adb logcat | grep "ForecastHistory"

# Watch for errors
adb logcat | grep -E "Error|Exception|FATAL"

# Watch database operations
adb logcat | grep -E "Room|Migration|SQLite"

# Full widget provider logs
adb logcat | grep "WeatherWidgetProvider"
```
