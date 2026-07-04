# Forecast Coverage Check — Deep Dive

*2026-07-03. Investigation triggered by a user-noticed widget redraw on the Samsung
SM-F936U1. Diagnosis first, then anatomy of the coverage machinery, then the agreed
redesign direction.*

## The symptom and diagnosis

The widget visibly redrew at ~20:23. Not an ANR, not a crash (`dumpsys activity
exit-info` clean; no ANR traces in logcat). It was a **forced "coverage gap" network
fetch** — 23.7s for the weather call alone (`SYNC_PERF weather=23712ms`) — followed by
the worker's **unconditional repaint of all widgets** at the end of `doWork` (there is
no "did anything change?" check anywhere in the paint path).

Worse: it's a loop. Device logs show coverage-gap enqueues at 19:52 and 20:22 —
exactly the 30-minute debounce apart — and the gap line reappeared *after* the big
fetch (20:23:39, 20:25:11):

```
DailyViewHandler: coverage gap: widget=352 source=NWS realMax=2026-07-10 visibleEnd=2026-07-11 requesting=16
```

Widget 352's visible right edge is today+8. NWS's API only serves ~7 days of
forecast. The gap can **never** be filled, so a forced full network fetch + all-widget
repaint fires every 30 minutes, indefinitely. The debounce bounds the frequency but
not the futility — the checker fires on a *condition* (gap exists) rather than an
*expectation* (a fetch could change this), with no feedback that its last attempt
didn't help.

## Anatomy: two triggers, one shared decision

Both born the same day (2026-06-20), ~30 minutes apart.

**Shared decision** — `ForecastHorizon.extensionTarget()`
(`shared/src/main/kotlin/com/weatherwidget/shared/config/ForecastHorizon.kt`).
Given today, the rightmost visible day, and how far real (non-climate-normal)
coverage reaches, returns `MAX_DAYS` (16) when the edge exceeds coverage, else null.
Shared with desktop via `ForecastHorizonContract` cases. Has **no concept of
per-source horizon limits** — it assumes any gap is fillable by fetching harder.

Context constants: `BASELINE_DAYS = 8` (routine fetch request; raised from 7 because
a 7-day window dropped the day exactly one week out), `MAX_DAYS = 16` (Open-Meteo's
`forecast_days` ceiling; 17 is rejected).

**Trigger 1 — navigation-time** (`WidgetIntentRouter.kt:238-260`, commit `159151a8`).
User taps right arrow past coverage → one-time forced 16-day fetch
(`reason=nav_extend_forecast`). Hardcoded-gated to Open-Meteo: *"Gated on Open-Meteo
specifically (the only source that can extend; NWS et al. cap near a week of their
own accord)."* Desktop counterpart in `Main.kt` (`ensureForecastDays`).

> **Design verdict (user):** gating a generic mechanism on one named provider is
> wrong. If the trigger exists at all it should operate on the *current displayed
> source*, not a hardcoded provider. The per-provider knowledge lives only in a
> comment at one call site — invisible to the render-time trigger that needed it.

**Trigger 2 — render-time** (`DailyViewHandler.kt:297-309` +
`RefreshScheduler.enqueueForecastCoverageRefresh`, commit `9366be35`). Runs on
*every daily render*; if the rightmost visible day lacks real forecast for the
**displayed source** (any source — this is where Trigger 1's gate was lost), it
force-fetches, debounced 30 min per source. Original motivation was transitional:
caches fetched under the old 7-day baseline looked "fresh" to the time-based
staleness check while being one day short, leaving the edge a climate-filler bar
forever. It acquired a permanent second job: wide widgets whose edge (today+8) sits
past the 8-day baseline with no navigation at all — coverage from an earlier 16-day
fetch decays one day per day until this check re-extends it (~weekly for Open-Meteo).

## Is it needed?

- **Trigger 1 (nav):** needed *under the current 8-day-baseline scheme* — without it,
  navigating past +7 shows filler for days Open-Meteo could serve. But its
  provider gate is wrong (see above), and under the redesign it becomes moot.
- **Trigger 2 (render):** its transitional job is over; its wide-widget job is real
  but only exists because routine fetches under-request. Design smells: launches
  network fetches from a render path; poll-shaped (fires on condition, no feedback);
  fixed debounce is the only loop protection.

## Options considered

1. **Per-source `maxForecastDays` metadata** on `WeatherSource` (pattern precedent:
   `providesHistoricalActuals`). **Rejected by user:** provider capabilities drift
   over time; a hardcoded capability table silently becomes wrong and then
   *misbehaves* (vs. merely under-performing).
2. **Observed-coverage feedback:** after a coverage fetch, if coverage didn't extend,
   suppress that source until tomorrow. Adaptive, no tables — but keeps all trigger
   machinery alive, adds state, still pays one futile fetch/source/day.
3. **Always request the max** — chosen, below.

## Agreed direction: always request MAX, delete the coverage-chasing machinery

> **Status: IMPLEMENTED 2026-07-03** (same day). Both triggers and the whole
> `forecastDays`/`KEY_FORECAST_DAYS` threading deleted; `ForecastHorizon` reduced to
> the single `MAX_DAYS` constant. Verified live on the Samsung: widget 352's DAILY
> renders emit zero coverage-gap lines.

Routine fetches always request `forecast_days=16` (Open-Meteo honors it; NWS has no
days parameter and returns its ~7 days regardless; other adapters likewise return
whatever their API/plan gives). `forecastDays` already threads through
`WeatherWidgetWorker → ForecastRepository → OpenMeteoApi`, so this is close to a
constants change.

Consequences:

- Stored coverage per source is always the deepest that source can currently
  provide, refreshed every routine fetch (60–480 min). Coverage never decays below
  achievable. Provider horizons **reveal themselves in behavior** — if NWS extends
  to 10 days someday, the app benefits on the next fetch, zero code changes.
- Any remaining gap at a widget edge is by definition unfillable right now →
  climate-filler (`GENERIC_GAP`) is the *correct rendering*, not a defect to chase.
- **Delete Trigger 2's forced fetch** (the 30-min redraw loop dies; render paths stop
  launching network work).
- **Delete Trigger 1** (nav can't outrun coverage that's already at max), including
  its wrong Open-Meteo gate and the desktop counterpart.
- Net: less code, no capability tables, no learned state; the futile-loop bug class
  becomes unrepresentable.

Cost: identical request count; Open-Meteo response grows ~8 extra days of daily
aggregates (couple of KB; the hourly fetch already pulls a 10-day range every cycle).
DB growth trivial, bounded by existing 1-month retention.

Residual constant: `MAX_DAYS=16` survives only as *request formation* in the
Open-Meteo adapter (it rejects 17) — like a base URL, not a capability assumption.
Drift degrades gracefully: if Open-Meteo raises its max we under-ask until bumped;
if a provider returns fewer days than requested we store what came back and filler
covers the rest. No loop, no misbehavior.

## Deferred / related

- **Fix B (deferred):** skip `updateAppWidget()` when the displayed content is
  unchanged (fingerprint rendered bitmap + final text strings — must be on
  *displayed* rounded values, since interpolated current temp drifts continuously).
  General anti-flicker; revisit if no-op redraws still bother after the above.
- The paint path repaints all widgets unconditionally after every worker run — any
  future "avoid redraw" work lands there.
- Memory entries: `nws_unfillable_coverage_gap_loop`,
  `feedback_no_hardcoded_provider_limits`.
