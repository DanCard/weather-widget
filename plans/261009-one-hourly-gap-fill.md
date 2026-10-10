# One hourly gap-fill

User, 2026-10-09: "two gap-fills? Shouldn't there only be one?" → "implement one gap fill".

## Problem

Android had two mechanisms for an hourly view with missing hours:

1. **Paint-time gap-fill** (`reason=hourly_gaps`), copied three times — `TemperatureGraphHoursLoader`,
   `PrecipViewHandler`, `CloudCoverViewHandler`. Any missing hour in the window → a **forced full
   sync** of every due source (`enqueueRedundantImmediateSync`), 15-min cooldown. It cannot tell a
   stale source from one whose data simply ends (NWS at 156 h), and only the temperature copy skipped
   elapsed hours, so precip/cloud refetched them every 15 min.
2. **On-demand** (`HourlyOnDemand`, `HourlyOnDemandWorker`): that source alone, only as deep as
   needed, ~3 s.

Panning into an empty future day fired both: the fast fetch under the banner and a slow forced sync
(~42 s, 3+ Google requests) that nobody needed.

## Change

The paint-time trigger stays (it is what retries after a failed fetch, with no tap or pan), but it
asks the one rule instead of forcing a sync:

- `WidgetDayClickCoordinator.fillHourlyGaps(context, widgetId, reason)` — same window and
  `HourlyOnDemand.panAction` as `afterHourlyNavigate`. Only `Fetch` acts: enqueue
  `HourlyOnDemandWorker` quietly. `NoDataMessage`/`Nothing` → log only (a paint never raises a
  banner; panning does).
- 15-min cooldown per (widget, source) kept (`hourly_gaps`).
- Quiet work: `ExistingWorkPolicy.KEEP` (never replaces a pan's/tap's announced fetch, which is
  REPLACE), and `completeOnDemand(announce = false)` neither clears another banner nor shows
  "no hourly … data ends".
- The three handlers keep their `*_GAPS` diagnostics; `TEMP_GAPS_ELAPSED_ONLY` stays.

Desktop has no paint-time gap-fill; it already uses only the shared rule.

## Tests

- Robolectric: a paint-time gap for a future uncovered day enqueues `hourly_on_demand_<id>` (not
  the full sync), shows no banner; second call inside the cooldown enqueues nothing; a pending
  announced pan is kept (KEEP).
- Worker request carries `announce=false`.
