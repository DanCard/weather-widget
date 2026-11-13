# Refresh failure: say why, retry, and say when

## Evidence (2026-11-12, ~21:05)

- Open-Meteo stalled; both devices hit its 30 s request timeout.
- Desktop: a source-switch refresh of Open-Meteo started 21:05:30, timed out 21:06:04
  (`CURRENT_TEMP_STATUS source=OPEN_METEO ok=false class=HttpRequestTimeoutException`). By then the
  user had cycled to NWS, and the popup said **"Couldn't refresh NWS"**. NWS was fine
  (`LAUNCH_REFRESH_CHECK source=NWS action=NONE`, 2 min old).
- Fold 4: `FETCH_METEO_FAIL … request_timeout=30000 ms` at 21:04:06 and 21:05:30;
  `source_fail_count_OPEN_METEO=4`, `source_fail_code_OPEN_METEO=TIMEOUT`, so the graph pill read
  "⚠ OPEN-METEO UPDATES FAILING / Timed Out · 9:05 PM".
- Nothing retried: the Fold's next plan was `FORECAST_DUE_WAKE sleepMin=261 next=OPEN_METEO`. The
  tap-stale threshold (2 h) made the fetch happen; the background cadence (8–10 h) does not
  schedule it again. Desktop's 15 min `FAILURE_RETRY_MS` floor only applies to sources its loop
  considers due, which a tap-stale source is not.

## Root causes

1. `DesktopUiApplication.announceSwitchRefresh` names the source displayed *when the fetch ends*,
   not the source that was fetched. The socket carries one byte with no source.
2. `RefreshOutcome.Failed` has no fields, so neither platform can say why.
3. Neither platform retries a failed fetch of the displayed source on a short schedule, so
   there is no honest "retrying at" time to show.

## Fix

1. **`:shared` `SourceRetry`** (policy + runner over a `Host`):
   - Transient codes only: `TIMEOUT`, `DNS_ERROR`, `CONN_REFUSED`, `SOCKET_ERROR`, `SSL_ERROR`,
     `HTTP_5xx`. Quota, 4xx, rate limits, `NO_COVERAGE` and `DATA_RESTRICTED` are not retried here
     (they have their own resets, or waiting won't fix them).
   - Only for a displayed source. Delay 15 min, doubling per consecutive retry, max 60 min; the chain
     resets on any success.
   - Logs `SOURCE_RETRY scheduled|skip`. The host persists the pending retry (Android prefs, desktop
     `app_logs` contract row, the cross-process pattern of `ProductQuotaLog`).
2. **Retry execution**:
   - Android: a delayed forced sync targeted at the source, under its own unique name per source
     (`delayedPolicy`: REPLACE only when not running; AGENTS rule 6). The reason is silent
     (not owed a notice).
   - Desktop: the forecast loop treats a due retry of the displayed source as due (full plan),
     and caps its sleep at the retry time; a launch-refresh failure wakes the loop to re-plan.
3. **`RefreshOutcome.Failed(errorCode, retryAtMs)`**; text `"Meteo: Timed Out · retrying 9:20 PM"`,
   falling back to `"Couldn't refresh Meteo"` when the code is unknown. Android uses the existing
   localized `watermark_*` code strings plus one new `refresh_retrying_at` string (19 locales).
4. **Desktop source-switch notice** reads the fetched source from a `SWITCH_REFRESH_RESULT` row the
   daemon writes before pushing the byte, so it names Open-Meteo, not NWS.
5. **Failure pill** detail shows `"Timed Out · retrying 9:20 PM"` while a retry is pending, instead of
   the failure time. The 3-failure threshold (Android) and hourly-only rule (desktop) stay, so a
   single background timeout stays silent.

## Tests

- `SourceRetryTest` (shared): transient classification, backoff chain, skip when not displayed,
  pending retry ignored once past.
- `RefreshOutcomeTest`: failed text with code, with retry, with neither.
- `FailureBannerLayout` detail with a retry time.
- Desktop: switch-result row parse; Android: `refreshOutcomeText` failed-with-reason.
- Live: kill network to a source (or point at a blackhole) on desktop and the emulator, check
  the notice text, the `SOURCE_RETRY` row, and that the retry fetch runs at the stated time.

## Addendum (user, 2026-11-12): cap and battery

- Retries after 1, 2, 5 and 10 min (user's schedule), 4 per failure streak; a failure more than 1 h
  after the last retry starts a fresh streak.
- On battery with the screen off a retry is neither armed (`skip reason=battery_screen_off`) nor run
  (`fire_skip`, Android in `FullSyncPipeline`, desktop in the forecast loop); a skipped retry ends its chain.
