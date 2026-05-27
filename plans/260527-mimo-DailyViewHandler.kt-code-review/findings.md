# Findings: DailyViewHandler.kt Code Review

## Finding 1: Duplicate DailyRenderContext Construction
- **Lines:** 318-343 vs 359-384
- **Impact:** DRY violation, maintenance burden
- **Fix:** Extract before `if (useGraph)` branch

## Finding 2: Unused Imports
- **Lines:** 25 (`ForecastHistoryActivity`), 26 (`SettingsActivity`), 50 (`Job`)
- **Impact:** Clutter
- **Fix:** Remove

## Finding 3: headerDateFormatter Locale Capture
- **Line:** 76
- **Impact:** Locale won't update if user changes locale at runtime
- **Fix:** Per-call formatter or document as intentional

## Finding 4: Database Instantiation Too Early
- **Lines:** 175-176
- **Impact:** Unnecessary allocation when `useGraph == false`
- **Fix:** Move into graph branch or DailyRenderContext

## Finding 5: updateTextMode Has 18 Parameters
- **Lines:** 623-636
- **Impact:** Readability, maintenance
- **Fix:** Accept DailyRenderContext

## Finding 6: Fire-and-Forget Coroutine
- **Lines:** 977-1000
- **Impact:** Not tied to lifecycle; low risk since it's diagnostic logging
- **Fix:** Use `withContext(Dispatchers.IO)` (function is already suspend)

## Finding 7: Unused todayStr Variable
- **Line:** 174
- **Impact:** Dead code
- **Fix:** Remove

## Finding 8: Redundant hideUnusedDailyViews Call
- **Line:** 308
- **Impact:** Called unconditionally; redundant for graph mode
- **Fix:** Skip call in graph mode path

## Finding 9: Dual-Path Icon Handling
- **Impact:** Maintenance concern, not a bug
- **Fix:** Document or defer

## Finding 10: Magic Numbers
- **Lines:** 63, 67
- **Impact:** Readability
- **Fix:** Add explanatory comments

## Finding 11: Duplicate Header Bind Calls in bindHeaderState
- **Lines:** 1397-1412 (first pass) vs 1449-1469 (second pass with scale)
- **Impact:** First pass is immediately overwritten; wasted work
- **Fix:** Remove first-pass calls (lines 1397-1412)

## Finding 12: Thin Wrapper Methods
- **Lines:** 802-846
- **Impact:** Indirection with no added logic
- **Fix:** Call DailyClickHandlerFactory directly or keep wrappers for readability
