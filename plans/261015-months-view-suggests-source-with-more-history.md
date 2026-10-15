# Months view: say when another source has more history

## Context

On the Pixel 7 Pro (2026-10-15) the months view on Google showed about 12 days, while NWS at the same
site goes back to Jun 2 (135 days). Each source has its own `daily_history` rows, so how far back the
record goes depends on when that source started being used, not when the app was installed. The user
expected the deeper record and only realised which source was shown afterwards. Their call: the months
view should say when another source has more history.

## Behaviour

- In the months view (Android screen and desktop popup), when another **visible** source's history at
  this site starts at least **30 days** earlier than the shown source's, show one line:
  "**NWS has history back to Jun 2. Tap to switch.**"
  - The date gets a year when it is not this year.
  - When the shown source has no history at all, any source with history qualifies.
  - The candidate needs at least **14 days** of history, so a stray few rows from an old site never
    win.
- If several sources qualify, suggest the one with the most days of history. Ties go to the earlier
  start date, then to the user's source order.
- Tapping the line switches the months view to that source.
  - Android: switches the screen's source, as the source button does.
  - Desktop: switches the displayed source, as the header's source label does.
- The line is hidden when nothing qualifies and in the column view (it is about the months record).

## Shared (`:shared`, `com.weatherwidget.shared.months`)

- **`MonthsSourceDepth`**
  - `depths(rows: List<DailyHistory>, today)` → one `SourceDepth(sourceId, first, days)` per source.
    A day counts when the months line would draw it: the same `resolvePastLineValues` rule (actual,
    else frozen forecast), dates before today.
  - The rows are the DAO's site-box read (`getExtremesInRange`), the same read both platforms already
    make, for every source.
  - `suggestion(currentId, depths, visibleIds)` → the `SourceDepth` to suggest, or null.
    `MIN_EXTRA_DAYS = 30`, `MIN_DAYS = 14`.
- Tests: depth counting (actual, frozen-forecast stand-in, empty rows skipped, today excluded), and
  the thresholds, tie-breaks, a current source with no history, and invisible sources ignored.

## Android

- `MonthsViewActivity.read` makes one extra `dailyHistoryDao.getExtremesInRange` over 547 days, all
  sources, then `MonthsSourceDepth.depths`.
  - It is already one indexed range query per load, read on IO.
- A tappable `TextView` (`months_more_history`) sits above the hint line, styled as a link.
  - The string is `months_view_more_history` with `%1$s` (source name) and `%2$s` (date), in 20 locales.
  - The date is formatted with `DateUtils`.
- Test: `MonthsViewDataIntegrationTest` gets a case where Room holds NWS from 100 days back and
  Google from 12, viewing Google → suggestion is NWS with the right start date; viewing NWS → none.

## Desktop

- `DesktopWeatherRepository.loadDailyActuals` already reads every source's rows in the box for
  547 days. It computes `MonthsSourceDepth.depths` from those same rows, so there is no extra query.
  The result is carried on `RawFetch.historyDepths`.
- `DesktopMonthsModel.build` adds the suggestion to `DesktopMonthsState`.
- `MonthsGraph` shows the line as a small clickable banner at the top of the graph
  (`months_more_history` test tag). Clicking applies the same source switch the header source label
  uses.
- Tests: `DesktopMonthsModelTest` (the suggestion appears and disappears with the data) and a
  `MonthsGraphUiTest` click on the banner.

## Verification

- `:shared`, `:desktop` and app unit suites.
- Emulator: on Google, the line appears when NWS is deeper; tapping it switches to NWS and the line
  disappears.
- Restart the desktop app.

## Implementation notes (2026-10-15)

Built as planned:
- **Shared**: `MonthsSourceDepth`, tests in `MonthsSourceDepthTest`.
- **Desktop**: `RawFetch.historyDepths` from `loadDailyActuals`' existing all-source read,
  `DesktopMonthsState.moreHistory`, a bottom banner on `MonthsGraph` and `switchDisplaySource`.
- **Android**: `MonthsViewActivity.readHistoryDepths`, the `months_more_history` line and the
  `months_view_more_history` string (20 locales).

Verified on the emulator, which matches the Pixel (Google from Oct 4, NWS from May 30). On Google the
line reads "NWS has history back to May 30. Tap to switch."; tapping it fills the two months with
NWS history, and the line goes away.
