# Daily home button recentres on today (main source, today off screen)

## Request

User (2026-10-15): "daily forecast view: when on the main api, and the today column is not in the
view, show the home button. When home button is pressed, put the today column in the view plus one
day of history."

## Today

The daily header's home button (`PreferredSourceHome`, 2026-09-01) shows **only** while the displayed
source is not the preferred (first visible) one, and its tap resets the source. Panned away from
today on the preferred source, nothing gets you back except tapping the arrows.

## Rule (`:shared`, extends `PreferredSourceHome`)

| Displayed source | Today in view | Button | Tap |
|---|---|---|---|
| not preferred | any | shown (unchanged) | reset source (unchanged — offset untouched) |
| preferred | no | **shown (new)** | **set date offset to the home offset** |
| preferred | yes | hidden | — |

The non-preferred row is deliberately unchanged: the request is conditional on "on the main api".

**Home offset** — the offset whose window starts at yesterday with today second:

- `shouldSkipYesterday(cols)` (≤ 8 cols) → offset **−1**: `getDisplayCenterDate` gives `today`,
  `skipHistory` is off, so the window is `Yesterday, Today, +1, …`. (Offset 0 there is today-first —
  no history column.)
- wide (> 8 cols) → offset **0** (already `Yesterday, Today, …`).
- 1–2 cols → offset 0 (`getDayOffsets` never shows yesterday; today-only is all that fits).

New shared functions:

- `PreferredSourceHome.homeDateOffset(numColumns): Int`
- `PreferredSourceHome.homeAction(currentSourceId, visibleSourceIds, todayInView): HomeAction?`
  — `RESET_SOURCE`, `RECENTER_TODAY`, or null (no button). Replaces the two platforms' direct
  `shouldShowHomeButton` calls so the decision lives in one place.

Note: a narrow widget at the home offset (−1) has today in view, so the button disappears — and
the arrows' "offset 0 = today-first" window is still one right-tap away.

## Android

- `DailyHeaderResolver`: compute `todayInView` from the `visibleFrom..visibleTo` it already has;
  `wantHomeButton = homeAction(...) != null`; carry the action to the binder.
- `TemperatureTouchTargets`: `setupSourceHomeShortcut` binds `ACTION_RESET_SOURCE` or a new
  `ACTION_RECENTER_TODAY` (own `WidgetRequestCodes` entry, so the two PendingIntents never alias).
- `WidgetActionReceiver` / `WidgetIntentRouter`: `handleRecenterToday` →
  `stateManager.setDateOffset(id, homeDateOffset(numColumns))`, repaint via the same path the nav
  arrows use (cache-first, no fetch). Logged `RECENTER_TODAY from=<offset> to=<offset>`.
- Content description: "Back to today".

## Desktop

- `DesktopWidgetHeader`: same `homeAction(...)` using the `todayInView` the daily branch already
  publishes upward (it accounts for zoom-out's prepended history columns, which
  `getVisibleDateRange` doesn't know).
- `RECENTER_TODAY` tap → `onUpdateConfig(config.copy(dateOffset = homeDateOffset(cols)))`. Desktop
  needs the daily column count in the header: publish it upward with `dailyTodayInView` (same
  mechanism). ~~Zoom-out (`dailyExtraHistory`) is left alone~~ — **wrong** (user, 2026-10-15: "shows 3 days of
  history instead of the requested one"; config had `dailyExtraHistory: 2`). The recentre also sets
  `dailyExtraHistory = 0`.

## Tests

- `:shared` `PreferredSourceHomeTest`: the table above; `homeDateOffset` for 1, 2, 3, 5, 8, 9, 12
  cols, and for each the window from `getVisibleDateRange(today, homeDateOffset(c), c, skip)` has
  today at index 1 (index 0 for 1–2 cols) and yesterday at index 0.
- Android Robolectric: preferred source, offset +4 → home zone bound to `ACTION_RECENTER_TODAY`;
  offset 0 → hidden; non-preferred → still `ACTION_RESET_SOURCE`. Integration: firing the action on
  a 5-col widget sets offset −1 and the rendered day list starts yesterday, today.
- Desktop `DesktopUiTest`: `daily_home_button` appears after panning right on the preferred source,
  click → `dateOffset` = home offset; absent at offset 0.

## Verification

Build + install; on a device pan right past today on the primary source, screenshot (button
visible), tap it, screenshot (yesterday + today in view, button gone). Desktop: rebuild and restart
(`scripts/buildStart-desktop.sh`), same check.

## Outcome (2026-10-15)

- `:shared` `PreferredSourceHome.homeAction` / `homeDateOffset(numColumns, skipYesterday)` (desktop
  passes its own skip: it decides from the full column count but draws one fewer under the Today overlay).
- Android: `setupSourceHomeShortcut` → `setupDailyHomeShortcut(action)`; `ACTION_RECENTER_TODAY`,
  `WidgetRequestCodes.recenterToday` (base 5450); `DailyInteractionRenderer.recenterToday` logs
  `DAILY_RECENTER_TODAY offset=a->b cols=n`.
- Desktop: `DesktopDailyViewState.homeDateOffset`, published to the header beside `todayInView`.
- Tests: `PreferredSourceHomeTest` (+6), `DailyHomeRecenterTodayIntegrationTest` (5, Robolectric),
  `DesktopDailyForecastModelTest` home offset at 5 widths, `DesktopUiTest` click → offset, button gone.
  Full `:shared` / `:desktop` / `:app` unit run: one failure, `LocationLatencyBudgetTest` (WorkManager
  state under suite load), passes alone — unrelated.
- Pixel 7 Pro: two right taps → home icon shown (thermometer gone); tap → offset 1 → −1, Wed · Today ·
  …, icon gone. 1.27 s, mostly the cold daily load (`preloaded=false`), same render as the arrows.
- Desktop: first build kept zoom-out → user saw 3 history days; then cleared zoom; final keeps zoom and
  shifts the window by it (model test: 5 widths × zoom 0–4). Rebuilt and restarted.
