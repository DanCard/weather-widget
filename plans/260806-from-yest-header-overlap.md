# Fix "from yest" header overlap on narrow widgets (Pixel 7 Pro)

Date: 2026-08-06

## Evidence (collected before fixing)

1. Pixel 7 Pro (`2A191FDH300PPW`) screenshot: hourly-view header shows the inline
   forecast-history icon (chart line) overlapping the "NWS" API label on the right.
   User confirmed the strikethrough-looking glyph over "NWS" is the forecast-history
   button — that is the overlap.
2. Runtime logcat on the Pixel:
   `positionCenterIcons: widthDp=373 isPrecipVisible=true useInline=true isToday=true`,
   `inline touch zones resized to 40dp for widthDp=373`.
3. Root cause: on narrow widgets (`widthDp < 420`) `positionCenterIcons`
   (TemperatureTouchTargets.kt) appends 4 inline nav touch zones (graph selector |
   stations | home | history) to the header's left LinearLayout — 4 x 40dp + 1dp
   margin = 161dp at widthDp=373. `HeaderWidthChecker.deltaLabelFitsInHeader` only
   measures icon + temp + delta + "from yest" + precip, ignoring the inline nav row,
   so "from yest" passes the fit check while the real row overflows into the API label.
4. Samsung (SM-F936U1) daily-view header: no such overlap (bitmap header, no inline
   icons; user retracted the initial Samsung report).

## Changes

1. `HeaderConstants.kt`: `DELTA_LABEL_TEXT_SIZE_DP` 10f -> 8f (~20% smaller, per user
   request). Drives both the bitmap header paint and `bindDeltaLabel` (sets px size).
2. `res/layout/widget_weather.xml`: `current_temp_delta_label` default `textSize`
   10sp -> 8sp (runtime sets px anyway; keeps preview consistent).
3. `HeaderWidthChecker.kt`:
   - Add `inlineNavRowWidthDp(widthDp, showStations)` mirroring `positionCenterIcons`
     rules (0 when widthDp >= 420; zone width 36dp pre-API31, else 32/40/48 by
     widthDp < 350 / < 400 / else; 4 zones, 3 when stations hidden; +1dp marginStart).
   - `deltaLabelFitsInHeader` gains `inlineNavWidthDp: Float = 0f`, added to the left
     cluster before comparing against the API label's left edge.
4. `TemperatureViewBinder.kt`: hoist the existing `isToday` computation above the
   delta-label fit check and pass
   `inlineNavWidthDp = HeaderWidthChecker.inlineNavRowWidthDp(state.widthDp, showStations = isToday)`.
   DailyHeaderResolver (daily view) needs no change: DailyVisibilityManager keeps the
   inline zones GONE, so the default 0 applies.
5. Tests (`HeaderWidthCheckerTest`, Robolectric/LongDuration):
   - inline nav width is 0 at >= 420dp and positive below, 3 zones when stations hidden.
   - label fits at a Pixel-like width without inline width but is dropped with it.

## Verification

- `./gradlew :app:testLongDebugUnitTest --tests '*HeaderWidthCheckerTest*'`
- Rebuild + install on Pixel 7 Pro, force widget re-render, screenshot: "from yest"
  gone from the narrow hourly header, history icon no longer overlaps "NWS".
  Confirmed by user on device.

## Addendum: rain chance suppresses "from yest" (same day)

New requirement: when a rain chance (precip %) shows in the header, skip "from yest"
entirely — precip % has display priority over the caption.

1. `HeaderWidthChecker.deltaLabelFitsInHeader`: returns false on any non-blank
   `precipText`. Both RemoteViews-header callers (TemperatureViewBinder hourly,
   DailyHeaderResolver daily) already pass precipText only when the % would actually
   render, so this one guard covers both.
2. `DailyGraphRenderer`: the bitmap-header caption candidate is additionally gated on
   `!(isPrecipVisible && headerPrecipPlacement.showHeaderPrecip)`, matching the guard.
3. Tests: new precip-priority case (suppressed at wide width with "1%", fits without);
   the inline-nav crowding case now uses precipText=null so the two rules don't mask
   each other. 17/17 pass.
4. Runtime evidence (Samsung SM-F936U1 daily header, previously showed "+0.5 from yest"
   next to "1%"): after install + refresh the header shows `69.4° +0.5   1%` — caption
   gone, rain chance kept.
