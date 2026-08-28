# §5 follow-up: which paint path loads hourly rows at the abandoned site

**Status:** ✅ Root cause found and fixed 2026-08-28 (§3 instrumentation + the defect below; §5 policy still open)
**Follows:** [`260828-blend-reads-stale-fragment-not-configured-location.md`](260828-blend-reads-stale-fragment-not-configured-location.md) §5
**Goal:** identify the loader that hands the renderer a list stamped entirely at a device site the
configured location has left, then decide the one location policy that is currently undecided rather
than wrong.

---

## 1. What the first fix did and did not settle

`260828-blend-reads-stale-fragment-not-configured-location.md` fixed the *consumer*: the observation
blend now centres on the configured location, so a far-site hourly list can no longer drag the
observation read three hours into the past. That fix stands on its own and is verified.

It did not explain the *producer*. For `dataLoc` to read `37.41700` at all,
`WidgetRenderer.kt:282`'s `unifyToNearestSite(hourlyForecasts, configuredLat, configuredLon)` must
have found **no** row at the configured site — `selectNearestSite` ranks by distance and a `37.406`
row beats a `37.417` row by two orders of magnitude (Manhattan 0.0008 vs 0.0790). The database held
314 NWS hourly rows at the configured site. So the list handed to the renderer was already
site-filtered to the wrong site before it arrived.

---

## 2. What this session established

### 2a. The stale paints are the interaction path — and are reproducible on demand

`WIDGET_PAINT` names the origin of every paint. Around the 14:16 report:

```
14:16:16  CLICK_DAILY  index=2 ... targetView=TEMPERATURE
14:16:16  DOMINANT_STATION ... readingAgeMin=186  text=knuq 66.2° @ 11:10 am
14:16:16  WIDGET_PAINT widget=345 caller=TEMPERATURE origin=USER_INTERACTION
```

Every stale paint carries `origin=USER_INTERACTION` (day tap, set view, cycle zoom). Every correct
paint came from the worker's full sync. **This is not a race** — it is one code path being
consistently wrong, which is why it reproduced on every tap and looked like flapping only because
the two paths interleaved.

That makes this reproducible without waiting for another drive: both fragments are still in the
database, so pointing the configured location at one of them and tapping a day should reproduce it
immediately (§4).

### 2b. Every location source traced resolves to the configured location

| Path | Location source | Resolves to |
|---|---|---|
| `WidgetPaintCoordinator.refreshWidgetsFromCache` | `ActiveLocationResolver.resolve()` | configured |
| `GraphInteractionRenderer` | `refreshContext.location` → `ActiveLocationResolver.resolve()` | configured |
| `FullSyncPipeline:72` | `candidateAtLoad ?: activeLocation` | candidate, else configured |
| `WidgetStartupCoordinator` | `ActiveLocationResolver.resolve()` | configured |

And the same-site filtering downstream is present where it looks like it should be:
`loadGraphWindowHourlyForecasts` filters `centerRows`/`nowRows` through `LocationMatch.sameSite`, and
`HourlyForecastStitcher.collapse` filters *both* sides against `centerLat`/`centerLon`, dropping an
hour entirely when nothing at the centre serves it.

**So static tracing does not close this.** Something between "resolve the configured location" and
"hand rows to the renderer" is not honouring the centre, and reading the code has not found it. The
next step is instrumentation, not more reading — this is the point at which continuing to guess costs
more than measuring.

### 2c. Refuted: the pending GPS candidate

`FullSyncPipeline:72` loads at `candidateAtLoad?.location ?: activeLocation`, which looked like the
answer — a location source no other path consults. It is not, for this incident:

- `LocationHandoffStore.promoteIfMatches` clears the candidate on promotion, and the promotion
  happened at 11:55:09 (`state=candidate_promoted reason=complete_visible_coverage`).
- Every `GPS_RESAMPLE` from 12:00 to 13:06 logged `outcome=same_site lat=37.406424`, which is
  `propose()` returning `SAME_ACTIVE` — no candidate written.

So no candidate existed during the stale paints. Recording this because it is a plausible-looking
explanation that costs an hour to re-derive, and because §5 below keeps it alive as a *separate*
question rather than an answer to this one.

### 2d. Regression introduced by the first fix's own diagnostics

`TemperatureViewHandler`'s `dataLoc=` field is `resolutionResult.lat`, which now returns the
**configured** location by design. Before the fix it echoed the coordinate carried by the rows, and
that is the single field that revealed this bug. It now always agrees with `configuredLoc`, and the
producer-side signal is gone.

`obsCentre=` (added in §3.2) records where the blend read — the consumer side. Nothing now records
what the *rows* carry. §3 restores that as its own field rather than reverting `dataLoc`, since the
blend centre is genuinely the more useful thing for `dataLoc` to mean.

---

## 3. Instrumentation

Three fields, all on paths that already log.

1. **`rowsLoc=` on `TemperatureViewHandler.headerState`** — the distinct device sites carried by the
   hourly rows actually handed to the renderer, e.g. `rowsLoc=37.41700,-122.08900(226)`, or
   `rowsLoc=multi[37.406(12),37.417(214)]` when more than one survives. Multiplicity matters: "one
   far site" and "two sites and the near one lost" are different bugs.
2. **Promote `HourlyForecastLoader`'s `load:` line to `app_logs`.** It already logs
   `stitched=… center=$lat,$lon sites=…` at `Log.i`, which is logcat-only — the reason this question
   could not be answered from the incident at all. Add the paint origin so a line can be attributed
   to a path.
3. **The same line for `GraphDataLoader.loadGraphWindowHourlyForecasts`**, which has no equivalent
   today and is the prime suspect under §2a.

Keep all three permanently. This is the third coordinate-fragmentation bug in this area
(`260806-today-column-stale-fragment-delta-opus`, `260827-observation-site-merge-for-actual-series`,
and this one), and each time the missing evidence was "which centre did the loader actually use".

---

## 4. Reproduction

Do this **before** changing any behaviour — the point is to see the wrong value, not to make it go
away.

The device currently holds both fragments (`37.417,-122.089` and `37.406,-122.021`) and is configured
at `37.41682,-122.08902`. So:

1. Install the §3 instrumentation.
2. Set the location to the *other* fragment via `ConfigActivity` → manual coordinates
   `37.4064, -122.0206`. (Not a GPS move — this pins `location_mode=fixed` and writes the canonical
   active location directly, which is the state the incident had.)
3. Tap a day on the widget to force `origin=USER_INTERACTION`.
4. Read `rowsLoc` and the two loader lines.

Expected if §2a is right: the full-sync paint logs `center=37.4064` with rows at `37.406`, and the
tap-driven paint logs a different centre or rows at `37.417`.

**Restore the configured location afterwards.** Note that this leaves real fetches at the pinned
coordinate; that is acceptable (the data is genuinely wanted at whichever site) but it does write
rows, so do it once rather than in a loop.

---

## 5. The undecided policy: which location wins during a pending handoff

Separate from the bug, and worth settling deliberately because the first fix answered it by omission.

The handoff design is: on a genuine move, `GpsResampler` writes a **candidate**, `FullSyncPipeline`
**fetches at the candidate** while the widget keeps **displaying the active location**, and
`tryPromoteLocationCandidate` promotes only once the candidate has drawable coverage
(`MIN_COMPLETE_VISIBLE_HOURS`, or forward coverage after `MOVING_GRACE_MS` = 30 min). Keeping site A
on screen until site B is drawable is the point — it stops a drive past three forecast sites
repainting at each one.

During that window the app deliberately fetches at B and displays A. Which site should the
**observation blend** read at?

- **Configured (A)** — what the first fix does now. Coherent with the display: the header, the sun
  shading and the labels all say A, so the actual line says A too. But observations at A stop being
  refreshed the moment the fetch moves to B, so the blend goes stale for the length of the pending
  window — which is exactly the symptom that started all this, and can run to 30 minutes, or longer
  since promotion is retried only on a full sync (60–480 min by battery).
- **Candidate (B)** — mirrors `FullSyncPipeline:72`'s own `candidate ?: activeLocation` ordering, and
  gives the rule "read where the fetch is writing", which is simple and always fresh. But it puts the
  actual line at B while everything around it says A.

There is a reason to think B is right that is specific to observations: **a device site is fetch
provenance, not a weather location** — the premise `ObservationSiteMerge` is built on. Sites 6 km
apart blend largely the same stations (KNUQ, KSJC, KPAO). So the A/B distinction matters far less for
the observed line than it does for a forecast, while the staleness cost of choosing A is concrete
and measured.

**Do not decide this until §4 has run.** If the producer bug turns out to be a missing same-site
filter, this window may be rarer than it looks, and a policy chosen against a mis-modelled frequency
is the kind of constant that gets tuned wrong later. Recording the options now so the decision is
made once, with evidence, rather than inherited.

---

## 6. Deferred until §4 and §5 land: `selectNearestSite` has no distance ceiling

`LocationMatch.selectNearestSite` returns the nearest site *present in the list*, however far. A
fragment 6 km away wins whenever it is the only one there — no gate, no log. That is what let a
wrongly-scoped list render as if it were correct instead of failing visibly.

A ceiling is tempting and is deliberately **not** in this plan: the function governs every forecast
read in the app, and the honest sequence is to fix the thing that produces the bad list first, then
decide whether a backstop is still worth its blast radius. If it is, the first increment should be a
`WARN` when the chosen site is further than `ObservationSiteMerge.MERGE_TOLERANCE_DEG`, not a
behaviour change.

---

## 7. Testing

The instrumentation in §3 is diagnostic and needs no tests of its own beyond compiling. The fix does,
once §4 names it. Two things to write regardless:

- **A test that the interaction path and the sync path agree.** Whatever the mechanism, the invariant
  is that two loaders given the same centre and database return rows at the same site. That is a real
  integration test (two loaders, one database) and it is the assertion that would have caught this
  without a device. Precedent: `CurrentTempUnificationIntegrationTest`, written for exactly this
  shape of divergence between two paths.
- **A `collapse` test for the drop-the-hour branch.** `HourlyForecastStitcher.collapse` returns null
  for an hour with no same-site row, which is correct but silent — it is how a fragment-only hour
  disappears. Pin it, so a future change that makes it fall back to a far site fails loudly.

---

## 8. Not in scope

- The orphaned `widget_lat_346` / `widget_lat_353` prefs keys (§7 of the previous plan). The risk was
  `LegacyDefaultLocationMigration`'s `widget_lat_*` prefix scan, and that migration is already slated
  for deletion in `plans/260827-delete-legacy-default-location-migration.md`. Fold the keys into that
  plan's acceptance greps rather than touching prefs-writing code now.

---

## 9. Outcome (2026-08-28)

Found by the §7 differential test, before the §4 device repro was ever run.

**`HourlyForecastStitcher.collapse` had an unbounded same-site fallback:**

```kotlin
val sameSite = hourRows.filter { … LocationMatch.sameSite(centerLat, centerLon, lat, lon) }
    .ifEmpty { hourRows }        // ← any site in the caller's ±0.1° box
```

For any hour the configured site did not cover, the stitcher took a row from **any** site the query
box returned — including one 0.068° (~6 km) away. The borrowed row carries its own coordinates, so a
downstream `firstOrNull()` adopted that site as the render location, which is how the observation
blend ended up centred three hours in the past.

**Both loaders leaked**, not just the interaction path:

```
sync path        leaked 7 row(s); sites=[37.40600,-122.02100, 37.41700,-122.08900]
interaction path leaked 7 row(s); sites=[37.40600,-122.02100, 37.41700,-122.08900]
```

So §2a's narrowing to `origin=USER_INTERACTION` was a correct reading of which *paints* were wrong,
but the wrong conclusion about blame — which loader you see depends only on which one painted last.
§2b's "the same-site filtering looks correct everywhere" was wrong for the same reason I misread it
twice: `collapse` ends with `pick(sameSite) ?: return@mapNotNull null`, which reads as "drop the
hour", and the `.ifEmpty` two lines above silently makes `sameSite` not same-site at all.

The unfiltered `historyRows` (§3) is real but secondary: it widens the input, while the `ifEmpty` is
what admits it.

### Why bounded rather than deleted

`git log -S` dates the fallback to `72e5a033` (2026-06-21), *"…and blank lines from GPS jitter"*. It
exists to stop an hour going blank when a jitter fragment sits just outside
`SAME_SITE_TOLERANCE_DEG` — a reachable case, at 0.0021832886° per `HourlyForecastLoader`'s own
comment. Deleting it would reintroduce that. So the fallback keeps its job and gains a bound:
`NEARBY_FALLBACK_TOLERANCE_DEG = 0.01` (~1 km), past the jitter boundary and short of a different
place, finer than every forecast grid the app reads.

Numerically equal to `ObservationSiteMerge.MERGE_TOLERANCE_DEG`, deliberately its own constant —
that one is sized by `distanceKm`'s IDW error budget for observations, this one by forecast-grid
resolution.

**This is the third same-site guard in this codebase with no distance bound**, after
`selectNearestSite` (§6, still open) and the one `ObservationSiteMerge` was written to replace. The
recurring shape is a tolerance chosen for GPS jitter being asked to answer a question about a
genuine move.

### The documentation was wrong, not just the code

The class KDoc claimed markers "farther than the same-site tolerance **are dropped**". They were not;
they won whenever they were the only rows for an hour. Corrected as part of the fix — a contract that
states the invariant the code violates is worse than none, because it stops the next reader looking.

### Why the existing test suite missed it

`HourlyForecastStitcherTest.same-site fragments collapse to the freshest, off-site marker is dropped`
asserts exactly the right contract — but always supplies a same-site row, so the off-site marker
loses on merit and `ifEmpty` never executes. The guard was tested only where it works. The three new
cases remove the same-site row so the fallback is actually exercised, and pin **both** sides of the
bound: 0.0021° is borrowed (72e5a033's behaviour survives), 0.068° is dropped.

### §4 was not needed

The device repro is unnecessary for this defect — the differential test reproduces it deterministically
from an in-memory database. Keep §4 in reserve for confirming the end-to-end paint if a related report
recurs, and §5's pending-handoff policy still wants the emulator's `geo fix`.
