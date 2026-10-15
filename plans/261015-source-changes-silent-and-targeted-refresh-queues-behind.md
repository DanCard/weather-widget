# Source changes show no refresh notice; a targeted refresh queues behind another source's sync

User, 2026-10-15, after `plans/261015-refresh-queued-toast-all-surfaces.md`:
- Samsung: "As I was toggling sources … I got a message that refresh queued. I wasn't expecting that."
- Pixel: "a message … about NWS being up to date when changing sources."
- "I never requested a source toggle gives a message." → "fix all"

## 1. Source changes are silent

The earlier plan counted the source toggle as an action "whose purpose is fresh data". That was my
call, not the user's. People switch sources to look at them. A refresh the switch triggers is a side
effect, like the ones arrows and zoom trigger. The follow-up ("drop up to date on toggles") still
let "refresh queued" through. It also missed the daily view's **home button**, which changes the
source too (Pixel 12:34:29: `origin=widget_reset_source none=UP_TO_DATE source=NWS … shown`).

Now no source change shows a notice, whatever it queued:
- Android: `toggle_api` and `reset_source` call `RefreshToast.logSilent`, which writes only the
  `REFRESH_TOAST origin=… silent` row.
- Desktop: the popup / Observations source cycle shows nothing. `sourceCycleNotice` and
  `sourceCycleNoticeSurface` existed only to build that notice, so they are deleted.
- The refresh itself is unchanged (`SourceToggleRefreshPolicy`, > 4 h).

## 2. A targeted forced refresh is no longer dropped by KEEP

Samsung, 12:33: a toggle to Open-Meteo (359 min old) started a forced sync targeted at Open-Meteo.
3 s later a home tap to NWS (375 min old) asked for its own forced sync with KEEP. KEEP found the
Open-Meteo sync pending and joined it, so `FETCH_PLAN parts=OPEN_METEO:DAILY` ran and NWS was never
fetched (forecast still from 06:17).

Fix (`WidgetWorkScheduler`):
- Every forced full sync is tagged `forced_target:<id|all>`.
- `enqueueForcedSync` with KEEP and a target: if a one-time sync is still pending and none of the
  pending ones is forced for this source or for all of them (`needsForcedFollowUp`), the request is
  enqueued with `APPEND_OR_REPLACE`. That chains it behind the pending sync and never cancels a
  running one. Returns `ForcedSyncEnqueue(request, followUp)`.
- An untargeted request keeps plain KEEP. A follow-up would refetch every source, including billed
  ones, which is out of scope.
- `RefreshScheduler.enqueueForcedRefresh` reports a follow-up as `Queued(FORECAST)`, not "already
  queued".

## Tests

- `WidgetWorkSchedulerCollisionTest`: a forced sync for another source queues behind the pending
  one; the same source again and untargeted requests join the pending work. `needsForcedFollowUp`
  truth table: all covers any source; an unforced sync covers nothing.
- `WidgetRefreshToastIntegrationRoboTest`: toggles onto a stale, a fresh and a quota-blocked source
  show no banner and write a `silent` row. A home tap onto a fresh primary source shows nothing. A
  home tap while another source's sync is pending leaves two pending syncs (Open-Meteo, NWS).
  Banner tests moved to zoom.
- `RefreshToastRoboTest`: a sync forced for the same source absorbs the forecast (OBSERVATIONS); a
  waiting unforced sync does not (BOTH).
- `RefreshNoticeStateTest`: source-cycle cases removed.
