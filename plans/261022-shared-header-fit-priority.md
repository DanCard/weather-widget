# One header fit rule in `:shared`, both platforms

## Why

The header's *content* is shared (`HeaderContent`), its *fit* is not, and the two platforms have
drifted (found 2026-10-22):

- Android never shows "from yest" beside a rain %; desktop does.
- Android hid the rain % to make room for the date (removed 2026-10-22); desktop never did.
- Desktop has no ladder at all: left and right clusters are content-sized and never drop anything,
  so a narrow window squeezes the centre until it overflows.
- Partial fit objects live on one side each: `HourlyHeaderFit` (app), `DailyHeaderCentreFit`
  (desktop). Android's decision is spread over five checks in `HeaderWidthChecker`,
  `DailyHeaderBinder` and `DailyForecastHeaderRenderer`.

Per CLAUDE.md, logic shared by Android and desktop is written once in `:shared`, and each platform
only implements reads and writes. Here that means the shared code decides what shows, and each
platform measures its text and draws the result.

## Priority (user, 2026-10-22)

Highest first. Something is dropped only once everything below it is gone.

1. Source label ("Meteo", hourly "Wed • Meteo" → "Wed" dropped first by width, unchanged)
2. Current temperature
3. Rain %
4. Delta
5. Buttons: thermometer + history first, home after, months last ("same rung or nothing", unchanged)
6. Weather icon
7. Date
8. "from yest" caption

What changes from today's Android:
- **The weather icon now drops before the buttons move or go.** Today the icon is the first thing
  the left-cluster ladder drops, but the buttons are placed afterwards against whatever is left,
  so a centred button row that collides with the left cluster goes inline or hidden while the icon
  stays.
- The date already ranks below the buttons. It now also ranks below the weather icon. (Not
  stated by the user; inferred, and open below.)

## Design

`:shared` `com.weatherwidget.shared.util.HeaderFit`, pure, all widths in px, no font engine:

```kotlin
data class Measured(
    val availablePx: Float,
    val sourceLabelPx: Float,
    val tempPx: Float, val iconPx: Float, val deltaPx: Float, val captionPx: Float, val rainPx: Float,
    val buttonPx: Float, val buttonGapPx: Float, val baseButtons: Int, val wantHome: Boolean, val wantMonths: Boolean,
    val datePx: Float,
    val gapPx: Float,
    val inlineButtonsAllowed: Boolean,  // Android hourly/narrow yes; desktop no
    val canCompress: Boolean,           // HourlyHeaderFit's zone narrowing + 80 % temp/icon
)
data class Plan(
    val showIcon: Boolean, val showDelta: Boolean, val showRain: Boolean, val showCaption: Boolean,
    val buttons: Int, val homeShown: Boolean, val monthsShown: Boolean,
    val buttonPlacement: ButtonPlacement,  // CENTER / INLINE / HIDDEN
    val showDate: Boolean,
    val textScale: Float, val zonePx: Float,
)
fun resolve(m: Measured): Plan
```

The order of operations follows the priority list: try everything, then remove items from the
bottom of the list one at a time (caption, date, icon, then the buttons step down CENTER → INLINE
→ HIDDEN with home and months going first, then delta, then rain %). At each step it compresses
before dropping (`HourlyHeaderFit`'s rule, folded in). The first configuration that fits wins.

Out of scope: Android's wide-header ×1.35 (`computeHeaderScale`), which is measured before fitting
and fed in as already-scaled widths, so the fit is compared at the painted size
(`daily_header_fit_at_painted_scale`). Each platform keeps its own drawing and its own text
measurement.

### Android

- `HeaderWidthChecker.resolveHeaderDisclosure`, `resolveDailyIconLayout`, `deltaLabelFitsInHeader`
  and `fitHourlyHeader` collapse to "measure → `HeaderFit.resolve`". `HeaderDisclosureLevel`
  becomes a view of the `Plan`, or is removed.
- The renderer's `resolveDateDrawX` keeps *where* the date goes. *Whether* it shows comes from
  `Plan.showDate`.
- The caption/date alternation (`preferDateOverLabel`) is removed: by the priority list the date beats the caption.
- `HourlyHeaderFit` moves into `HeaderFit` and is deleted from app.

### Desktop

- `DesktopWidgetHeader` measures each piece with its `TextMeasurer` (already done for the date)
  and lays out only what `Plan` keeps. It gains the narrow-window ladder for free.
- `DailyHeaderCentreFit` is deleted. `CrossPlatformDuplicateNamesTest` then guards drift.

## Tests

- `:shared` `HeaderFitTest` (pure): a sweep of available widths from 0 to wide for a full header,
  asserting that every step drops exactly the next item in the priority list, never overlaps, and
  never drops a higher item while a lower one shows. Also specific cases: icon gone before the
  buttons move, rain % kept with no date, home before the base buttons, months same-rung-or-nothing,
  and compression happening before anything is dropped.
- These tests must fail on today's Android ladder (the icon outlives a collapsed button row) and on
  today's desktop ("from yest" beside a rain %).
- The existing Android tests (`DailyIconLayoutLadderTest`, `HourlyHeaderFitTest`,
  `HeaderWidthCheckerHourlyFitTest`, `DailyForecastHeaderDateSlotTest`) are rewritten against
  `HeaderFit` or kept where they cover drawing only.
- Desktop: `DesktopWidgetHeader` Compose test at three widths (wide, crowded, very narrow)
  asserting which test tags are present.
- Manual: emulator widgets at 2–6 columns, desktop popup resized from wide to narrow, screenshots
  of both.

## Phases

1. `HeaderFit` + tests in `:shared`; desktop daily switches to it (smallest consumer).
2. Android daily.
3. Android hourly (and desktop hourly) — folds in `HourlyHeaderFit`.

## Open

- Should "from yest" ever show beside a rain %? The plan assumes **never** (Android's rule; the
  caption ranks last). That changes desktop's header as it is drawn today.
- Is the weather icon above the date (as listed), or the date above the icon?

## Phase 1 — done (2026-10-22)

- `:shared` `HeaderFit` (`resolve`, `fits`; LEFTOVER and TRUE_CENTRE centre modes) + `HeaderFitTest`
  (7 tests, including a 0–800 width sweep in both modes, with and without rain).
- Desktop daily header measures every piece with its `TextMeasurer` and draws what the plan keeps
  (`HEADER_FIT view=daily …` log on change). Hourly header unchanged until phase 3.
- `DailyHeaderCentreFit` + its test deleted. `DesktopDailyHeaderFitTest` renders 29 widths
  (100–800 dp): no "from yest" beside a rain %, narrow headers drop the buttons instead of squeezing
  them, buttons ≤ rain ≤ temp. It fails on the old header (caption beside the rain %).
- Behaviour now visible on desktop: "from yest" only without a rain %; a narrow window drops the date,
  then the buttons, then the delta and so on.
- Lower items may take room a higher one cannot use at all (the small weather icon where the button
  row cannot fit, the rain % where the temperature cannot). Strict "drop everything below" would leave
  that room empty.

Next: phase 2, Android daily (TRUE_CENTRE + INLINE rung, `resolveDateDrawX` keeps position only).

## Phase 2 — done (2026-10-22)

- Android daily: `HeaderWidthChecker.measureDailyHeader` (px, text at `headerScale`, button zones
  unscaled) → `HeaderFit.resolve` in `DailyHeaderResolver`; `HEADER_FIT view=daily … Plan(…)
  Measured(…)` logged per render. `HeaderState.fit` replaces `disclosure`, `iconCount`,
  `iconPlacement`, `showHomeButton`, `showMonthsButton` and `preferDateOverLabel`.
- `HeaderFit.CentreMode.TRUE_CENTRE` never keeps the painted date beside INLINE buttons (the bitmap
  cannot see the RemoteViews row; the old `allowsHeaderDate` rule, now in `:shared`).
- RemoteViews row follows the plan item by item (`HeaderRemoteViewsBinder.applyDailyFit`); painted
  header gets `currentTempText`/`showIcon`/`showDelta`/`deltaLabelText`/`dateText` from it.
- Date vs "from yest" alternation removed (`nextHeaderLabelSwap`, `preferDateOverLabel`): the date
  ranks above the caption. The renderer's `resolveHeaderContention` stays as the painted geometry's
  safety net, date first. Old per-widget `widget_header_label_swap_<id>` prefs are left orphaned
  (a few ints).
- Deleted: `DailyIconPlacement`, `DailyIconLayout`, `resolveDailyIconLayout(FromBounds)`,
  `resolveDailyIconPlacement(FromBounds)`, `scaledClusterEdges`, `DailyIconLayoutLadderTest`,
  `DailyIconPlacementTest` (zone-width cases kept as `DailyIconZoneWidthTest`; placement cases moved
  to `HeaderFitTest`).
- Emulator (360 dp, 7 cols, Silurian): before — icon kept, three buttons inline after the rain %;
  after — icon dropped, buttons centred. Measured: centred slot starts at 346 px, left cluster 306
  without the icon, 375 with it.
- Tests: `:shared` HeaderFitTest 10/10, app unit suite 2606/0.

Next: phase 3, hourly headers on both platforms (fold in `HourlyHeaderFit`, `resolveHeaderDisclosure`).

## Phase 2b — buttons right of the middle (user, 2026-10-22)

"Instead of dropping the current weather icon so buttons can stay centered, … move the default
location right … because the left side is more crowded than right side." Then: "If buttons don't
fit in fixed spot they should move around as before."

- `HeaderFit.BUTTON_ANCHOR = 0.58`: the centred buttons sit on a fixed spot (not one sliding with
  the left cluster), the middle of the free span on both platforms (0.584 on the 360 dp emulator
  widget, ≈ 0.57 in the desktop popup). Rungs: anchor → true middle → INLINE → HIDDEN;
  `Plan.anchor` says which, and Android's container, the painted date and desktop's overlay all
  read it. Months stays on its rung (never moves the row to the middle).
- One geometry for both platforms: `CentreMode` (LEFTOVER / TRUE_CENTRE) removed; desktop's daily
  buttons + date are an overlay at `HeaderFit.centreGroupLeft`, no longer centred in the weighted
  middle. Android < 12 passes `buttonAnchor = 0.5` (no `setViewLayoutMargin`).
- Android: `positionDailyIcons(anchor = fit.anchor)` sets the shared container's MARGIN_LEFT
  (`centre_horizontal` in a FrameLayout ⇒ the margin is the shift); `positionCenterIcons` resets it
  to 0 (a reapply keeps margins). `resolveDateDrawX(anchorX = …)` and the large-Today ink box use it.
- Emulator, 360 dp: icon back, three buttons at 58 % (`HEADER_FIT … anchor=0.58`). 250 dp: no room
  for buttons anywhere, as before.
- Tests: HeaderFitTest 13 (emulator measurements; anchor → middle fallback; the sweep checks both
  spots and that the centre group never overlaps either side), PositionDailyIconsRoboTest (margin
  set, and reset by the hourly view on reapply), DailyForecastHeaderDateSlotTest (date after
  anchored buttons), DesktopDailyHeaderFitTest (history button right of 58 %). Suites: shared 2514,
  desktop 653, app 2609, 0 failures.

## Phase 2c — the date stops jumping (user, 2026-10-22)

"Date jumps around when I click API sources … Both desktop and Fold-4. On Fold-4 date disappears
when home button is shown … Maybe on wide widgets move the home buttons more to the center."

- Cause: buttons were centred on a spot, so home (shown for a non-preferred source) widened the row
  both ways and pushed the date right by half a button; on the Fold (662dp ×1.35) that left 200px
  for a 216px date. Android's painted date also had its own placement (right anchor / free-span
  centre, bounded by the API label), so the source label's width moved it too.
- Now the buttons END on a fixed line `HeaderFit.SEAM_OFFSET_DP` = 70dp (scaled) right of the
  middle; extra buttons grow leftwards; the date starts just after the line (`HeaderFit.dateLeft`).
  70: the 360dp emulator widget needs ≥ 65 for three buttons beside its icon, the Fold's date fits
  up to 93. `BUTTON_ANCHOR` (0.58) replaced; rungs: line → middle (`Seam.MIDDLE`) → INLINE.
- Android: `HeaderState.measured` carries the measurements; `positionDailyIcons(centreOffsetDp)`;
  painted header gets `centerIconsLeftFraction` / `dateLeftFraction`. `resolveDateDrawX` is now
  only a safety net: a date overlapping the painted left cluster or API label is dropped, never
  moved (old placement logic and its 14 tests replaced by 4).
- Desktop: buttons and date are two overlays at `buttonsSpan` / `dateLeft`.
- Verified: desktop, four source clicks (Silur → NWS → Google → Meteo), date at the same x, home
  appearing left of history. Fold (`HEADER_FIT`): graph widgets show icon, both buttons and the date.
  HeaderFitTest 15 (Fold measurements: date at 1133 with and without home and with the
  observations button panned away). Suites: shared 2516, desktop 653, app 2599.

## Phase 3 — hourly headers, both platforms (2026-10-22)

- `HeaderFit` gains INLINE squeezing (`minInlineButton`, `minTextScale`; `Plan.inlineZone`,
  `Plan.textScale`): zones narrow, then icon + temperature shrink, before anything drops — only
  beside INLINE buttons, only where the platform passes the floors (Android hourly: 28dp, 0.8; the
  user's 2026-10-09 rule, not generalised to daily or desktop). "From yest" is offered only if it
  changes neither.
- Android: `HeaderWidthChecker.measureHeader(…, hourly = true)` (was `measureDailyHeader`) drives
  `TemperatureViewBinder` and `HourlyHeaderBinder` (cloud / precip); `HEADER_FIT view=hourly` logged.
  `positionCenterIcons(measured, fit)`: CENTER / INLINE / HIDDEN from the fit instead of the old
  `widthDp < 420` cutoff; CENTER buttons end on the shared line (container MARGIN_LEFT), so history
  stays put switching daily ↔ hourly. `applyFit` replaces `applyDisclosure` for every header.
- Deleted: `HourlyHeaderFit`, `fitHourlyHeader`, `resolveHeaderDisclosure`, `HeaderDisclosureLevel`,
  `deltaLabelFitsInHeader`, `inlineNavRowWidthDp`, `INLINE_NAV_MAX_WIDTH_DP`; `HourlyHeaderFitTest`
  (cases ported to HeaderFitTest), most of `HeaderWidthCheckerTest` (computeHeaderScale kept);
  `HeaderWidthCheckerHourlyFitTest` rewritten on measureHeader + HeaderFit (NATIVE fonts).
- Behaviour change by the drop order: when even the squeezed inline row cannot fit, the buttons go
  before the delta (delta ranks above buttons); the old ladder dropped icon, delta, rain % first.
- Desktop: hourly uses the same fit (four buttons, the emoji measured), buttons an overlay ending on
  the line; the weighted middle is a spacer. "From yest" no longer beside a rain % in hourly either
  (`HeaderRainChanceNoWrapTest` updated). `DesktopDailyHeaderFitTest` → `DesktopHeaderFitTest`.
- Verified: emulator 360dp hourly — everything kept, four buttons centred on the line (previously
  inline below 420dp); desktop daily ↔ hourly — history at the same x. Suites: shared 2521,
  desktop 654, app 2577 (fewer: deleted/merged hourly tests).
