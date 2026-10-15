# Two-second refresh toast on every surface: what was queued, or why nothing was

User, 2026-10-15:
1. "When a data refresh is queued … posting a toast message for a couple of seconds saying so?"
   → "Yes 2 seconds, on all surfaces."
2. "Can we have a different message for forecast refresh versus actuals refresh? If no refresh, can
   we put up a message about that too?"

## Scope

- **User-initiated actions only.** These are taps and clicks on the widget, the Android app screens,
  and the desktop popup and windows. Background, cadence, screen-on and gap-fill fetches get no toast.
  On Android those would show over whatever app is in front.
- **Each user action gets exactly one toast.** It says what was queued (forecast, observations, or
  both) or why nothing was queued. A widget source toggle runs two checks, `requestIfStale` and
  `toggle_api_stale`; their results are merged into one notice.
- **Zoom cycling never shows a "nothing refreshed" toast.** It still shows "… refresh queued" when it
  does queue a fetch. Zoom checks staleness as a side effect, and "up to date" on every zoom tap would
  be noise. The "nothing refreshed" toast is for actions whose purpose is fresh data: Refresh buttons,
  "Tap to refresh", the source toggle and the home button.
- **Duration is 2 s.** On Android `Toast.LENGTH_SHORT` is exactly 2000 ms (AOSP `SHORT_DELAY`).
  Desktop matches it with `:shared` `RefreshNotice.DURATION_MS = 2_000`.
- **Burst guard:** at most one toast per 2 s. A suppressed toast is still logged.
- **Logging:** every toast writes `REFRESH_TOAST origin=… kind=… shown|suppressed` to `app_logs` on
  both platforms (memory `toast_paths_need_db_rows`).

## Messages

"Observations" is the word the app's UI already uses for actuals (the Observations screen).

**Queued:**

| Kind | Text | When |
|---|---|---|
| FORECAST | Forecast refresh queued | a full sync (it also backfills that source's actuals, but the forecast is what was asked for) |
| OBSERVATIONS | Observations refresh queued | current temperature / recent observations only |
| BOTH | Forecast and observations refresh queued | a full sync and a separate current-temp/observations update were both queued |

**Nothing queued:**

| Reason | Text | Source of truth |
|---|---|---|
| UP_TO_DATE | {Source} forecast up to date · {age} ago (or "{Source} observations up to date · {age} ago") | the same freshness the decision used; age formatted by the existing staleness age-label helper |
| ALREADY_QUEUED | Refresh already queued | `refreshIfStale` skip `debounced`; WorkManager KEEP found the same unique work pending |
| IN_PROGRESS | Refresh already in progress | desktop `refreshInFlight`; Android unique sync work already RUNNING |
| QUOTA | {Source} limit reached · next refresh after {time} | `SourceQuotaBlocks.fullyBlockedUntil` |
| NO_LOCATION | the existing `obs_no_location_to_refresh` text | `ActiveLocationResolver` null |

**Where the text lives:** the outcome is a `:shared` sealed type,
`RefreshNotice.Queued(kind) | NotQueued(reason, source, ageMs?, untilMs?)`. Android maps it to string
resources; desktop builds English text from it in `:shared`. That adds about 7 new Android strings,
each translated into all 19 locales (locale parity test; escape apostrophes). The existing
`refresh_now_enqueued_toast` is replaced by the BOTH string on Android.

## Triggers

### Android

| # | Trigger | Kind when queued | Can show "nothing refreshed"? | Change |
|---|---|---|---|---|
| A1 | Settings → Refresh data | BOTH | NO_LOCATION only (it is forced) | replaces the generic toast |
| A2 | Widget source toggle | FORECAST (`toggle_api_stale`), BOTH if `requestIfStale` also queued | yes: UP_TO_DATE / QUOTA / ALREADY_QUEUED | new; merges both checks |
| A3 | Widget home button → source reset | as A2 (`reset_source_stale`) | yes | new |
| A4 | Widget zoom cycle (`cycle_zoom` → `requestIfStale`) | BOTH (forced sync plus current-temp update) | **no** (see Scope) | new; `refreshIfStale` returns an outcome instead of `Unit` |
| A5 | Widget "Tap to refresh" (`ACTION_REFRESH`, not `uiOnly`) | FORECAST (`refresh_action_stale`) | yes: UP_TO_DATE | new; `uiOnly` repaints sent by app screens stay silent |
| A6 | Settings: enabling a source that becomes primary (`SourceSwitchFetch`) | FORECAST | yes: UP_TO_DATE when it has a fresh cache | new |
| A7 | Settings: changing the actuals provider | FORECAST (a forced full sync; it re-reads that source's actuals) | no | new |
| A8 | Observations → Refresh | OBSERVATIONS | NO_LOCATION (existing), IN_PROGRESS | add a start toast; the existing "Refreshed {source}" still shows when it finishes |
| A9 | Forecast History → Refresh | FORECAST | IN_PROGRESS | add a start toast; the existing finish toast is unchanged |

The App Logs → refresh button keeps its own debug toast.

**Left out on purpose, since each has its own message:**
- The day tap or pan that loads an hourly day ("Fetching hourly forecast for {day}…")
- A location change in the setup screen ("Getting weather for {place}…")
- All automatic fetches

### Desktop

| # | Trigger | Kind | Can show "nothing refreshed"? | Shown in |
|---|---|---|---|---|
| D1 | Settings → Refresh | FORECAST (`repo.refresh` fetches forecast and its observations) | IN_PROGRESS, NO_LOCATION (repository null) | a 2 s pill in the Settings window (not its Snackbar: `SnackbarDuration.Short` is 4 s) |
| D2 | History → Refresh | FORECAST | IN_PROGRESS | a pill in the History window |
| D3 | Observations → Refresh | OBSERVATIONS | IN_PROGRESS | a pill in the Observations window |
| D4 | Popup source cycle | FORECAST if the source is > 4 h old or has no cache | UP_TO_DATE | the popup's `transientMessage` slot, below the location banner and the no-hourly message in priority |

**D4:** the fetch decision is made in the daemon, so the popup must make the same decision. Move it
into one pure function, `sourceCycleFetches(cachePresent, lastForecastFetchMs, nowMs)`, built on
`SourceToggleRefreshPolicy`. The daemon and the popup both call it, so they cannot disagree.

## Implementation

- **`:shared` `RefreshNotice`**: the outcome types above, `merge(a, b)` (a Queued beats a NotQueued;
  FORECAST + OBSERVATIONS = BOTH), `DURATION_MS`, the gate `shouldShow(lastShownMs, nowMs)`, and
  `desktopText(notice, ageFormatter)`.
- **Android**:
  - `RefreshScheduler.refreshIfStale` returns a `RefreshNotice`. Its skip reasons map to
    UP_TO_DATE / ALREADY_QUEUED / QUOTA.
  - `InteractionRefreshRequester` passes outcomes up, and `WidgetIntentActionHandler` merges them per
    interaction.
  - `RefreshToast.show(context, origin, notice)` posts to the main looper, applies the gate, maps the
    notice to a string resource, shows `LENGTH_SHORT`, and logs the row.
- **Desktop**: `RefreshNoticeState` (text + request counter; a `LaunchedEffect` clears it after
  `DURATION_MS`), a `RefreshNoticePill` composable in the three windows, and the popup reading it
  through `transientMessage`. The `request*Refresh` functions return their `RefreshNotice`, including
  for the in-flight case they suppress today.

## Tests

| # | Test | Kind |
|---|---|---|
| 1 | `RefreshNotice.merge`: FORECAST + OBSERVATIONS = BOTH; Queued beats NotQueued; two NotQueued keep the more specific reason (QUOTA > ALREADY_QUEUED > UP_TO_DATE) | unit (`:shared`) |
| 2 | `shouldShow`: first ever, 1999 ms, 2000 ms, clock moved backwards | unit (`:shared`) |
| 3 | `desktopText` for every kind and reason, with the age and the quota time formatted | unit (`:shared`) |
| 4 | `refreshIfStale` returns UP_TO_DATE (fresh), ALREADY_QUEUED (debounced), QUOTA (blocked), Queued(BOTH) (enqueued) | Robolectric |
| 5 | `RefreshToast.show`: `ShadowToast` shows the right string for each notice, `LENGTH_SHORT`; a `REFRESH_TOAST` row is written | Robolectric |
| 6 | Two notices 500 ms apart: one toast, plus one `suppressed` row | Robolectric |
| 7 | Widget toggle onto a stale source: one toast, "Forecast and observations refresh queued" (both checks fired), not two | integration (handler + requester + toast) |
| 8 | Widget toggle onto a fresh source: "{Source} forecast up to date · {age} ago" | integration |
| 9 | Widget toggle onto a quota-blocked source: the QUOTA text with its time | integration |
| 10 | Zoom cycle on fresh data: no toast; on stale data: the BOTH toast | integration |
| 11 | "Tap to refresh": stale → FORECAST; fresh → UP_TO_DATE; `uiOnly` → nothing | integration |
| 12 | Settings Refresh data → BOTH; actuals-provider change → FORECAST; source becomes primary, fresh → UP_TO_DATE | Robolectric |
| 13 | Observations Refresh → "Observations refresh queued" at the start (fetch held open), "Refreshed {source}" at the end | Robolectric |
| 14 | Automatic `enqueueForcedRefresh` (`viewing_forecast_due`) shows no toast | integration (guard) |
| 15 | `RefreshNoticeState` clears at exactly 2 s of virtual time; a new notice restarts the timer | desktop unit |
| 16 | `sourceCycleFetches`: stale or no cache → true, fresh → false; agrees with the daemon's `launchForecastStaleAfterMs` | desktop unit |
| 17 | Settings / History / Observations Refresh show the right pill; a second click while one is in flight shows "Refresh already in progress" | desktop Compose UI test |
| 18 | Locale parity: every new string exists in all 19 locales | existing unit test |
| 19 | Manual, Pixel: toggle onto a stale and onto a fresh source; screenshot each toast; `REFRESH_TOAST` rows in the pulled DB | on-device |
| 20 | Manual, desktop: Observations Refresh, then click again while it runs; both pills appear, about 2 s each | on-device |

## Changes made during implementation

- **Widget taps show the notice in the widget's banner, never a Toast** (user, 2026-10-15, after the
  device check). Android drops a background app's Toast when the app has no notification permission:
  `NotificationService: Suppressing toast from package com.weatherwidget by user request` on the
  emulator, which matches the Fold 4. Only the Pixel 7 Pro has POST_NOTIFICATIONS granted. The banner
  is `FetchBanner` for 2 s. It never covers a different message already up (`suppressed busy`), and
  `FetchBanner.expire` hides it at 2 s. Expiry alone left it on screen, because banner visibility
  stays set across repaints. App screens keep real toasts; desktop keeps its pill.
- **Source toggles drop "up to date"** (user, 2026-10-15: "Why getting a message on each api
  change?"). Switching sources is browsing; most switches land on a fresh source. A queued refresh,
  QUOTA and ALREADY_QUEUED / IN_PROGRESS still show. This covers the widget toggle and the desktop
  popup and Observations source cycles (`RefreshNotice.isUpToDate`). The home button's source reset
  is unchanged. Emulator: a toggle onto fresh Google logs `skip=fresh_data` and shows nothing.
- **Browsing taps include arrows and view switches, not only zoom.** All of them run the same stale
  check; each announces only a refresh it queued.
- **A forced refresh absorbed by a pending sync is reported, not claimed.** Under KEEP, a sync already
  waiting or running absorbs the request: ALREADY_QUEUED / IN_PROGRESS
  (`WidgetWorkScheduler.pendingOneTimeSync`).
- **A desktop source cycle can queue observations only.** The daemon's launch action has three
  outcomes (FULL_FORECAST / OBSERVATIONS / NONE), and the toast names each. The Observations window's
  source cycle announces too, in its own window.
- **Not automated:** the History screen's start toast. It sits beside `HISTORY_REFRESH_TAP
  outcome=fetch_started`; no test harness injects History's repository.

## Verification

- Unit/integration: `:shared` 2213, `:desktop` 598 (1 skipped, pre-existing), `:app` 2523, all passing
  before the banner change. After it: `WidgetRefreshToastIntegrationRoboTest` 9,
  `RefreshToastRoboTest` 9, `SourceSwitchFetchIntegrationTest` 4, all passing.
- Emulator (API 36, no notification permission), daily view:
  - Before the banner change: toggle → `REFRESH_TOAST … shown`, but the system suppressed the
    Toast. With the permission granted: "Silur forecast up to date · 1m ago" and
    "NWS forecast up to date · 1m ago" toasts on screen. Permission revoked afterwards.
  - After: toggle → banner "Google forecast up to date · 3m ago" (`shown banner=1`). The first build
    left it up past 4 s (the expiry race above). The fixed build shows "Meteo forecast up to date ·
    4m ago" at 0.7 s and has cleared it by 3.2 s.
