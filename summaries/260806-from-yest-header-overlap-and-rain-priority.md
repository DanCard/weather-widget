# "from yest" header overlap fix + rain-chance priority

*2026-08-06 · plan: `plans/260806-from-yest-header-overlap.md`*

Two related changes to the opportunistic "from yest" caption that trails the header's
yesterday delta:

1. **Overlap fix (Pixel 7 Pro hourly header).** The forecast-history icon was overlapping
   the "NWS" API label on narrow widgets. Runtime evidence (`positionCenterIcons` logcat:
   `widthDp=373`, `useInline=true`, zones resized to 40dp) showed the cause: on widgets
   narrower than 420dp the header's left LinearLayout also carries the inline nav icon row
   (graph selector | stations | home | history ≈ 161dp at that width), but
   `HeaderWidthChecker.deltaLabelFitsInHeader` only measured icon + temp + delta + caption
   + precip. The caption passed the fit check while the real row overflowed into the API
   label. (The first report named the Samsung; that was a mistake — the Samsung daily
   bitmap header has no inline icons and no overlap.)
2. **Rain-chance priority (new product rule).** When a rain chance (precip %) shows in the
   header, "from yest" is skipped entirely — the precip % has display priority.
3. **Font −20%.** The caption was shrunk anyway: `DELTA_LABEL_TEXT_SIZE_DP` 10f → 8f
   (drives both the bitmap-header paint and the RemoteViews binder, which sets px); the
   `widget_weather.xml` default went 10sp → 8sp for preview consistency.

## What changed

1. **`HeaderWidthChecker.kt`**
   - New `inlineNavRowWidthDp(widthDp, showStations)` mirroring `positionCenterIcons`
     exactly: 0 at ≥420dp; per-zone 36dp pre-API 31, else 32/40/48dp for
     widthDp <350 / <400 / otherwise; 4 zones (3 when the stations icon is hidden on
     non-today graphs); +1dp marginStart on the selector zone.
   - `deltaLabelFitsInHeader` gains `inlineNavWidthDp: Float = 0f`, added to the left
     cluster before comparing with the API label's left edge, and now returns `false` for
     any non-blank `precipText` (rain-priority rule — both RemoteViews callers already
     pass precipText only when the % would actually render).
2. **`TemperatureViewBinder.kt`** — hoisted the existing `isToday` computation above the
   header bind and passes the inline nav row width into the fit check, so the caption is
   dropped instead of overlapping. Daily view unaffected (its inline zones are always
   GONE → default 0).
3. **`DailyGraphRenderer.kt`** — the bitmap-header caption candidate is additionally gated
   on `!(isPrecipVisible && headerPrecipPlacement.showHeaderPrecip)`, matching the guard.
4. **`HeaderConstants.kt`** — `DELTA_LABEL_TEXT_SIZE_DP` 10f → 8f.

## Tests

- New: inline-nav threshold/zone-width cases, caption-dropped-when-inline-row-counted,
  and rain-priority (suppressed at wide width with "1%", fits without).
- The inline-crowding case uses `precipText = null` so the two suppression rules don't
  mask each other; widthDp=200 keeps it meaningful under Robolectric's coarse text
  metrics (~60px measured cluster vs ~real device widths).
- 17/17 `HeaderWidthCheckerTest` pass (`:app:testLongDebugUnitTest`).

## Runtime verification

- Pixel 7 Pro (hourly, widthDp=373): "from yest" gone from the header, history icon no
  longer overlaps "NWS" — confirmed by the user on device.
- Samsung SM-F936U1 (daily bitmap header, previously `+0.5 from yest` beside `1%`): after
  install + refresh it shows `69.4° +0.5   1%` — caption gone, rain chance kept, so both
  rules verified live. (Pixel screen was PIN-locked for the post-rain-rule screenshot, but
  its header already hid the caption via the inline rule and the unit tests pin the new
  one.)
- Plan file updated with the rain-priority addendum. Nothing committed per convention.
