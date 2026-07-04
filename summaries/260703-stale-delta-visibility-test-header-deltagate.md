# Stale delta-visibility test updated to the HeaderDeltaGate contract

*2026-07-03 · commit `ad0544b5` · fixes the one failing test flagged in
`summaries/260703-max-horizon-delete-coverage-triggers-verification.md`*

## What was wrong

**The test was stale, not the code.** Commit `23ce822a` ("Keep header delta visible on
future scroll…") deliberately changed the visibility rule from "hide whenever the NOW
line isn't in the window" to the `HeaderDeltaGate` contract
(`shared/src/main/kotlin/com/weatherwidget/shared/graph/HeaderDeltaGate.kt`): **visible
while the window includes now or extends into the future, hidden only once the window has
scrolled entirely into the past** (mirrors `GhostLineGate`'s future-yes/past-no rule).

The implementation, desktop parity, and the `deltaHiddenReason` string were all updated —
but `TemperatureDeltaVisibilityRoboTest`'s case "delta badge is hidden when now line is
not visible" still pinned the old rule (`centerTime = now.plusHours(24)` expecting
*hidden*), so it failed against the intended new behavior. It failed identically on a
clean checkout of `HEAD`, which is how the horizon-refactor verification confirmed it was
pre-existing rather than a regression.

## The fix

Replaced the stale case with both sides of the new contract:

- scrolled **+24h into the future** → delta **visible** (the exact scenario commit
  `23ce822a` existed to fix)
- scrolled **48h into the past** (fully past even at the widest zoom's forward span) →
  delta **hidden**

Full `TemperatureDeltaVisibilityRoboTest` class passes; suite back to 1404/1404.

## Lesson

This failure mode — behavior deliberately reversed, one test left pinning the old
behavior — is why test names that describe the *rule* ("hidden when now line is not
visible") age badly compared to names describing the *contract source*. The renamed
tests reference the gate's semantics directly, so the next person changing
`HeaderDeltaGate` gets pointed at exactly the tests encoding its contract. Same lesson as
the max-horizon refactor, in miniature: the nav-trigger's Open-Meteo gate and the
render-trigger's missing gate diverged because the rule lived in two places with nothing
tying them together.
