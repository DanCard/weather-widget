# Daily view rain-label work (emulator + desktop)

**Date:** 2026-07-06

Three related changes to the daily-forecast rain labels, all verified live on both platforms.

---

## 1. Rain % anchors above the *topmost* high label

**Bug:** On a past day the daily view can print two high-temp labels — the observed actual
(thermostat pink) and the forecast overlay (yellow). Since warmer temps draw higher on the graph,
when the forecast ran warmer than reality its label sits *above* the actual. Both renderers
anchored the rain % to the actual high only, so "15%" wedged between the two numbers instead of
clearing the top one (seen on the "Sun" / yesterday column).

**Fix:**
- **Android** (`DailyForecastGraphRenderer.kt`): extracted the dual-high decision into a shared
  `resolveHighLabelPlan()` used by both the draw loop and the rain-label resolvers. It exposes an
  `anchorHigh`/`anchorBaseline` that is the *topmost* (warmer) of the two labels when both are
  shown, and the headline `effectiveHigh` otherwise. `resolveHighLabelBaseline` /
  `resolveHighLabelDrawScale` now return/measure against that anchor.
- **Desktop** (`DailyForecastGraph.kt`): the dual-high branch now sets
  `highLabelTopAtCenter = minOf(aY, fY)`.

Single-label days (most days) are unchanged — they still anchor to the headline high.

---

## 2. Rain-label font scaling ported to desktop (new history rule)

Desktop previously drew every day rain label at a flat `9sp` (night `11sp × NIGHT_SCALE`),
ignoring probability and distance — a real parity gap with Android.

**New shared rule** (`DailyRainLabels.rainLabelFontScale`, in `:shared`):
- **Future / today:** probability-weighted × distance-weighted (far-out low-confidence drizzle
  shrinks).
- **Past (history):** probability-weighted **only** — the days-into-the-future distance term is
  dropped, so a history chance% sizes exactly like a same-probability *near-term* forecast chance%.
  (This is a deliberate change from Android's old flat `0.85×` history size.)

Both renderers now call the shared function; Android's `HeaderPrecipCalculator.getPrecipScaleFactor`
delegates to the shared step table (`precipProbabilityScaleFactor`) so header + labels can't drift.
Desktop `DesktopDailyDay` gained `dayPrecipProbability` / `nightPrecipProbability` / `daysFromToday`
to feed the scale. New shared tests lock the behavior (history == probability-only; future shrinks
with distance).

---

## 3. Day rain label sized off the chance it displays (Android-only)

**Symptom the user caught:** on yesterday, two "15%" labels but the *night* one rendered larger
than the *day* one — backwards.

**Root cause (latent, surfaced by change 2):** the day label's **font** was sized off
`RainData.dailyPrecipProbability = precip` (`weather.precipProbability`, a raw daily field), while
its **text** and **icon** came from `resolvedPrecip.dayPrecip` (the 8am–8pm window max). The two
diverge; when the raw daily value was lower than the resolved day chance, the "15%" day label
shrank below the equal-chance night label (which was already wired to its resolved night chance).
Flat history sizing had masked this until change 2 made history probability-dependent.

**Fix:** `DailyViewLogic.kt` now sets `dailyPrecipProbability = dayPrecipForIcon` so the day label
sizes off the same day chance it shows, mirroring `nighttimePrecipProbability`. (Desktop already
used the resolved value for both text and font, so it never had this bug.)

---

## Verification
- `:shared`, `:app`, `:desktop` all compile; shared/app rain-label + desktop UI tests pass.
- Android installed to emulator; desktop rebuilt + restarted via `scripts/buildStart-desktop.sh`.
- User visually verified each of the three changes.

## Open design question (not implemented)
Day is **not** guaranteed larger than night. For equal chances it is (the `NIGHT_SCALE = 0.72`
factor), but a night much more likely than the day can still edge larger (e.g. 15% day vs 90%
night). A hard clamp (night label never exceeds day label) was offered but not added — revisit if
that case looks wrong in practice.

## Notes
- Pre-existing unrelated working-tree changes (`desktop/.../LogList.kt`, `ObservationsWindow.kt`)
  were left untouched.
