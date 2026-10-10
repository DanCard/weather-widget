# Source view tracking — summary (2026-10-10)

Plan: `plans/261010-source-view-tracking-table.md`. Brainstorm: `notes/261010-source-view-probability-brainstorm.md`.

## What was decided (user, in order)

- Track source toggles for 30 days, to later decide whether non-primary sources are worth storing.
- **Daily counts, not events** — privacy; every estimator works at day granularity.
- Home button and Observations-screen cycling count too.
- Probability: recency-weighted (7-day half-life), and **built now** for a later step.
- **Calendar-day denominator**, no "active day" tracking — a phone in a drawer views nothing, so
  those days should lower the probability.
- **No dwell filter** — a rain-chance glance takes < 1 s, the same as passing through.

## What changed

- `:shared` `com.weatherwidget.shared.sourceview`: `SourceViewTally` (day key, view bucketing,
  `wasPrimary`, day-aligned retention cutoff), `SourceViewProbability` (Beta-Binomial, `upper90`,
  `withinHours`, daily summary line), `BetaQuantile`. `com.weatherwidget.data.local.SourceViewSql`
  (DDL for both platforms). `RetentionPolicy.SOURCE_VIEW_DAYS = 30`.
- Android: Room 77 (`SourceViewDayEntity`, `SourceViewTrackingEntity`, `SourceViewDao`,
  `MIGRATION_76_77`, tracking row on every open). `SourceViewRecorder` called from `toggleApi`,
  `resetSource` (in `finally`, after the paint) and `WeatherObservationsActivity.cycleSource`. Retention
  + daily `SOURCE_VIEW_PROBABILITY` log in `ForecastRepository.maintainSourceViews`.
- Desktop: schema 30; `DesktopSourceViews` (recorder + daily log, off the UI thread); the three copied
  API-button cycles collapsed into `cycleDisplaySource`; home button and Observations window record.
- CLAUDE.md: schema line, table list, retention table.

## Verification

All new tests pass (15 shared, 10 desktop, 6 Robolectric, 1 instrumented); full `:shared`, `:desktop`
and `:app` suites green after moving the DDL out of the UI-prose package. Live on the Pixel (v77,
TOGGLE + HOME rows, probability log) and on desktop (v30, tracking row, probability log).

## Insights

- Retention cut at `now − 30 d` would have deleted the 30th day the estimator reads; the cutoff is
  day-aligned in one shared function.
- Recording sits in `finally` + `NonCancellable`: the widget handler swallows render failures, so a
  call placed after the paint would silently skip counting exactly the taps that went wrong.
- With a 7-day half-life, 30 days weigh as ~9; a never-toggling user reads ~9 %, not 0. Any
  "stop fetching" threshold in the follow-up has to sit below that floor.
