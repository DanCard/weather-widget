# Session Log: Investigating and Fixing the Daily Today Bar Glitch (2026-05-12)

## Overview
The user reported that the "today" bar in the daily forecast view on Samsung devices sometimes "glitches" and displays at a lower temperature than it should. This behavior was intermittent but consistently resulted in a visually "shrunk" or lowered bar for the current day.

## 1. Empirical Investigation (Samsung Device)
I pulled a fresh database backup from the connected Samsung device (`RFCT71FR9NT`) and performed a targeted analysis of the logs and data.

### Findings from `forecasts` Table:
- **Query**: `SELECT datetime(fetchedAt/1000, 'unixepoch', 'localtime'), targetDate, highTemp, lowTemp FROM forecasts WHERE source='NWS' AND date(targetDate/1000, 'unixepoch') = '2026-05-12' ORDER BY fetchedAt DESC;`
- **Result**: 
    - `2026-05-12 21:33:31 | 76.0 | null`
    - `2026-05-12 18:33:18 | 76.0 | 53.0`
- **Conclusion**: The NWS gridpoints API often drops the `lowTemp` for the current day in evening updates (once the low has likely passed or the period has ended). This results in a valid high (76°F) but a null low.

### Findings from `app_logs` Table (`TODAY_BAR_DEBUG`):
- **Log Entry**: `widget=346 mode=GRAPH obsHigh=58.7 obsLow=55.8 fHigh=61.0 fLow=47.0 ... fallback=false`
- **Observation**: While the NWS forecast was 76°F, the widget was using `fHigh=61.0` and `fLow=47.0`. 
- **Correlation**: `61/47` matched exactly with the `05-12` entry in the `climate_normals` table.

## 2. Root Cause: Aggressive Climate Fallback
The investigation revealed a logic error in `DailyViewHandler.kt`:

1. **Mapping Logic**: When building the `weatherByDate` map, the handler checked if the preferred source (e.g., NWS) had both `highTemp` and `lowTemp`.
2. **The Bug**: If *either* value was null, it would immediately fall back to the `GENERIC_GAP` source (climate normals) for that date.
3. **Shadowing**: This fallback happened *before* the data reached `DailyViewLogic`.
4. **Suppression of Recovery**: `DailyViewLogic` and `DailyActualsEstimator` have specialized logic to recover missing "Today" values using historical snapshots or hourly data. However, because `DailyViewHandler` had already replaced the "NWS" entry with a "Generic" (climate normal) entry, the recovery logic saw a "complete" (but incorrect) climate forecast and never attempted to restore the true NWS values.

## 3. Implementation of Fix
I modified the weather map building logic in `DailyViewHandler.kt` to specifically exempt "Today" from the aggressive climate normal fallback.

```kotlin
// DailyViewHandler.kt

// For Today, we MUST preserve the preferred source even if incomplete (e.g. NWS evening drop),
// because DailyViewLogic/DailyActualsEstimator have specialized recovery for Today.
if (preferred != null && !isToday && (preferred.highTemp == null || preferred.lowTemp == null)) {
    items.find { it.source == WeatherSource.GENERIC_GAP.id && it.highTemp != null && it.lowTemp != null } ?: preferred
} else {
    preferred ?: items.first()
}
```

This change ensures that for the current day, the partially complete preferred source is passed through to the downstream logic, which then successfully uses historical snapshots (e.g., the 6:33 PM update that still had the 53°F low) or hourly trends to fill the gaps.

## 4. Verification & Testing

### Automated Tests
- **New Test Case**: `DailyViewHandlerFallbackTest.kt`
    - Simulates an evening scenario with an incomplete NWS forecast (high=76, low=null) and a complete climate normal (61/47).
    - Verifies that the widget preserves the NWS values (`fHigh=76.0`) for Today instead of falling back to climate normals.
- **Result**: **PASSED**

### Existing Tests
- Ran `DailyViewLogicTest` and `DailyViewHandlerTest`.
- **Result**: **PASSED** (38 tests)

## 5. Metadata
- **Date**: 2026-05-12
- **Device**: Samsung SM-F936U1 (Samsung Galaxy Z Fold 4)
- **Status**: Fixed and Verified.
