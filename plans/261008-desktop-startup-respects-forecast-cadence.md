# Desktop startup refetches the forecast only when due by the cadence

## Report (user, 2026-10-08)

A side note said each desktop rebuild-and-restart spends ~3 Google requests (1–3 of them
`forecast/hours`) because a launch refetches any forecast older than 30 minutes. User: "It
shouldn't do that. Should respect the stale limit before refresh. Same with android."

## Root cause

**Desktop.** `runLaunchRefresh` asks `launchForecastStaleAfterMs(reason)` how old the forecast may be:

| reason | threshold |
|---|---|
| `resume:*`, `network:*` (wake, link-up) | the cadence (`activeForecastCadenceMs`, 4–6 h for the displayed source) |
| `source_cycle` | 4 h (`SourceToggleRefreshPolicy`) |
| **everything else, including `startup`** | **`FORECAST_FRESHNESS_THRESHOLD_MS` = 15 min** (the note said 30) |

So every launch (autostart at login, `buildStart-desktop.sh`, the time-zone relaunch) refetches
a forecast more than 15 minutes old. Seen today at 06:30 and 09:11. The test
`wake and network restore refetch the forecast only when due by the cadence` pins `startup` to
the 15-minute value.

**Android: already correct, no change.** Startup, install (`PackageReplacedReceiver`), boot and
widget add enqueue an **unforced** sync (`enqueueDelayedStartupSync`, `forceRefresh = false`), and
`FullSyncPipeline` always passes the cadence `ForecastFetchContext`, so
`ForecastFetchCoordinator.isStale` judges it by `ForecastFetchPolicy`. The temperature view's
startup refresh is UI-only (`EXTRA_UI_ONLY`). Evidence: both phones were reinstalled at 06:35
with a 37-minute-old forecast and made no Google request. Forced syncs remain only for explicit
user actions and missing-data repairs.

## Fix

- `launchForecastStaleAfterMs`: `startup` joins the automatic catch-ups and uses the cadence
  (`cadenceMs() ?: Long.MAX_VALUE`). A launch with **no cache** still fetches (`cachePresent`), and
  a forecast older than the cadence still fetches; observations still catch up
  (`LaunchRefreshAction.OBSERVATIONS`).
- Hourly-limiting is unchanged: startup is not hourly-limited, so when the cadence says the
  forecast is due, the whole forecast is fetched.
- Unchanged and deliberate: `source_or_location_change` (a site the user just picked, or a source
  just enabled) keeps the 15-minute user-present threshold. `actuals_provider_change` too (out
  of scope).
- KDoc on `launchForecastStaleAfterMs` / `determineLaunchRefreshAction`; CLAUDE.md Google bullet.

## Tests

- `DesktopApiUsageAndRefreshGateTest`: `startup` → the cadence, and `startup` with the cadence
  suspended → never; a 100-minute-old forecast at startup → `OBSERVATIONS`, a 7-hour-old one →
  `FULL_FORECAST`, no cache → `FULL_FORECAST`. `source_or_location_change` still → 15 min.
- On desktop: restart with `scripts/buildStart-desktop.sh` and confirm
  `LAUNCH_REFRESH_CHECK reason=startup … action=OBSERVATIONS` and no `GOOGLE_REQUEST`.
