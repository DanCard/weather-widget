# Source view probability — brainstorm (2026-10-10)

Two questions, for `plans/261010-source-view-tracking-table.md` (counts vs event rows):

- **Q1.** P(user uses the API toggle on a given day)
- **Q2.** P(source X is viewed in the next 24 h)

## Two things every method needs

1. **Exposure, not calendar days.** A day the user never looked at the widget or app is no evidence
   of "didn't toggle". The denominator must be *active days* (the widget was painted with the screen
   on, the app opened, or the desktop popup shown). Neither table design records this yet, and every
   method below needs it.
2. **A real view vs passing through.** The toggle cycles in order, so getting to source 4 shows
   sources 2 and 3 for a second each. Counting those overstates them. Tell: dwell time. If the next
   switch comes < ~3 s later, the source was passed through, not viewed. A source shown when the
   screen goes off, or held for ≥ 3 s, was viewed.

## Q1 — P(toggle today)

| # | Method | Estimate | Strength | Weakness |
|---|---|---|---|---|
| 1 | Plain frequency | k / n active days with ≥ 1 toggle | Simple | Noisy early on; 0/3 reads as "never" |
| 2 | Beta-Binomial | (k + α) / (n + α + β) | Handles new installs; the prior encodes the default | Prior has to be chosen |
| 3 | Recency-weighted (2) | weight each day by 0.5^(age / 7 d) | Follows behavior changes (found the toggle, stopped using it) | Needs a half-life |
| 4 | Two-state Markov | P(toggle \| toggled yesterday), P(toggle \| didn't) | Toggling comes in streaks (weather events last days) | Halves the data per estimate |
| 5 | Weather-conditional | P(toggle \| rain likely / sources disagree / extreme temp) | Probably the strongest signal: people compare sources when the weather matters | Needs a daily context record |
| 6 | Weekday vs weekend | shrink each bucket toward the overall rate | Cheap | 30 days ≈ 4 Sundays: weak |

**Leaning:** start with #3 (Beta-Binomial over active days, half-life ~7 d). Add #5 later if the data
shows weather drives toggling. The context can be kept per day (max rain %, high-temp spread across
sources), and the `forecasts` rows it comes from are kept 30 days anyway.

**Prior / cold start:** an optimistic prior (e.g. Beta(1, 1) → 0.5) keeps today's behavior (fetch
everything) on a new install. The estimate only falls once evidence builds up.

## Q2 — P(source X viewed in next 24 h)

- **Primary source:** ≈ 1 on any active day. No model needed.
- **Others:** P(X) ≈ P(active tomorrow) × P(toggle | active) × P(X viewed | toggle day), or directly:
  a per-source Beta-Binomial on "active days where X was *viewed* (not passed through)", with
  recency weighting as in Q1 #3.
- **Position in the cycle matters:** sources earlier in the order get more pass-throughs and more
  real views. If the user reorders sources, the history of the moved source becomes stale. Option:
  reset or shrink that source's estimate when its position changes.
- **Split by view:** P(X viewed in the *hourly* view) is what the hourly-horizon decision needs. A
  source viewed only in the daily view needs daily rows plus the 72 h behind noon cloud and day/night
  rain %, not 240 h of hourly.
- **The window should be the fetch interval, not 24 h:** the real question is "will X be viewed
  before its next scheduled refresh?" For an interval T: P = 1 − (1 − p_day)^(T / 24 h), roughly.

## Turning a probability into a decision

- **Expected cost:** keep fetching X fully if p × cost_miss > cost_fetch. With the on-demand fetch
  (`HourlyOnDemand`), a miss costs a few seconds of "Fetching hourly…", not missing data. So
  cost_miss is small and the threshold can be fairly high, except for Google, where cost_fetch is
  real money.
- **Act on the upper bound, not the mean:** reduce fetching only when the 90 % upper credible bound
  is below the threshold. Then a light user with little data isn't cut off early.
- **Hysteresis:** off below 10 %, back on above 20 %. Otherwise a source flips on and off
  from one day to the next.
- **One toggle restores it:** viewing X raises its estimate at once and refreshes it on demand
  (`SourceToggleRefreshPolicy`), so a wrong "rarely viewed" call costs a single slow view.

## Checking the estimators

With 30 days of data, backtest: for each day d, predict from days < d and score against what
happened (Brier score or log loss). Compare #1/#2/#3/#4 and pick the half-life. Data for this is
needed from more than one user, or at least both of this user's devices.

## What this means for counts vs rows

| Needed by | Event rows | Daily counts |
|---|---|---|
| Active-day exposure | not in either; add it | not in either; add it |
| Toggled that day (Q1 #1–4, #6) | ✓ | ✓ |
| Source *viewed* vs passed through (Q2) | ✓ (from dwell) | ✓ only if decided **at write time** (record on the next switch / screen off) |
| Daily vs hourly view (Q2) | ✓ | ✓ as a key column |
| Weather context (Q1 #5) | ✗ (separate per-day record) | ✗ (same) |
| Time of day, exact sequence | ✓ | ✗ — no method above uses them |
| Backtesting | ✓ | ✓ (all methods work at day granularity) |

**Every method works at day granularity.** Counts are enough if the table has (a) an active-day
row and (b) "viewed" decided when it is written, not "switched to". Rows only buy time-of-day,
which nothing here uses and which is the part with privacy cost.

Possible counts table: `source_view_days(date, sourceId, view [DAILY|HOURLY], trigger, switches,
views)` plus one `ACTIVE` row per active day (or a `source_view_activity(date, activeFlag)`
table).
