# Remove the location handoff policy

**Status:** 📋 Planned 2026-08-28 · **Decision:** Danny, 2026-08-28
**Supersedes:** the pending-candidate policy in `LocationHandoffPolicy` / `LocationHandoffStore`
**Related:** [`260828-interaction-paint-loads-hourly-at-the-wrong-site.md`](260828-interaction-paint-loads-hourly-at-the-wrong-site.md) §5

> "Battery policy will help with thrashing. If the user is looking at the phone they should have
> accurate info. If user wants a refresh, they should get a refresh. Forbidding by policy is
> horrible." — Danny, 2026-08-28

**Goal:** the displayed location follows the device immediately. Fetch *cost* stays governed by the
battery-aware cadence that already governs it. No second gate withholding correct information.

---

## 1. Why this is right, beyond the decision

### 1a. The gate is redundant with the one that actually protects the budget

The stated rationale is "a drive through several forecast sites would repaint and refetch each
intermediate site". Repainting is free. Refetching is not — but fetch cost is *already* bounded by
the battery-aware interval (60 min plugged → 480 min under 20%) and the >30-minute staleness gate.
A second mechanism that withholds correct information to protect a budget the first mechanism
already protects is doing the wrong job.

### 1b. The policy has its behaviour backwards — measured, twice

**It withholds when it should promote.** Emulator run, 2026-08-28, device moved to San Francisco:

```
LOCATION_HANDOFF state=candidate_waiting_data reason=waiting_for_history_or_stability
  candidate=37.7749,-122.4194 dailyRows=69 hourlyRows=156
```

69 daily rows and 156 hourly rows — past `dailyReady`, comfortably past `MIN_FORWARD_HOURS = 10`.
San Francisco was drawable and was withheld **purely by `MOVING_GRACE_MS`**, while the widget showed
Mountain View's weather to a device in San Francisco for the full 30 minutes, with no label saying so.

**It promotes when it should withhold.** From
[[location_move_collapses_today_actuals]], Samsung 2026-08-22: two GPS excursions ~0.5 mi from home
were promoted instantly, each with **zero observations before 12:00**, corrupting the today-column
thermostat low (57.03° → 66.52°). The cause is in the policy's own structure — `completeVisible` is
computed from **forecast** rows only, and it `return`s *before* the `MOVING_GRACE_MS` check, so the
guard that exists to stop a drive promoting intermediate sites never engaged for the exact case it
was written for.

So the policy delays the case it should allow and waves through the case it should catch. Deleting it
loses nothing that works.

### 1c. The correctness argument for gating has already been retired

[[location_move_collapses_today_actuals]] records the reframing after
`plans/260822-today-low-backfill-then-forecast-fallback.md` was decided:

> Defect #2 reframed — with the decided plan making the UI correct whether or not a stub is
> promoted, **promotion gating drops from a correctness fix to a quality issue about site thrashing.**

The remaining justification was thrashing, and §1a disposes of that.

### 1d. It contradicts a principle this codebase already adopted

CLAUDE.md, on deleting `DEFAULT_LAT/LON`: it "used to fetch and label Mountain View's weather for
anyone whose GPS never resolved." The pending window does the same thing for up to 30 minutes,
unlabeled. The principle that killed the default location — never present another place's weather as
yours — was not carried into the handoff.

---

## 2. What "remove the policy" means precisely

**Delete:**

| Thing | Why it goes |
|---|---|
| `LocationHandoffPolicy` (190 lines) — `evaluateCandidateUsability`, `MOVING_GRACE_MS`, `REQUIRED_DAILY_DAYS`, `MIN_COMPLETE_VISIBLE_HOURS`, `MIN_FORWARD_HOURS` | the gate itself |
| `LocationHandoffStore` (123 lines) — candidate persistence | nothing left to hold pending |
| `tryPromoteLocationCandidate`, `LocationCandidateOutcome` | promotion is no longer conditional |
| `candidateAtLoad` / `candidate ?: activeLocation` in `FullSyncPipeline` | one location source again |
| `CandidateProposal` states, `isAcquisition` branch | acquisition and following become the same thing, which is the honest end of the split CLAUDE.md documents |

**Keep, deliberately:**

- **`location_mode = fixed`.** A user pinning a location is a *choice*, not a policy overriding them.
  `skipped_pinned` stays exactly as it is.
- **Passive-only background GPS.** Untouched; the Samsung precise-location rule is unrelated to this.
- **A location change forces one fetch.** You moved; you want data. That is the "if the user wants a
  refresh they get a refresh" half of the decision.
- **Every `GPS_RESAMPLE` breadcrumb.** More important after this, not less.

---

## 3. Where the thrash budget actually lives

Removing the gate must not turn a train journey into a fetch per station. It does not, provided one
thing is true: the location-triggered fetch respects the same battery-aware interval as every other
fetch.

Today `FullSyncPipeline` sets `forceRefresh = (input.forceRefresh || candidateChangedThisRun)`, so a
location change **bypasses** the cadence. That bypass was safe only because the policy made candidate
changes rare. With the policy gone it is the thrash vector, so it moves under the existing budget:

- A location change requests a fetch.
- The battery-aware interval decides whether it happens now or coalesces with the next one.
- The user's own refresh action stays immediate and unconditional — that is a request, not a policy.

This is the decision's actual mechanism: **one budget, applied uniformly, instead of a second policy
that hides data.**

---

## 4. The one real risk, and its fix

Promoting immediately means the new site can have thin observations for a while, and today's actuals
are built from observations. The self-heal for this already exists — `OBS_HOURLY_BACKFILL` with
`reason=temperature_graph_sparse_history` — but [[location_move_collapses_today_actuals]] records a
defect that this change makes far more likely to bite:

> its cooldown key is `"${displaySource.id}_HOURLY_HISTORY"` per widget with **no site component**,
> so a heal at the old site suppresses the new site's for 30 min.

**Fix that first, in its own commit.** Add the site to the cooldown key. Without it, every promotion
lands on a site whose backfill is suppressed for exactly the window in which it is needed — turning
"accurate but briefly sparse" into "accurate and sparse for half an hour", which is the outcome this
change exists to avoid.

Sub-mile GPS excursions will now promote (the 2026-08-22 stubs were ~0.008°, outside
`SAME_SITE_TOLERANCE_DEG`'s 0.002°). That is correct under the decision — that *is* where the phone
is — and with the backfill working it is a data-completeness question, not a correctness one. Worth
watching after it ships rather than pre-emptively re-gating.

---

## 5. Testing

Five existing test files encode the policy and must be rewritten rather than deleted wholesale — the
scenarios stay, the expected outcomes invert:

| File | Becomes |
|---|---|
| `LocationHandoffPolicyTest` | delete with the policy |
| `LocationHandoffStoreTest` | delete with the store |
| `LocationHandoffRoboTest` | "a detected move promotes immediately" |
| `LocationHandoffIntegrationTest` (androidTest) | same, end to end |
| `GpsResamplerTest` | keep; drop candidate assertions, keep permission/pinned/no-fix/same-site outcomes |
| `LocationRoundTripRoboTest` | rewrite: step 2 asserts the active location **moves**; step 4 still asserts nothing is lost on return |

New coverage the change needs:

- **A move promotes with no data at the new site at all.** The case the old policy existed to
  prevent; assert the widget shows the new location and degrades honestly rather than showing the old
  one.
- **A pinned location is never promoted over.** The one gate that survives.
- **Location-triggered fetches respect the battery interval** (§3) — the assertion that the thrash
  budget really did move rather than vanish.
- **Backfill cooldown is per-site** (§4), proving a move is not suppressed by the old site's heal.

---

## 6. Sequence

1. Site-scoped backfill cooldown key (§4). Independent, shippable, valuable on its own.
2. Move the location-triggered fetch under the battery interval (§3).
3. Delete the policy and store; promote on detection (§2).
4. Rewrite the tests (§5).

Steps 1 and 2 first so that when the gate goes, the machinery that replaces it is already carrying
the load.
