# Session log — Battery & DB-write savings for non-selected ("other") APIs + remove hourly force-write window

**Date:** 2026-06-24
**Branch:** main
**Status:** ⏳ All changes uncommitted in working tree (user has not asked to commit). Unit tests green.

**Plan file:** `~/.claude/plans/wondering-if-you-have-eager-parnas.md`
**Memory:** `memory/primary_means_displayed_source.md` (+ MEMORY.md pointer)

---

## All prompts (verbatim, in order)

1. `Wondering if you have any thoughts on saving battery life and or db writes.  Low priority: other APIs.  primary api is currently selected api.  Other APIs is everything else.`
2. *(AskUserQuestion: goal / primary-def / aggressiveness)* → goal answered freeform **"I was hoping to get a list of ideas.  Why so rigid on what the primary goal is?  Can't have two goals and not worry which is primary?"**; primary-def → **"Yes, unify to selected"**; aggressiveness → **"Moderate"**
3. *(ExitPlanMode — approved)*
4. `Seems wrong: How stale an unchanged hourly row may get before we rewrite it just to refresh its fetched at timestamp` / `Correct: Update fetched at regardless if changed or unchanged. What do you think?`
5. `Remove the window globally and verify the retention question`
6. `write to session-logs/ dir`

---

## The core finding

The app already deprioritized "other APIs" in **three** places, but each used a *different*
definition of "primary":

| Policy | Gates | "Primary" was | Matched user's "= selected"? |
|---|---|---|---|
| `ForecastFetchPolicy` | fetch cadence | `activeSourceIds` (displayed) | ✅ |
| `CurrentTempRepository` | current-temp throttle (rank ≥3) | global `visible_sources_order` | ❌ |
| `ForecastHistoryPolicy` | DB history bucket (4h/8h) | global first-in-order (`getPrimarySource()`) | ❌ |

Consequence: toggling a widget to a non-first source gave the *displayed* source the slow DB
lane and could even throttle its current temp — backwards from intent.

---

## What changed (the menu, as implemented)

**Foundation — unify "primary = currently-displayed source".**
New `WidgetStateManager.getActiveDisplaySourceIds(): Set<String>` — queries `AppWidgetManager`
for all widget ids → `getCurrentDisplaySource`, falls back to `getPrimarySource()` when no
widgets exist. Every prioritization decision now keys off this set.

**Idea 1 — DB history cadence + current-temp throttle on the displayed set.**
- `ForecastHistoryPolicy.bucketMs/snapshotBucket` now take `prioritySourceIds: Set<String>`
  (was a single `primarySourceId: String`). Both `ForecastRepository` call sites pass
  `getActiveDisplaySourceIds()`.
- `CurrentTempRepository` throttle now exempts any displayed source regardless of global rank.

**Idea 2 — non-priority history cadence 8h → 12h** (`ForecastHistoryPolicy.NON_PRIMARY_BUCKET_MS`).
Render-side `ForecastEvolutionGeometry.SNAPSHOT_BUCKET_HOURS = 4` is a display-only collapse,
**not** coupled to the write cadence — confirmed, left untouched.

**Idea 3 (revised after prompt 4-5) — removed the hourly force-write window entirely.**
`hasMeaningfulHourlyChange()` previously rewrote an unchanged row when `>60min` since last
fetch, purely to refresh `fetchedAt`. The user flagged the justification as backwards. Resolution
went the *opposite* direction from "always write" (which would defeat the whole write-reduction
goal, since the live table is `@Insert(REPLACE)` = whole-row write): **drop the time clause.**
A live hourly row is now written only on a real content change. The priority-window plumbing
added mid-session was reverted; `prioritySourceIds` is still computed in `saveHourlyEntities`
for the history-bucket cadence.

**Idea 4 — off-charger non-active fetch multiplier.**
`ForecastFetchPolicy.intervalMinutes()` off-charger had *no* active/non-active split (both fell
straight to `BatteryFetchStrategy`). Now non-active sources get `× OFF_CHARGER_NONACTIVE_MULTIPLIER`
(= 2). `?: return null` preserved, so a battery-suppressed fetch is never resurrected by the
multiplier.

---

## The `fetchedAt` design discussion (prompts 4-5)

`fetchedAt` was overloaded: (A) "when content was produced" vs (B) "when we last confirmed it".
The dedup wants (A); the old force-write window was a muddy lazy-(B) bolted on. Decision: make it
cleanly (A), drop the window. **Verified safe** against the retention coupling the user asked about:

- `hourlyForecastDao.deleteOldForecasts` = `DELETE ... WHERE fetchedAt < :cutoff`, cutoff = **30 days**
  (`cleanOldData`, `ForecastRepository.kt:983`).
- Live display window is only **`-24h..+60h`** (shared `HOURLY_LOOKBACK/LOOKAHEAD`). A row can't
  be both on-screen and have a 30-day-old `fetchedAt`: near-term forecasts get genuine content
  changes that bump `fetchedAt` long before then. Far-future cached rows age out as past data.
- History snapshots stamp their own real fetch time (built from fresh `mergedEntities`),
  independent of this dedup.
- User-facing staleness/refresh reads `location.fetchedAt`, not the hourly row.
- Only live-row `fetchedAt` consumer is `ForecastSourcePriority.resolveForecastsByTime`
  (freshest-anchor), internally consistent — becomes *more* correct (freshest = newest content).

---

## Files changed

Production:
- `widget/WidgetStateManager.kt` — new `getActiveDisplaySourceIds()`; doc on `getPrimarySource()`.
- `data/repository/ForecastHistoryPolicy.kt` — `Set<String>` signature; `NON_PRIMARY_BUCKET_MS` 8h→12h.
- `data/repository/ForecastRepository.kt` — both save call sites use the set; `hasMeaningfulHourlyChange`
  time clause + the two `*_HOURLY_FORCE_WRITE_MS` constants removed.
- `data/repository/CurrentTempRepository.kt` — displayed sources exempt from throttle.
- `widget/ForecastFetchPolicy.kt` — `OFF_CHARGER_NONACTIVE_MULTIPLIER` off-charger.

Tests:
- `ForecastHistoryPolicyTest` — set-based signature, 12h, "every displayed source gets fast bucket".
- `ForecastFetchPolicyTest` — rewrote the "off-charger ignores active distinction" test; added
  multiplier + null-passthrough cases.
- `ForecastRepositoryHourlyChangeTest` — replaced the two fetchedAt-window tests with
  "unchanged row is never rewritten regardless of age".
- `OpenMeteoIntegrationTest`, `OpenMeteoDayNightPrecipIntegrationTest` — stub `getActiveDisplaySourceIds`;
  `snapshotBucket(...)` call updated to set arg.

---

## Tests

`./gradlew testDebugUnitTest` for the 5 affected classes — **BUILD SUCCESSFUL**, all green
(both before and after the window removal). Main source set compiles (so no other callers used
the old signatures — verified via grep too).

---

## Not done / open

- **Idea 5 (measurement)** left for the user: existing `SNAPSHOT_SKIP` / `CURR_FETCH_THROTTLE_SKIP`
  logs + row counts (`python3 scripts/backup_databases.py` → `sqlite3`) to quantify before/after.
- No `installDebug` / on-device verification run this session.
- Nothing committed.
