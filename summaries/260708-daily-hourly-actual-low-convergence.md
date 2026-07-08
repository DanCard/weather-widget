# Daily-bar vs hourly-graph actual-low convergence (window membership)

**Date:** 2026-07-08

## Symptom
On both Samsung and Pixel, the daily forecast view's **today** column showed an actual low of ~52.6°F
(NWS) while the hourly temperature graph — same source, same day — bottomed at ~54.4°F. The daily
value even drifted *worse* (52.6 → 52.5) as the outlier station kept cooling.

## Root cause
The daily column and the hourly graph both blend station observations through the **same** engine
(`ActualTemperatureSeriesBuilder.blendObservationSeries` via `ActualsAggregator.aggregate`). They
diverged only because each caller passed a **different observation window**, and the blend is
window-sensitive because **station membership is window-sensitive**:

- `LOAC1` (personal, 8.3 km) read 48°F pre-dawn while three nearer stations held ~55°F.
- `KPAO` (official, 6 km) went stale at 20:47 the prior evening.
- A **today-only** window drops the prior-evening coverage, so the lone cold outlier dominates the
  low. The graph's multi-day render window keeps that coverage → outlier diluted.

The device's own `EXTREMA_WINDOW_DIAG` log proved it: same day, isolated window low = 51.5 vs wide
window low = 54.4.

### The critical subtlety
The widget's displayed **today** value does NOT come from the stored `daily_history` table. Today is
computed **live** by `getDailyActualsWithLiveToday` (which deliberately does not merge persisted
daily_history for today). So fixing `daily_history` alone left the display wrong — the live path had
to be widened too. A second live path (`WidgetIntentRouter` SET_VIEW handler) had the same flaw.

## Fix (shipped, verified)
One authoritative public constant, referenced by every caller so the windows can't drift:
`ActualsAggregator.DAILY_BLEND_CONTEXT_MS` = 24h.

| Layer | File / function | Change |
|-------|-----------------|--------|
| Shared engine | `ActualsAggregator.aggregate` / `blendDailyExtremesViaSeries` | Blend each day over `[dayStart-CONTEXT, dayEnd+CONTEXT]` from the full obs list; extract extrema from the target day only. |
| Android — live today **display** | `ObservationRepository.getDailyActualsWithLiveToday` | Fetch obs from `todayStart-CONTEXT`. Merge is primary(past DB)-wins, so the extra prior-day row is harmless. |
| Android — view-toggle display | `WidgetIntentRouter` SET_VIEW handler | Same widen. |
| Android — stored history | `ObservationRepository.recomputeDailyExtremesForDay` | Fetch ±CONTEXT; `.filter { it.date == dateMillis }`. |
| Desktop | `DesktopWeatherRepository.recomputeDailyExtremes` | No change — already passes a whole-history window. |

Same-session unrelated change: Synoptic web fallback breadth `MAX_WEB_FALLBACK_STATIONS` **2 → 3**.

**Live result:** NWS today low 52.6 → **54.4**, high 57.2 → **55.6**, both now matching the hourly
graph. Confirmed by logcat `getDailyActualsWithLiveToday: ... blendedLow=54.38` and by the user on both
devices.

## Why it kept recurring, and how it's guarded now
Every prior fix converged one call site but never made the invariant **caller-independent**; each new
feature that re-fetched a day-isolated window reopened it. The durable invariant:

> For a fixed observation set, a day's daily-aggregate extreme == that day's wide-window blend ==
> the hourly graph's per-day extreme — regardless of how narrow the caller's query window is.

### Automated tests added
- `shared/src/test/.../actuals/ActualsWindowIndependenceTest.kt` — pure-JUnit, both platforms; asserts
  `aggregate` == wide blend == graph build, and differs from a day-isolated blend.
- `app/src/test/.../widget/DailyLiveTodayWindowConsistencyTest.kt` — Room + repository; drives the
  actual **display path** and asserts today's low blends across midnight, not today-only. This is the
  test that would have caught the field bug.
- Plan: `notes/260708-daily-hourly-actual-extrema-convergence-testplan.md`.

Regression sweep (`ObservationResolverTest`, `ObservationRepositoryDailyMergeTest`,
`YesterdayActualHighConsistencyTest`, shared actuals) — all green.

**Rule for the next recurrence:** any new code path that feeds `aggregate` /
`aggregateObservationsToDailyBySource` from a DB fetch MUST fetch
`queryStart - ActualsAggregator.DAILY_BLEND_CONTEXT_MS`.

## Notes
- The `EXTREMA_WINDOW_DIAG` diagnostic log was left in place — post-fix, isolated vs wide should track
  closely, so it stays useful as an ongoing sanity check.
- All changes are in the working tree; nothing committed.
