# Session log — Hourly graph used the *earliest* forecast snapshot for past hours (desktop 78° vs Samsung 74°) + label-provenance logging

**Date:** 2026-06-23
**Branch:** main
**Status:** Two pieces.
- ✅ committed `d177db54` (by user) — "Use latest forecast for all hours instead of earliest snapshot for past hours" (the core bug fix across 3 sites + tests/comments)
- ⏳ uncommitted — label-decision logging enrichment in `TemperatureLabelResolver.kt` (provenance/displayed/clock-time)

**Plan file:** `~/.claude/plans/hourly-temperature-forecast-desktop-glowing-kettle.md`
(commit `d177db54` also added two `plans/…really-bad-code-to-choose-earliest-forecast*.md` notes)

---

## All prompts (verbatim, in order)

1. `Hourly temperature forecast: Desktop forecasts high of 78, samsung says 74.`
2. `Desktop is wrong`
3. `I connected samsung and emulator.  You can use emulator for reference if that helps.`
4. `What's the plan?`
5. *(AskUserQuestion — past-today line)* → freeform **"Totally wrong to chose oldest forecast.  Should be latest forecast."**
6. `I wonder how earliest even got in the code.  That is so totally wrong.`
7. *(rejected ExitPlanMode)* `Everything in Commit 72e5a033 is suspect.  Should we look at that later or now?`
8. `I already ran installDebug and it looks good.  Should we search the code for minByOrNull and see if anything is suspect?`
9. `On desktop I see a label of 73.8 on hourly temperature graph.  Is that an interpolated value?`
10. `Why is it a high label if 74 is higher than 73.8?`
11. `Would it help if logging where added to make this easier to diagnose if the question is asked again?`
12. `yes`
13. `write a session log to session-logs/ dir`

---

## The bug (committed `d177db54`)

**Symptom:** desktop hourly graph forecast high = 78°, Samsung = 74°, same location/source. Desktop wrong.

**Diagnosis (live DB + panel screenshot, not just code reading):**
- Desktop was on **NWS** (source indicator + every `REFRESH`/`CURR_TEMP` log). Live NWS high today = **74°**, same as Samsung.
- For **elapsed (past) hours of today**, the graph drew the *as-predicted* line from `hourly_forecast_history`, and `HourlyForecastStitcher` selected `minByOrNull { fetchedAt }` = the **earliest snapshot ever stored**. Desktop had snapshots back to **06-17 (6.2 days old)** predicting today's afternoon at **78–79°** → the 78° peak.
- Samsung showed 74° only because its history didn't reach that far back, so it fell through to the live forecast. **Same shared code, divergence = history depth, not source.**
- Even the genuine **1-day-ahead** snapshot was 78° here (NWS busted the forecast; actual ≈69°). Only the **latest** forecast gives 74°.

**Origin:** commit `72e5a033` ("Fix hourly graph showing hindsight revisions for past hours") meant to avoid NWS's REPLACE-overwritten hindsight, but implemented "original prediction" as the *earliest* snapshot — the 6–7-day-out, least-accurate long-range forecast. The same "earliest-for-past" mental model had also been copied into `CurrentTemperatureResolver` (commit `6325cab2`).

**Fix — "latest forecast wins for every hour" at all three sites:**
- `shared/.../data/model/HourlyForecastStitcher.kt` — `originalByTime` `minByOrNull`→`maxByOrNull`; removed the `time < nowMs` past-branch so live wins for all hours, history backfills missing hours/nullable fields. `nowMs` kept for call-site compat (unused).
- `shared/.../widget/CurrentTemperatureResolver.kt` — `pickBestForecast` was `if (past) minByOrNull else maxByOrNull`; now always `maxByOrNull`. (This had inflated the live current-temp `estimate` to ~75.7° vs the real 74°.)
- `shared/.../data/local/desktop/DesktopWeatherDao.kt:getHourlyHistory` — SQL `ORDER BY … snapshotBucket ASC`→`DESC`. This pre-collapses history to one row/hour *before* the stitcher sees it, so without it the freshest-history fallback (for fully-past days aged out of live) would still get the stale earliest. (Android already passed raw buckets + ordered `snapshotBucket DESC`, so it was effectively consistent once the stitcher flipped.)
- Tests inverted: `HourlyForecastStitcherTest`, `DesktopWeatherDaoTest`, `DesktopWeatherRepositoryTest`; comments in `GraphDataLoader`/`DesktopWeatherDao` updated.

**Verified:** shared/desktop/app unit tests green; user verified desktop now shows 74° and ran `installDebug` (widget unchanged).

**Trade-off accepted:** navigating to a fully-past *day* on the main hourly graph now shows that day's *latest* (near-final) forecast, not the day-ahead prediction. As-predicted accuracy comparison still lives only in the dedicated Forecast History view.

---

## `minByOrNull` audit (prompt 8)

Swept all 24 `minByOrNull` in production code + every `fetchedAt`/`snapshotBucket` SQL ordering + `sortedBy { fetchedAt }`. Classification:
- temperature lows / distance (nearest station/location) / time-distance (closest to now/noon) / soonest-schedule → all correct.
- The **only** surviving `minByOrNull { fetchedAt }` is `DailySnapshotSelector.kt:31` — **intentional**: the daily triple-bar's "as-of-24h-ago" left bar; its primary path is `maxByOrNull` (freshest snapshot still ≥24h old), `minByOrNull` is just the "get as close to 24h-prior as possible" fallback. Old-snapshot semantics belong in that dedicated comparison feature, not the main line.
- Remaining `ASC` orderings (`DesktopWeatherDao:901`, `ForecastDao:311`) feed the **Forecast Evolution** graph — chronological by design.
- The three `sortedBy { fetchedAt }` are chronological evolution draws.

**Conclusion:** the defect was confined to the three already-fixed sites. No further changes.

---

## History-fetching confirmation (prompt during wrap-up)

User wanted to confirm hourly-forecast *history* is never refetched unless missing. Confirmed — invariant already holds, stronger than asked:
- History is **never separately fetched**. It's snapshotted from the *same* forward forecast fetch (`refresh()` desktop `:245`; `ForecastRepository` `:807-828` Android), keyed by a 4h/8h `snapshotBucket` (`ForecastHistoryPolicy`).
- You **cannot** refetch a past forecast — APIs only return current+future. The past-forecast curve only accumulates forward; fresh installs start sparse (code explicitly refuses to seed Open-Meteo decimals into history).
- The only on-demand historical fetch is **observations** (`ensureHistory`), gated by `deepestHistoryDaysFetched` (only deepens past what's cached).

---

## "73.8 on the graph — is it interpolated?" (prompts 9–10) + logging (11–12)

- **Yes, interpolated — and it is NOT a high label.** From the desktop label log: `displayed="73.8" role=LOCAL reason=FORECAST_MIDPOINT provenance=SMOOTHED_MIDPOINT val=73.791664`. It's a synthesized **midpoint** anchor `addForecastMidpointLabel` drops on the bare, monotonically-descending *future* forecast segment (NOW≈3:50pm → 72° at the right). The real daily **high is the 74°** label on the left (the early-afternoon plateau). `dailyHighIndex = maxByOrNull { labelTemps[it] }` = 74.0; 73.8 and 74 are different label roles, so the "high" isn't below anything.
- NWS emits integers; the decimals in `LABEL_TEMPS` are `smoothValuesPreservingAllExtrema` easing the 74→72 transition.

**Logging enrichment (uncommitted), `shared/.../graph/TemperatureLabelResolver.kt`:**
- `provenanceFor(role, isMidpoint)` → `OBSERVED` / `SMOOTHED_FORECAST` / `SMOOTHED_MIDPOINT`.
- `logLabelDecision(...)` — one enriched DEBUG line carrying `displayed` (on-screen string), `t` (clock time), `role`, `reason`, `provenance`, `val`, `idx`. Applied to all 3 `LabelAccepted` sites + `MidpointSuppressed`.
- **Deliberately NOT routed to `app_logs`**: the resolver runs every render; routing would recreate the "CurrentTempResolver swamp" the codebase already filters against. Kept in the file sink.
- Dropped the `°` glyph (file sink isn't UTF-8, rendered it as `?`); bare number still greps.

Now answerable in one grep:
`grep 'displayed="73.8' ~/.local/state/weather-widget/autostart-*.log`
→ `… reason=FORECAST_MIDPOINT provenance=SMOOTHED_MIDPOINT …`

---

## Memory updates this session

- Updated `hourly_forecast_line_is_hindcast.md` — its "earliest snapshot for past hours" fix path is now reversed.
- Added `hourly_past_hours_latest_forecast.md` — the latest-wins fix across the 3 sites + the audit conclusion.

---

## Key files

- `shared/src/main/kotlin/com/weatherwidget/data/model/HourlyForecastStitcher.kt`
- `shared/src/main/kotlin/com/weatherwidget/widget/CurrentTemperatureResolver.kt`
- `shared/src/main/kotlin/com/weatherwidget/data/local/desktop/DesktopWeatherDao.kt` (`getHourlyHistory`)
- `shared/src/main/kotlin/com/weatherwidget/shared/graph/TemperatureLabelResolver.kt` (logging, uncommitted)
- `shared/src/main/kotlin/com/weatherwidget/shared/util/DailySnapshotSelector.kt` (audited, intentionally unchanged)
