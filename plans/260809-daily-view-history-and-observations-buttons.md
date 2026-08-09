# Plan: Forecast-history + current-observations buttons on the daily view

Date: 2026-08-09

## Problem

The hourly graph header carries a row of center icons — graph selector (☁️/🌧️/🌡️), **current
observations** (thermometer), home (daily), **forecast history** (rising line chart). The daily
forecast view carries none of them.

The user wants two of those four on the daily view as well:

1. **Forecast history** — opens `ForecastHistoryActivity` (Android) / `ForecastHistoryWindow`
   (desktop) showing how the forecast for **today** evolved.
2. **Current observations** — opens `WeatherObservationsActivity` (Android) / `ObservationsWindow`
   (desktop).

Chosen placement (confirmed with the user): **center of the header, next to the date text.**
Both platforms in scope.

## Evidence collected

### Android — how the hourly button works today

1. `app/.../widget/handlers/TemperatureTouchTargets.kt`
   - `setupHistoryShortcut` (line 175) binds `R.id.history_icon`,
     `R.id.forecast_history_activity_touch_zone`, and `..._inline` to a broadcast
     `WidgetActions.ACTION_DAY_CLICK` carrying `date`, `showHistory=true`, `isHistory`,
     `EXTRA_LAT`, `EXTRA_LON`, `EXTRA_SOURCE`. Icon tint `0xAAFFFFFF`, size
     `HeaderConstants.CENTER_ICON_SIZE_DP = 20f`, scaled by `headerScale`.
   - `setupWeatherStationsShortcut` (line 280) binds `R.id.weather_stations_icon` /
     `weather_stations_touch_zone` / `..._inline` to a `PendingIntent.getActivity` for
     `WeatherObservationsActivity`.
   - `positionCenterIcons` (line 347) switches the whole four-icon row between the **floating**
     centered container and the **inline** slots inside the left cluster at `widthDp < 420`, and
     resizes touch zones on API 31+.
2. `app/src/main/res/layout/widget_weather.xml`
   - `hourly_center_header_container` (line 1322) — `LinearLayout`, `top|center_horizontal`,
     `marginTop=-10dp`, holding the four 24dp×48dp touch zones. Declared **after** the nav zones so
     it wins touch priority. All four default `visibility="gone"`.
   - Inline duplicates live inside `current_weather_container` (lines 1593–1671).
3. `ACTION_DAY_CLICK` + `showHistory=true` is routed by
   `WidgetDayClickCoordinator.navigateToHistory` (line 155) → `ForecastHistoryActivity` with
   `EXTRA_TARGET_DATE`/`LAT`/`LON`/`SOURCE`. `isValid` (line 125) requires a parseable `date` and
   finite lat/lon extras.

### Android — why the daily view is different

4. `DailyVisibilityManager.hideUnusedDailyViews` (lines 10–23) sets **all twelve** center-icon views
   (floating + inline) to `GONE`, and `setGraphModeViews` calls it. This is the only reason the
   icons are absent — the views and their setup functions already exist.
5. **The daily header is painted into the graph bitmap**, not composed from RemoteViews:
   - `DailyForecastHeaderRenderer.drawHeader` (line 33) walks a `cursorX` through weather icon →
     temp → delta → "from yest" → rain %, then draws the gear, the API label, and finally the date
     via `resolveHeaderDateLayout`.
   - `DailyGraphRenderer` lines 298–306 set `current_temp`, `current_temp_delta`,
     `precip_probability`, `weather_icon`, `api_source`, `settings_icon` to **`INVISIBLE`** (space
     preserved, pixels come from the bitmap) and `header_date_center`/`header_date_right` to `GONE`.
   - The date is painted by `DailyForecastHeaderRenderer.resolveHeaderDateLayout` (line 176):
     center if `[centerLeft, centerRight]` clears `leftClusterRight + gap` and `apiLeft - gap`;
     else the `DATE_RIGHT_MARGIN_DP = 112f` anchor; else the date is dropped.
6. **Two date-placement implementations exist, and only one is dead.** Verified by grep over
   `app/src`, `desktop/src`, `shared/src` (main + test):
   - `DailyHeaderBinder.bindHeaderDate` (line 30) — **dead**. No caller in `main`, none in tests.
     It is the only code that would ever set `header_date_center`/`header_date_right` to `VISIBLE`;
     six sites set them `GONE` and nothing sets them visible.
   - `DailyHeaderBinder.resolveHeaderDatePlacement` / `resolveHeaderDatePlacementFromBounds` —
     **live**, but no longer for placing the date. `resolveHeaderPrecipPlacement` (line 143) calls
     them twice, and is called from `DailyHeaderResolver.kt:244`; the result reaches
     `DailyGraphRenderer.kt:255` as `HeaderRenderData.showPrecip`. Their job is now *"would the date
     still fit if we also drew the rain %?"* — the header's precip-vs-date priority rule. Covered by
     four tests in `DailyViewHeaderDatePlacementTest`.
   - So the daily header answers the same geometric question twice, in two unit systems: dp /
     RemoteViews (`resolveHeaderDatePlacementFromBounds`) and bitmap px
     (`resolveHeaderDateLayout`). **The centered icon slot must be threaded into both** — adding it
     only to the renderer would leave the precip decision believing the date still fits centered
     while the painted date has shifted left or been dropped, suppressing the rain % to protect a
     date that is not drawn.
   - `dateText` is non-null only at `displayDays.size >= HeaderConstants.DATE_MIN_COLUMNS` (6)
     — `DailyGraphRenderer.kt:162`. **On most widgets the header center is already empty.**
7. `resolveHeaderDateLayout` has three other callers that must stay consistent:
   - `resolveHeaderDateBounds` (line 142) — used for rain-overlap suppression.
   - `resolveDeltaLabelVisible` (line 267) — the "from yest" caption yields to the date.
   - `resolveHeaderInkBottom` (line 359) — the per-column header ceiling for the large-Today
     overlay. Per `plans/260807-today-overlay-header-aware-above-ceiling.md` this must measure
     **everything the header draws over that column**, so the new icon slot has to be `consider()`ed
     there or the overlay will draw under the icons.

### Desktop

8. `desktop/.../Main.kt` `WidgetHeader` (line 1412). The center cluster (lines 1545–1614) is a
   plain `if (isHourly) { …four icons… } else { Text(date) }`. The two icons we need already exist
   verbatim in the hourly branch:
   - observations: `painterResource("drawable/ic_thermometer.xml")`, tint `White.copy(alpha=0.67f)`,
     `size((15*scale).dp)`, `testTag("open_observations_header")` → `onOpenObservations()`.
   - history: `painterResource("drawable/ic_forecast_history_line.xml")`, tint
     `White.copy(alpha=0.6f)`, `testTag("open_forecast_history")` →
     `onOpenHistory(targetHour.toLocalDate())`.
   - `onOpenHistory` is already plumbed `WidgetPopup` → `WidgetHeader` → `Main.kt:926`
     (`historyInitialDate`, `historyVisible`, `historyShowRequestId++`). `onOpenObservations`
     likewise. **No new plumbing on desktop.**

## What the header row will look like

Modelled from the real constants (left-cluster cursor walk in `drawHeader`,
`apiLeft = W − 44 − (14 + apiTextWidth) + 10`, gear 18dp at `W−2`, date 20dp, gap 6dp, right anchor
`W−112`, slot 2×24dp). Text widths approximated from glyph-advance ratios rather than
`Paint.measureText`, so the exact dp at which case D flips is ±a few dp; the ordering is not.

```
L = painted left cluster (weather icon / temp / delta / rain %)   # = the two new buttons
D = painted date           A = API label                          G = gear

A) 440dp, 6 cols, rain 30%   -- the common wide case
  |LLLLLLLLLLLLLLLLLLLLLLL    DDDDDDDDDD #########                           AAAAA   GGG   |
   72° +1.2  30%              Sat 9      🌡 📈                                NWS      ⚙
   icons=centered   date=left of icons

C) 370dp, 5 cols   -- date needs 6 cols, so the center is free
  |LLLLLLLLLLLLLLLLLLLLLLLLLLLL         ###########                        AAAAAA   GGGG   |
   icons=centered   date=not shown (unchanged from today)

D) 400dp, worst-case left cluster ("100.4° +12.4 100%") + "Meteo"
  |LLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLL##############    DDDDDDDDDDD    AAAAAAA   GGGG   |
   icons=inline (appended to left cluster)   date=right anchor

E) 340dp, same content, crowded
  |LLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLL#################        AAAAAAAA   GGGGG   |
   icons=inline   date=dropped

F) 300dp, minimal disclosure (no weather icon, no delta, no rain)
  |LLLLLLLL                            #############                    AAAAAAA    GGGGG   |
   icons=centered   date=not shown
```

Case B ("from yest" caption instead of a rain %) renders identically to A.

When the daily view is **navigated off today**, the observations button drops (Step 3.5) and the
slot halves to 24dp, so the header gets *less* crowded, not more:

```
G) 440dp navigated -- history button alone
  |LLLLLLLLLLLLLLLLLLLLLLL      DDDDDDDDDD #####                             AAAAA   GGG   |
   icons=centered   date=left of icons        (only 📈)

H) 400dp navigated -- case D with one icon
  |LLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLL  #####           DDDDDDDDDDD    AAAAAAA   GGGG   |
   icons=centered   date=right anchor
```

H is the same content as D, which needed the inline fallback with two icons and stays **centered**
with one — worth a regression case, since it is the cheapest proof that the width is derived from
the live icon count rather than a constant.

### Placement ladder — buttons rank above the date

An earlier draft of this plan said the daily view would use **only** the floating centered pair and
skip the hourly view's inline fallback. Modelling the geometry disproved that: in case D the
centered slot collides with the left cluster by ~1dp and **both buttons vanish** — the feature
disappears exactly on the densest headers. The ladder below fixes D and E.

1. **Centered pair**, date placed immediately left of the slot (cases A, C, F).
2. Centered slot collides with the left cluster → **icons go inline**, appended to the left cluster
   via the existing `weather_stations_touch_zone_inline` /
   `forecast_history_activity_touch_zone_inline` views; the date is then re-placed with
   `leftClusterRight` extended by the inline width: center → right anchor → dropped (cases D, E).
3. Even inline will not clear the API label → icons hidden. Only reachable on headers that are
   already at `isIconWidth` / `disclosure == NONE`.

The date yields before the buttons do, deliberately: the buttons are the requested feature and the
date is already absent below 6 columns.

**Why the inline fallback works in a bitmap-painted header:** `DailyHeaderResolver.bind` binds the
same strings at the same sizes into the RemoteViews (`bindCurrentTemp` at
`DAILY_CURRENT_TEMP_TEXT_SIZE_DP`, `bindPrecipProbability`, `bindDelta`, `bindDeltaLabel`) and
`DailyGraphRenderer` lines 298–302 then set them **`INVISIBLE`, not `GONE`** — they reserve exactly
the width the painted text occupies, so an inline icon appended after them lands immediately right
of the painted rain %. Daily inline zones are fixed at **32dp** each (65dp for the pair incl. the
1dp first-zone margin) rather than the hourly view's width-dependent 32/40/48dp, so a single
constant feeds the fit math.

## Proposed change

### Step 0 — `:app` delete the dead `bindHeaderDate` (separate, behavior-free commit first)

1. Delete `DailyHeaderBinder.bindHeaderDate` (lines 30–70). Nothing calls it.
2. Keep `resolveHeaderDatePlacement`, `resolveHeaderDatePlacementFromBounds`, the
   `HeaderDatePlacement` enum and all four `DailyViewHeaderDatePlacementTest` cases — they are live
   via `resolveHeaderPrecipPlacement` (Evidence 6). Retitle the enum's KDoc to say what it now
   decides ("whether a date *would* fit — input to the header rain-% priority rule"), since the
   name reads as if it still positions a TextView.
3. Do **not** delete the `header_date_center` / `header_date_right` TextViews from
   `widget_weather.xml`. They become write-only after this, but six sites set them `GONE` and
   `CurrentTempTouchRoutingRoboTest:469-470` asserts on them; removing the views is a wider cleanup
   with no bearing on this feature.
4. Verify with `./gradlew :app:compileDebugKotlin` and the existing
   `DailyViewHeaderDatePlacementTest` — no behavior change, so nothing else should move.

Doing this first keeps the icon work below from being reviewed alongside an unrelated deletion.

### Step 1 — `:app` shared geometry: reserve a centered icon slot in the painted header

New pure helper next to the existing pure `resolveHeaderDatePlacementFromBounds` idiom, so it is
testable without a font engine (see `robolectric_no_font_engine`):

1. Add to `DailyForecastHeaderRenderer`:
   ```kotlin
   internal fun resolveDateDrawX(
       widthPx: Float,
       dateWidth: Float,
       leftClusterRight: Float,
       dateRightBoundary: Float,
       centerIconsWidth: Float,   // 0f when the icons are not shown
       gapPx: Float,
       rightMarginPx: Float,
   ): Float?
   ```
   Rules, matching the ladder above:
   - `centerIconsWidth == 0f` (icons HIDDEN, or INLINE — inline icons are already folded into
     `leftClusterRight` by the caller) → today's exact behavior: center, else right anchor, else
     null. This covers cases D, E and F's fallback.
   - `centerIconsWidth > 0f` (icons CENTER) → the pair owns
     `[widthPx/2 - centerIconsWidth/2, widthPx/2 + centerIconsWidth/2]`.
     - Preferred: date drawn **immediately left of the slot** —
       `drawX = widthPx/2 - centerIconsWidth/2 - gapPx - dateWidth/2`; valid when
       `drawX - dateWidth/2 >= leftClusterRight + gapPx`. This is case A:
       `… 30%   Sat 9 [🌡][📈]   NWS ⚙`.
     - Fallback: the existing right anchor, with the left bound raised to
       `max(leftClusterRight, iconsRight)`.
     - Else `null` (date dropped).
2. `resolveHeaderDateLayout` gains a `centerIconsWidthPx: Float` parameter and delegates its x
   choice to `resolveDateDrawX`. All four call sites pass it through.
3. `DailyForecastGraphRenderer.HeaderRenderData` gains `centerIconsWidthPx: Float = 0f`.
4. `resolveHeaderInkBottom` gains a `consider(iconsLeft, iconsRight, upOffset + iconBoxPx)` branch
   when the slot is non-zero, so the large-Today overlay ceiling accounts for the icons.

Width constants (new, in `HeaderConstants`):
```kotlin
const val DAILY_CENTER_ICON_ZONE_WIDTH_DP = 24f   // mirrors the hourly floating zones
const val DAILY_INLINE_ICON_ZONE_WIDTH_DP = 32f   // fixed, unlike hourly's 32/40/48 ladder
```
Width is `zoneWidth * iconCount` (+1dp first-zone margin when inline), scaled by `headerScale` like
every other header metric. `iconCount` is **2** when today is in view and **1** when it is not
(Step 3.5) — never a constant.

### Step 2 — `:app` decide once where the icons go

In `DailyHeaderResolver.resolve`, next to the existing `disclosure` / `headerScale` computation
(which already measures the same clusters via `HeaderWidthChecker`):

```kotlin
val iconPlacement = HeaderWidthChecker.resolveDailyIconPlacement(   // CENTER | INLINE | HIDDEN
        context, dimensions.widthDp, apiSourceText, apiTextSizeDp,
        currentTempText = formattedTemp, deltaText = deltaTextForFit,
        precipText = precipTextForFit, precipTextSizeDp = precipTextSizeDp,
        includeIcon = disclosure.showsIcon(),
        currentTempSizeDp = HeaderConstants.DAILY_CURRENT_TEMP_TEXT_SIZE_DP,
        iconCount = if (todayInView) 2 else 1,   // observations button drops off-today
        headerScale = headerScale,
    )
```

`resolveDailyIconPlacement` is a new small pure-ish function returning `CENTER | INLINE | HIDDEN`,
implementing the three-rung ladder against the same two bounds `resolveHeaderDisclosure` already
computes (`leftClusterRight + gap`, `apiLeft - gap`):

```kotlin
CENTER  if  slotLeft >= leftClusterRight + gap  &&  slotRight <= apiLeft - gap
INLINE  else if  leftClusterRight + inlinePairWidth + gap <= apiLeft
HIDDEN  otherwise
```
plus an upfront `HIDDEN` when `isIconWidth` or `disclosure == NONE`.

`iconPlacement` is stored on `DailyViewHandler.HeaderState` so **one decision** drives all three
consumers — the RemoteViews visibility (floating vs inline zones), the bitmap's
`centerIconsWidthPx` (non-zero only for `CENTER`), and the `leftClusterRight` the date math sees
(extended by the inline pair width for `INLINE`). No second measurement, no drift.

**Also thread the slot into the precip-priority path** (Evidence 6). `resolveHeaderPrecipPlacement`
and `resolveHeaderDatePlacement`/`...FromBounds` gain a `centerIconsWidthPx: Float = 0f` parameter
mirroring Step 1's rules (prefer left-of-slot, then the right anchor, else `null`), and
`DailyHeaderResolver.kt:244` passes the same width it passed in Step 2. Without this the rain %
would be suppressed to protect a centered date that the renderer has already moved or dropped.
The default of `0f` keeps the four existing tests green unchanged.

### Step 3 — `:app` show and wire the two icons in the daily view

1. `TemperatureTouchTargets.kt`: split the lat/lon derivation out of `setupHistoryShortcut` so the
   daily view can pass coordinates it already has:
   ```kotlin
   internal fun setupHistoryShortcutAt(
       context, views, appWidgetId, date: LocalDate, lat: Double, lon: Double,
       displaySource, setVisibility: Boolean = false, scale: Float = 1.0f)
   ```
   The existing `setupHistoryShortcut(centerTime, hourlyForecasts, …)` becomes a thin wrapper that
   resolves `lat`/`lon` from `hourlyForecasts.firstOrNull()` (today's behavior, unchanged) and
   delegates. No behavior change for the three hourly callers.
2. New `positionDailyIcons(views, placement, density)` in `TemperatureTouchTargets.kt`, taking the
   `CENTER | INLINE | HIDDEN` from Step 2. It touches **only** the stations and history views —
   graph-selector and home stay `GONE` in every branch:
   - `CENTER` → `weather_stations_icon`/`weather_stations_touch_zone` and
     `history_icon`/`forecast_history_activity_touch_zone` `VISIBLE`; both `_inline` zones `GONE`.
   - `INLINE` → the two `_inline` zones `VISIBLE` (widths set to
     `DAILY_INLINE_ICON_ZONE_WIDTH_DP` on API 31+ via `setViewLayoutWidth`, matching the constant
     the fit math used); floating zones `GONE`.
   - `HIDDEN` → all four `GONE`.
   Unlike the hourly `positionCenterIcons`, the daily inline width is a fixed 32dp rather than the
   width-dependent 32/40/48 ladder, so exactly one constant feeds both the fit math and the layout.
3. `DailyVisibilityManager.hideUnusedDailyViews` keeps hiding all twelve (it is the safe default and
   is also used by text mode); `DailyGraphRenderer` calls `setupWeatherStationsShortcut`,
   `setupHistoryShortcutAt(date = ctx.today, lat, lon, ctx.displaySource, scale = headerScale)` and
   then `positionDailyIcons` **after** `DailyVisibilityManager.setGraphModeViews`, so the
   graph path re-enables exactly the two it wants.
   - Text mode (`setTextModeViews`) is untouched — the single-row layout has no room.
4. **Target date follows what is on screen** (user's rule):
   ```kotlin
   val todayInView = displayDays.any { it.isToday }
   val historyTargetDate = if (todayInView) ctx.today else ctx.centerDate
   ```
   `displayDays.any { it.isToday }` is the accurate test, not `dateOffset == 0` — the daily view
   shows yesterday alongside today, so small offsets can still have today on screen. It is also the
   same predicate `DailyGraphRenderer` already uses at lines 110/169/194.

   This makes the daily view symmetric with the hourly one, whose `isToday`
   (`TemperatureViewBinder.kt:120-125`) likewise means *"today is within the visible window"*.
5. **The observations button hides when today is out of view.** "Current observations" is
   inherently now-ish, and the hourly view already drops its thermometer in exactly this case
   (`positionCenterIcons(isToday)`, `TemperatureTouchTargets.kt:387-391`). Panning the daily view
   off today therefore leaves a **single** history button.
   - Consequence for Step 2: the icon-pair width must be derived from the **live icon count**, not
     a constant — 48dp centered / 65dp inline with both, 24dp / 33dp with history alone. A
     navigated header thus has *more* room for the date, not less. `resolveDailyIconPlacement`
     takes `iconCount: Int`.

### Step 4 — `:desktop` mirror it

In `Main.kt` `WidgetHeader`, replace the `else` branch of the center cluster with a `Row` holding
the date `Text` followed by the same two `Icon`s used in the hourly branch:

```kotlin
Row(horizontalArrangement = Arrangement.spacedBy((6 * scale).dp),
    verticalAlignment = Alignment.CenterVertically) {
    Text(targetHour.format(dateFormatter), …)                       // unchanged
    Icon(ic_thermometer.xml, …, testTag("open_observations_header_daily")) { onOpenObservations() }
    Icon(ic_forecast_history_line.xml, …, testTag("open_forecast_history_daily")) {
        onOpenHistory(LocalDate.now())
    }
}
```

Distinct test tags because both branches can never be composed at once, but a shared tag would make
the intent of each test ambiguous. Sizes/tints copied from the hourly branch so the two headers
match. `onOpenHistory(LocalDate.now())` = today, matching Android Step 3.4.

### Step 5 — `:app` alternate which of date / "from yest" gets dropped

Today the contention is settled by a fixed priority (`DailyForecastHeaderRenderer.shouldDrawDeltaLabel`,
line 320): if the date fits *with* the caption, both draw; otherwise **the caption is always the
one sacrificed**. "from yest" is therefore the structural permanent loser. Adding the 48dp icon
slot makes this contention more frequent, so it needs fixing alongside.

**Requested behavior:** when both cannot fit, alternate which one is dropped, swapping **on every
render** (user's choice over a time-bucketed swap — accepted with the flicker note below). If
neither fits on its own, drop both.

The two are **not symmetric**, so the survivor must be re-checked rather than assumed:
- the caption sits in the **left cluster** — its fit is `leftClusterRightWithLabel + gap <= apiLeft`;
- the date is **centered** — its fit is against the left cluster *and* `apiLeft`, and dropping the
  caption shortens the left cluster, which can free the date's centered slot. Dropping the date
  frees center space the caption cannot use.

1. **Collapse the two decisions into one pure function.** Replace `shouldDrawDeltaLabel` with
   ```kotlin
   internal data class HeaderContention(val showDeltaLabel: Boolean, val showDate: Boolean)

   internal fun resolveHeaderContention(
       hasDateText: Boolean,
       dateFitsWithLabel: Boolean,
       dateFitsWithoutLabel: Boolean,
       labelFitsAlone: Boolean,     // leftWithLabelRight + gap <= apiLeft
       preferDateOverLabel: Boolean, // the per-render alternation
   ): HeaderContention
   ```
   Ladder:
   - `dateFitsWithLabel` → both.
   - `!hasDateText` → `showDeltaLabel = labelFitsAlone` (no contention; today's behavior).
   - contention → try the alternation's winner first, then the other, then neither:
     - `preferDateOverLabel && dateFitsWithoutLabel` → date only (today's behavior).
     - `!preferDateOverLabel && labelFitsAlone` → caption only, **date dropped** (new).
     - else `dateFitsWithoutLabel` → date only; else `labelFitsAlone` → caption only;
       else → **both dropped**.
   One function so the caption and date decisions cannot disagree — currently
   `resolveDeltaLabelVisible` and `resolveHeaderDateLayout` each re-derive their half.
2. **Thread the flag.** `DailyForecastGraphRenderer.HeaderRenderData` gains
   `preferDateOverLabel: Boolean = true` (default preserves today's behavior for every other
   caller). `drawHeader` resolves the contention once, up front, and passes `showDate` down —
   `resolveHeaderDateLayout` returns `null` when the caption won.
3. **Drive it per render.** `WidgetStateManager` gains a per-widget counter
   (`header_label_swap_<id>`, SharedPreferences) incremented once per daily graph render in
   `DailyGraphRenderer.render`; `preferDateOverLabel = counter % 2 == 0`. Per-widget so two widgets
   don't lock in step, persisted so it survives process death.
4. **Mirror into the RemoteViews-side twin.** `HeaderWidthChecker.deltaLabelFitsInHeader` and the
   `deltaLabelText` computation at `DailyHeaderResolver.kt:261` must consume the same counter value,
   or `HeaderState.deltaLabelText` will disagree with what the bitmap paints. Read the counter once
   in `DailyHeaderResolver.resolve` and put it on `HeaderState` alongside `iconPlacement`.
5. `resolveHeaderInkBottom` and `resolveHeaderDateBounds` both call the contention logic — they must
   pass the same `preferDateOverLabel` so the overlay ceiling and rain-overlap suppression measure
   what is actually drawn.

**Accepted trade-off (user's explicit choice):** the daily widget repaints on nav taps, source
toggles, screen unlock and every UI tick, so on a header that is genuinely too tight the date and
caption will visibly swap as you interact with it. This is intended — it guarantees both values are
reachable rather than one being permanently starved.

**Android only.** The desktop header lays out in a Compose `Row` with no fit contention — nothing
to alternate. Noted so the parity checklists don't flag it.

## Testing

1. **`:app` pure unit** — new `DailyForecastHeaderRendererDateSlotTest` (framework-free, Short) for
   `resolveDateDrawX`:
   - `centerIconsWidth = 0` reproduces every existing center/right/null outcome (regression guard).
   - Roomy header + icons → date lands immediately left of the slot, with exactly `gapPx` clearance.
   - Left cluster grown until the left-of-slot position collides → falls back to the right anchor.
   - Right anchor also colliding (icons wide / API label long) → `null`.
   - Slot never overlaps the returned date bounds in any passing case (invariant assertion).
2. **`:app` cross-check the two implementations** — a new test asserting
   `DailyHeaderBinder.resolveHeaderDatePlacementFromBounds` and
   `DailyForecastHeaderRenderer.resolveDateDrawX` agree on *whether* a date fits, over a table of
   widths × left-cluster widths × slot widths (0 and 48dp). This is the guard Evidence 6 says is
   missing today: the two have always had to agree and nothing checked it.
3. **`:app` pure unit** — `resolveDailyIconPlacement` ladder: CENTER on a roomy header; INLINE when
   the centered slot collides with the left cluster but the pair still clears the API label
   (case D); HIDDEN when neither fits; HIDDEN when `isIconWidth` or `disclosure == NONE`.
   Include case D's near-miss explicitly — a 1dp collision is what broke the original design.
   Plus **case D vs case H**: identical header content, `iconCount = 2` → INLINE, `iconCount = 1`
   → CENTER. Cheapest proof the width tracks the live icon count rather than a constant.
3b. **`:app` target-date rule** — `todayInView` → history intent carries `ctx.today`; navigated off
   today → carries `ctx.centerDate`; and the observations icon is `GONE` in the second case.
   Drive it off `displayDays.any { it.isToday }`, including the boundary where a non-zero
   `dateOffset` still has today on screen (yesterday+today window) — the case `dateOffset == 0`
   would get wrong.
4. **`:app` Robolectric** — extend `HistoryIconVisibilityRoboTest` (`@Category(LongDuration)`):
   - `daily graph mode shows stations and history icons` — `weather_stations_touch_zone` and
     `forecast_history_activity_touch_zone` `VISIBLE`; `home_touch_zone`,
     `graph_selector_touch_zone` and all `_inline` zones `GONE`.
   - `daily graph mode falls back to inline icons on a crowded header` — the two `_inline` zones
     `VISIBLE`, floating pair `GONE`. **The buttons must never both be GONE while the header
     renders** — assert that as the invariant, since that is the failure the ladder exists to
     prevent.
   - `daily text mode shows no center icons` — all twelve `GONE`.
   - Per `robolectric_no_font_engine`, assert **visibility and dp geometry only** — no pixel
     assertions on the painted date; and confirm each new test can fail (invert once locally).
5. **`:app` intent contract** — extend `DailyViewHandlerIntentContractTest`: the daily history
   `PendingIntent` carries `ACTION_DAY_CLICK`, `date == today`, `showHistory=true`, finite
   `EXTRA_LAT`/`EXTRA_LON` and a non-empty `EXTRA_SOURCE` — i.e. it satisfies
   `WidgetDayClickCoordinator.isValid`. This is the assertion that catches a silently dead button.
6. **`:app` overlay regression** — one case in the existing today-overlay header-ceiling test
   asserting `resolveHeaderInkBottom` over the center column returns the icon-box bottom when the
   slot is active (guards the `plans/260807-…-above-ceiling` invariant).
7. **`:desktop` Compose UI** — two tests in `DesktopUiTest.kt` alongside
   `headerObservationsButtonOpensObservations`: with `viewMode = ViewMode.DAILY`, clicking
   `open_forecast_history_daily` invokes `onOpenHistory` with `LocalDate.now()`, and clicking
   `open_observations_header_daily` invokes `onOpenObservations`.
8. **`:app` pure unit** — rewrite `DailyForecastHeaderDeltaLabelTest` (6 existing cases) against
   `resolveHeaderContention`. All six keep their current expectations with
   `preferDateOverLabel = true`, proving the default is behavior-preserving. New cases:
   - contention + `preferDateOverLabel = false` + caption fits alone → caption shown, **date
     dropped** (the new behavior).
   - contention + `preferDateOverLabel = false` + caption does *not* fit alone but date does →
     falls back to the date (alternation never blanks a slot that could have been filled).
   - neither fits alone, either flag → **both dropped**.
   - invariant across the whole table: `showDeltaLabel && showDate` only when `dateFitsWithLabel`.
9. **`:app` alternation counter** — assert consecutive daily renders of the same widget flip
   `preferDateOverLabel`, that two widget ids advance independently, and that
   `HeaderState.deltaLabelText` (RemoteViews side) agrees with the bitmap's decision for the same
   counter value — the desync guarded against in Step 5.4.
10. **Build / manual verify**
   - `./gradlew :app:testShortDebugUnitTest :desktop:testByDurationDesktop`
   - `./gradlew :app:testLongDebugUnitTest --tests "*HistoryIconVisibility*"`
   - `./gradlew installDebug`, then screenshot the daily widget at 4/5/6+ columns
     (`adb exec-out screencap -p > … && convert … .jpg`) to confirm the date sits left of the icons
     at ≥6 columns and the icons center cleanly below that; tap both.
   - `scripts/buildStart-desktop.sh` and check the daily popup header (per
     `feedback_auto_restart_desktop`).

## Out of scope / notes

1. Only `bindHeaderDate` is dead; `resolveHeaderDatePlacement*` is live via the rain-% priority rule
   (Evidence 6). Step 0 deletes the former; Step 2 extends the latter. An earlier draft of this plan
   claimed all three were dead — that was wrong, and acting on it would have desynced the rain-%
   decision from the painted date.
2. The daily view now uses the `_inline` left-cluster slots as its rung-2 fallback. That
   contradicts the KDoc on `HeaderWidthChecker.deltaLabelFitsInHeader` ("Daily view passes the
   default 0 — its inline zones are always GONE"), so that comment must be updated and the daily
   caller at `DailyHeaderResolver.kt:263` must pass the inline pair width when
   `iconPlacement == INLINE`; otherwise the "from yest" caption can reappear over the icons — the
   exact Pixel 7 Pro bug that KDoc was written to record.
3. No graph-selector or home icon on the daily view — the daily view *is* home, and the selector
   only cycles hourly graphs.
4. The painted date lives in the `graph_view` bitmap while the icons are RemoteViews. The bitmap is
   rendered at the widget's own dp size, but `graph_view` uses `fitCenter`, which the codebase
   already documents as introducing a small offset (see the `dual_touch_zone` comment,
   `widget_weather.xml:1675`). A few px of drift between the date and the icons is possible on some
   hosts; the 6dp gap absorbs it. Worth an eyeball during Step 7's screenshot check.


---

## Implementation notes (as built, 2026-08-09)

Five things changed from the plan once it met real devices. Recorded here because each was a wrong
prediction, not a refinement.

1. **Order is buttons-then-date, not date-then-buttons.** The approved mockup put the date first,
   and Android's preferred rung did try that — but on both a Samsung fold and a ~350dp emulator the
   left cluster (`76.7° +5.8 from yest`) reaches past where the date would have to start, so it
   fell through to the right anyway while desktop always drew it first. Rather than keep two
   platforms disagreeing, the date-before rung was removed and desktop's Row reordered. See
   `resolveDateDrawX`'s KDoc.
2. **The fixed 112dp right anchor is a position, not a search.** On the emulator it overlapped the
   buttons by 11dp while a 53dp gap sat immediately to its right, and the date was dropped with
   room to spare. Added a final rung that centres the date in whatever span is actually free
   between the buttons and the API label. Reachable only when the buttons hold the middle.
3. **Button zone width is width-dependent.** 24dp zones put the icons ~4dp apart and read as one
   glued-together control on Samsung; 40dp fixed that but cost the date 16dp of its gap and pushed
   it off the emulator entirely. Now 40dp at ≥420dp, 24dp below.
4. **The buttons were drawn high, not the date low.** Everything the daily header paints is
   top-aligned at `upOffset` (-2dp), but the buttons live in `hourly_center_header_container` at
   `marginTop=-10dp` — correct for hourly, whose `current_temp` TextView shares that -10dp, wrong
   for daily, which paints its text. Lifting the date was tried first and clipped it against the
   bitmap's top edge. Fixed by lowering the container to -4dp in daily mode only, with the hourly
   value set explicitly too so a partial RemoteViews update cannot carry one into the other.
5. **The Robolectric visibility test in the plan could not work.** With no weather data the daily
   view renders TEXT mode, so a render-level assertion about the graph path silently proved
   nothing (probe: `graph_view=GONE, text_container=VISIBLE`). Replaced by
   `PositionDailyIconsRoboTest`, which drives `positionDailyIcons` directly, plus a text-mode case
   in `HistoryIconVisibilityRoboTest`. Likewise `resolveDailyIconPlacement` grew a
   `...FromBounds` twin so the ladder is tested framework-free — Robolectric's `measureText` is not
   a trustworthy input for a fit assertion.

### Test inventory as built

| File | Covers |
|---|---|
| `DailyForecastHeaderDateSlotTest` (12) | `resolveDateDrawX`: no-slot regression, buttons-then-date order, never-left / never-overlap sweeps, free-span rung, icon-count width |
| `DailyForecastHeaderDeltaLabelTest` (12) | `resolveHeaderContention`: 6 original fixed-priority cases + alternation, fallbacks, and two full truth-table invariants |
| `DailyIconPlacementTest` (9) | CENTER/INLINE/HIDDEN ladder incl. the 1dp collision boundary and a monotonicity sweep over icon count |
| `PositionDailyIconsRoboTest` (7) | visibility wiring, floating-vs-inline exclusivity, observations dropping off-today, applied zone widths |
| `HistoryIconVisibilityRoboTest` (2) | hourly unchanged; daily text mode shows no buttons |
| `DesktopUiTest` (+3) | daily history/observations buttons, and daily tags not leaking into the hourly header |

Full sweep at completion: **2871 tests, 0 failures** across `:app`, `:desktop`, `:shared`.

### Not done

- The cross-check test pairing `resolveHeaderDatePlacementFromBounds` against `resolveDateDrawX`
  (plan testing item 2). Both were updated in step with the free-span rung and the order change,
  but nothing yet asserts they agree — still the plan's own stated risk from Evidence 6.
- The intent-contract assertion that the daily history `PendingIntent` satisfies
  `WidgetDayClickCoordinator.isValid` (plan testing item 5). The button is verified by hand on
  device, not by test, so a malformed intent would still ship silently.
- The overlay-ceiling regression case for the new icon slot in `resolveHeaderInkBottom`
  (plan testing item 6). The code path is implemented and threaded, but untested.
