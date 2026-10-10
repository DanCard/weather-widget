# Source view tracking: daily counts

Status: **implemented** (2026-10-10; approved the same day). Rewritten the same day from per-event rows to
daily counts (user: privacy; every estimator works at day granularity — see
`notes/261010-source-view-probability-brainstorm.md`).

## Request (user, 2026-10-10)

> Create a table to track how often the user toggles API button to view other sources, and which
> sources are viewed. Track for 30 days. The goal of the table is that later on we will use the data
> to determine how likely the user will view other sources. I don't see the point of storing other
> APIs if the user never views them.

This change **collects** the data and **builds the probability method** (`SourceViewProbability`).
Using the probability to decide what to fetch is a later plan.

## What is stored

No timestamps, no switch sequence, no time of day — only per-day totals.

### `source_view_days` — what was viewed

| Column | Type | Meaning |
|---|---|---|
| `date` | INTEGER | the local day as epoch-day ms (`LocalDate.toEpochDay() × 86 400 000`) — the same day key as `api_usage_stats` for non-Google rows |
| `sourceId` | TEXT | `WeatherSource.id` switched to |
| `viewKind` | TEXT | `DAILY` or `HOURLY` (every graph mode — temperature, precip, cloud — counts as `HOURLY`; the Observations screen counts as `HOURLY`) |
| `triggerKind` | TEXT | `TOGGLE` (API button), `HOME` (back-to-preferred button), `OBSERVATIONS` (source cycle in the Observations screen) |
| `wasPrimary` | INTEGER | 1 when `sourceId` was the primary (first usable) source at the switch — so a reorder mid-month doesn't change what an old row meant |
| `switches` | INTEGER | times the user switched to this source — every switch is a view (below) |

Primary key `(date, sourceId, viewKind, triggerKind, wasPrimary)` (`view` and `trigger` are SQL keywords, hence the suffix). Written with the increment-then-insert pattern of
`ApiUsageDao.logCall`.

### `source_view_tracking` — when counting started

One row, `startedDate` (local midnight, epoch ms), inserted when the table is created (Room
`MIGRATION_76_77` and fresh-install `onCreate`; desktop DDL for both paths). Days before it are not
counted, so a new install isn't read as 30 days of "never toggled".

### The denominator is calendar days (user, 2026-10-10)

No "active day" tracking. The question the fetch policy asks is *will anyone view this source before
its next fetch?* A phone in a drawer views nothing, so those days **should** pull the probability
down — there is no reason to fetch sources that aren't going to be viewed. An active-day denominator
would answer a different question ("how likely when the user is engaged") and keep fetching through
a week in a drawer.

Cost: back from a week away, the first toggle to a rarely-viewed source may find it stale and fetch
it on demand (`SourceToggleRefreshPolicy`) — a few seconds once; that view then raises its estimate.

### Every switch is a view (user, 2026-10-10)

No dwell filter. A 3 s (then 750 ms) "stayed on it" rule was considered to discount sources only
passed through on the way round the cycle, and dropped: the user often toggles through sources just
to read one day's rain chance, which takes under a second — the same length as a pass-through, so no
threshold separates them. Counting every switch errs toward *more* views for sources early in the
cycle, i.e. toward fetching, which is the safe direction.

### Retention

30 days for `source_view_days` — `RetentionPolicy.SOURCE_VIEW_DAYS = 30L`, pruned with the others (Android
`WeatherRetentionManager` → `ForecastRepository.maintainSourceViews`, desktop `DesktopWeatherDao.applyRetention`).
The cutoff is day-aligned (`SourceViewTally.retentionCutoffMs` = today − 30 days): `now − 30 d` in ms
would drop the 30th day the estimator still reads.

## Where it is recorded

**`:shared`:** `SourceViewTally` — the `viewKind` bucketing, the day key, `wasPrimary`, the
day-aligned retention cutoff; `SourceViewSql` — the DDL both platforms run. Pure logic,
platform-free; each platform only writes the row (Android `SourceViewRecorder` → `SourceViewDao`; desktop
`DesktopSourceViews` → `DesktopWeatherDao`).

**Android (Room 76 → 77):** two entities (`source_view_days`, `source_view_tracking`) + DAO, `MIGRATION_76_77`, schema export.
- `WidgetIntentActionHandler.toggleApi` → `TOGGLE`
- `WidgetIntentActionHandler.resetSource` → `HOME` (not on the stale-PendingIntent no-op)
- `WeatherObservationsActivity.cycleSource` → `OBSERVATIONS`

**Desktop (schema 29 → 30):** DDL + DAO in `DesktopWeatherDao`.
- The API-button cycle is copied three times (`DesktopWidgetHeader.kt` ×2, `DesktopWidgetPopup.kt`).
  Collapse into one function that cycles **and** records, so a future copy can't skip tracking.
- Home button in `DesktopWidgetHeader` → `HOME`; the Observations window's source cycle, if it has
  one, → `OBSERVATIONS`.

**Not recorded:** Settings reorder/enable changing the displayed source, coverage fallbacks, the
daemon's `source_change` refresh.

Recording is best-effort: a failed write is logged and never blocks the toggle or the paint.

No UI in this change.

## The probability method (built here, used by a later plan)

`:shared` `SourceViewProbability` — pure functions over `source_view_days` rows and the tracking start date; nothing calls it yet
except tests (and a debug log line, below). The later fetch-policy plan only has to call it.

Recency-weighted Beta-Binomial over **calendar days** from yesterday back to 30 days or the tracking
start, whichever is later (today is excluded until it ends):

```
w(age)  = 0.5 ^ (age_days / H)          H = HALF_LIFE_DAYS = 7
n       = Σ w(age)  over counted days
k       = Σ w(age)  over counted days with the event
p       = (k + α) / (n + α + β)         prior α = β = 1  (starts at 50 %: fetch everything until shown otherwise)
```

```kotlin
object SourceViewProbability {
    const val HALF_LIFE_DAYS = 7.0
    const val PRIOR_ALPHA = 1.0
    const val PRIOR_BETA = 1.0
    const val LOOKBACK_DAYS = RetentionPolicy.SOURCE_VIEW_DAYS

    data class Estimate(
        val probability: Double,     // posterior mean p
        val upper90: Double,         // 90 % upper bound — act on this when cutting back, so thin data never cuts off
        val effectiveDays: Double,   // n
        val weightedEvents: Double,  // k
        val countedDays: Int,        // unweighted calendar days counted, for display/logging
    )

    /** Q1: chance the user switches to a non-primary source today (TOGGLE or OBSERVATIONS). */
    fun toggleToday(views: List<ViewDayRow>, trackingSince: LocalDate, today: LocalDate): Estimate

    /** Q2: chance [sourceId] is switched to today; [view] = null → any view. */
    fun sourceViewedToday(sourceId: String, views: List<ViewDayRow>, trackingSince: LocalDate,
                          today: LocalDate, view: ViewBucket? = null): Estimate

    /** Stretch a per-day probability to an arbitrary window, e.g. the source's fetch interval. */
    fun withinHours(pDay: Double, hours: Double): Double = 1 - (1 - pDay).pow(hours / 24)
}
```

Rules:
- **Toggle event** = that day has a `TOGGLE` or `OBSERVATIONS` row with `switches > 0` for a source
  with `wasPrimary = 0`. `HOME` alone is a return, not a toggle.
- **Source event** = `switches > 0` for that source that day (filtered by `view` if given).
- **The current primary** → `probability = 1.0` (it is what is shown).
- Rows dated today or in the future, before `trackingSince`, or older than `LOOKBACK_DAYS` are ignored.
- `upper90` = Beta(k + α, n − k + β) 90th percentile, via bisection on the regularized incomplete
  beta (continued fraction; ~40 lines, no library).

Loader: `SourceViewHistory.load(sinceDate)` per platform (Room DAO / desktop DAO) returns the shared
row types, so both platforms feed the same function.

Observability now: once a day (first retention pass of the day), log `SOURCE_VIEW_PROBABILITY
toggle=0.16 NWS=1.00 OPEN_METEO=0.12 …` to `app_logs` on both platforms, so the numbers can be
watched for a few weeks before anything depends on them.

Worked example — today Saturday, tracking for ≥ 30 days, all sources viewed last Wednesday
(age 3) and nothing else (these become test cases):

| Case | k | n | p |
|---|---|---|---|
| Viewed Wed (3 days ago) | 0.743 | 9.115 | **15.7 %** |
| Same, viewed 20 days ago instead | 0.138 | 9.115 | 10.2 % |
| Viewed Wed + Thu + Fri | 2.469 | 9.115 | 31.2 % |
| Viewed every Wednesday (4×) | 1.393 | 9.115 | 21.5 % |
| New install (tracking 3 days), viewed Wed | 0.743 | 2.469 | 39.0 % |
| Viewed 10 days ago, phone in a drawer since | 0.371 | 9.115 | 12.3 % (active-day denominator would have said 21.7 %) |
| Never viewed in 30 days | 0 | 9.115 | 9.0 % (floor) |
| Tracking started today | 0 | 0 | 50 % (prior) |

Note the **floor**: at H = 7 the weights sum to ~9 effective days, so even a user who never toggles
reads 9 %. The later "stop fetching" threshold must sit below it (or use a longer half-life — 14 d
gives a ~5 % floor). That choice belongs to the later plan; the constant is in one place.

## Tests

| # | Kind | Test |
|---|---|---|
| 1 | Unit (:shared) | `SourceViewTally`: switch → `switches` +1 on the right (date, source, view, trigger, wasPrimary) row |
| 2 | Unit (:shared) | rapid switches (ms apart) each count |
| 3 | Unit (:shared) | day key is the local day of the switch, either side of midnight |
| 4 | Unit (:shared) | `view` bucketing: daily → `DAILY`, every graph mode + Observations → `HOURLY` |
| 5 | Unit (:shared) | `RetentionPolicy.SOURCE_VIEW_DAYS == 30` cutoff |
| 6 | Robolectric | `toggleApi` → `TOGGLE` row; `resetSource` → `HOME`; stale no-op → nothing |
| 7 | Robolectric | Observations source button → `OBSERVATIONS` row |
| 8 | Robolectric | `wasPrimary` set from the primary at switch time; reorder later doesn't change old rows |
| 10 | Robolectric | `WeatherRetentionManager` drops rows > 30 days in both tables |
| 11 | Instrumented (emulator) | `WeatherDatabaseMigrationTest` 76 → 77; tracking row inserted |
| 12 | Unit (desktop) | fresh DB and 29 → 30 upgrade create both tables; tracking row = creation day |
| 13 | Unit (desktop) | the one cycle function: advances + records; single source → no-op, no row |
| 14 | Integration (desktop) | header toggle → rows readable via the DAO; prune > 30 days |
| 15 | Unit (:shared) | `SourceViewProbability`: every worked-example row above, to 0.1 % |
| 16 | Unit (:shared) | toggle event: HOME-only day is not a toggle; switch to primary is not a toggle; OBSERVATIONS is |
| 17 | Unit (:shared) | source event: any switch counts; `view = HOURLY` filter |
| 18 | Unit (:shared) | current primary → 1.0; today/future/>30-day/before-tracking rows ignored |
| 19 | Unit (:shared) | `upper90` ≥ `probability`, shrinks as `n` grows; matches a known Beta quantile (e.g. Beta(1,9) → 0.2257) |
| 20 | Unit (:shared) | `withinHours`: 24 h = p; 0 h = 0; 48 h = 1 − (1 − p)² |
| 21 | Integration (Robolectric) | Room DAO rows → `SourceViewHistory.load` → `SourceViewProbability` gives the same as the pure test |
| 22 | Integration (desktop) | desktop DAO rows → same loader → same numbers as #21 (parity) |

On-device: tap through sources on the Pixel, pull the DB, query both tables; same on desktop
`weather.db`.

## Docs

CLAUDE.md: table list, retention table (30 days), schema line (77 / 30).

## Verification (2026-10-10)

- `:shared` — `SourceViewProbabilityTest` (10), `SourceViewTallyTest` (3), retention cutoff (1), daily
  log (1): every worked-example row reproduced to 0.1 %; `upper90` matches Beta(1,9) → 0.2257 (scipy).
- `:desktop` — `DesktopSourceViewsTest` (10): fresh v30 and a 29 → 30 upgrade, the one cycle function,
  HOME/OBSERVATIONS, no-op when not installed, retention, estimator parity, log once a day.
- Android Robolectric — `SourceViewRecordingRoboTest` (5) plus an Observations-screen test: TOGGLE rows
  with view and primary flag, HOURLY in graph views, HOME with no row for a stale intent, Room → estimator
  parity (same fixture and 0.157 as desktop), day-aligned retention.
- Full suites: `:shared` and `:desktop` all green; `:app` 2448 tests, 1 failure at first —
  `HardcodedUserFacingStringTest` read the DDL as UI prose because it lived under
  `com.weatherwidget.shared.*`. Moved `SourceViewSql` to `com.weatherwidget.data.local` (where SQL
  belongs by that test's rule); green after.
- Emulator — `WeatherDatabaseMigrationTest` 23/23, including the new `migrate76To77_…`.
- Pixel 7 Pro — installed; tapped the API button (Google → Meteo), then home. DB: `user_version` 77,
  `OPEN_METEO|DAILY|TOGGLE|0|1`, `GOOGLE_WEATHER|DAILY|HOME|1|1`, tracking start 2026-10-10, and
  `SOURCE_VIEW_PROBABILITY toggle=0.50 upper90=0.90 days=0 GOOGLE_WEATHER=1.00 …` (first day → prior).
- Desktop — rebuilt and restarted: `weather.db` upgraded to 30, tracking start today, the same
  probability line logged. The click path itself was not exercised on the live desktop (covered by
  `DesktopSourceViewsTest`).

Deviations from the plan text: columns are `viewKind`/`triggerKind` (`view`/`trigger` are SQL
keywords); the store interface became plain DAO calls; the switch is recorded in a `finally` after the
paint, so a failed paint still counts the switch the user made.
