# The observation blend read at the data's coordinate, not the configured one

**Date:** 2026-08-28
**Plan:** [plans/260828-blend-reads-stale-fragment-not-configured-location.md](../plans/260828-blend-reads-stale-fragment-not-configured-location.md)
**Report:** *"hourly forecast: samsung: stale info `knuq 66.2 @ 11:10 am`"*

## What happened

At 14:17 the hourly graph named an 11:10 reading. KNUQ had reported at **13:35**, and that row was in
the database.

The device moved Mountain View → Sunnyvale at 11:55
(`LOCATION_HANDOFF state=candidate_promoted location=37.4064271,-122.0206173`). Fetching followed the
move; rendering did not.

| | old site `37.417,-122.089` | new site `37.406,-122.021` |
|---|---|---|
| newest NWS observation | **11:10** | **13:50** |
| last fetch | 11:26 (frozen) | 14:05 |

The app had already logged the split on every paint, and nobody was reading the field:

```
configuredLoc=37.40643,-122.02061   dataLoc=37.41700,-122.08900
```

## Why it was hard to see

**KNUQ read 66.2 °F at both 11:10 and 13:35.** The label was therefore *numerically correct* and only
wrong about the time, which is why it read as "stale info" rather than as obviously broken. Any
diagnosis — or test — that compared temperatures would have concluded the app was fine.

It also flapped. `dataLoc` alternated between the two sites minutes apart on the same widget
(13:05:39 correct, 13:06:42 stale, 14:04:19 correct, 14:16:16 stale), so the label oscillated between
a fresh reading and a three-hour-old one depending on which paint path ran last — the same signature
as the `-13.7°` today-column bug in `260806-today-column-stale-fragment-delta-opus.md`.

## The diagnosis

`TemperatureStateResolver.kt:127` derived the render's location from the data instead of from prefs:

```kotlin
val lat = hourlyForecasts.firstOrNull()?.locationLat ?: Double.NaN
```

That value is the centre of `ObservationSiteMerge`'s ±`MERGE_TOLERANCE_DEG` (0.01°) filter. The two
fragments are **0.068° apart in longitude**, so centring on the stale one did not down-weight the
fresh rows — **it excluded every one of them before the blend ran.** Reproduced in SQL against the
pulled database: a correctly-centred read returns 4175 rows ending 13:50; the render got 7770 rows
ending 11:15.

Two things made this a small fix rather than a redesign:

- **The same paint already knew the answer, 40 lines away.** `TemperatureViewHandler.kt:124` resolves
  the *current-temp* location as `getWidgetLocation(appWidgetId) ?: firstOrNull() ?: NaN` — prefs
  first, data as fallback. Only the blend location skipped the prefs rung. One widget paint, two
  different answers to "where am I?"
- **`resolve()` already takes `appWidgetId` and `stateManager`.** The missing rung cost no signature
  change.

### The tolerance was the wrong knob

Tempting to widen `MERGE_TOLERANCE_DEG` so both fragments merge. Wrong: its KDoc sizes it against
`distanceKm`'s error budget, and KNUQ is 2.4 km from the new site against ~5 km from the old one — a
6 km centre error makes the IDW weights meaningless. The centre was wrong, not the box.

### `Double.NaN` is a safe default for a scalar, not for a selector

The comment above the old line defended the NaN correctly *for its original consumers*: sun shading
falls back to `UNKNOWN_LOCATION`, IDW distance weights drop out. That reasoning never got revisited
when the value became a **filter centre**. A filter centred on the wrong point does not degrade
visibly — it returns a confident, complete-looking, wrong answer.

## The fix

**New** `app/.../handlers/BlendCentre.kt` — the rung order as a pure object, so it is testable without
Robolectric and so the "this is a filter centre, not just a coordinate" constraint has somewhere to
live for the next person who adds a consumer.

```kotlin
val blendCentre = BlendCentre.resolve(configuredLocation, dataDerivedLocation)
```

Configured location → coordinate carried by the rows → `NaN`. Never a stand-in coordinate.

**Accepted trade-off:** right after a move the configured site may hold fewer observations than the
one just left, so the graph draws fewer actual points until the first fetch lands. That is correct —
the alternative is another location's frozen readings labelled with the current time. The window is
short: on the measured incident the handoff and the first fetch at the new site were **eleven seconds**
apart.

**Diagnostics.** `DOMINANT_STATION` gained `locSource=` and `obsCentre=`. It already paired
`readingAgeMin` with `newestObsAgeMin` to separate "fetch stalled" from "blend named a lagging
station"; this incident was a third case — *wrong centre* — with no field naming it. Both ages were
large while fresh rows sat unreachable behind the merge box. Added `BLEND_CENTRE_DIVERGENCE` (WARN)
for when the two coordinates disagree by more than that box.

## Testing

13 new tests. **Every assertion is on a timestamp, never a temperature** — a value assertion passes
under this bug.

- `BlendCentreLocationTest` (9, pure) — the rung order, non-finite handling, the divergence signal.
  One test guards the *premise* rather than behaviour: `the two sites really are outside the merge
  box`. If `MERGE_TOLERANCE_DEG` were ever widened past 0.068°, centring on the stale fragment would
  stop excluding fresh rows and this fix's reasoning would need revisiting — a change that would
  otherwise be silent.
- `BlendCentreExcludesFreshRowsTest` (4, shared) — merge → blend → label on the measured fragments.
  **The blend reproduced the bug from first principles**: fed the same rows, it returned a dominant
  contribution dated 11:10 from one centre and 13:35 from the other, same station, same 66.2°. That
  confirmed the diagnosis independently of the SQL reconstruction.
- `CurrentTempUnificationIntegrationTest` (+2) — the paint-agreement invariant: with a configured
  location and rows carrying a different one, the blend centre must equal the current-temp centre.

**Proven to fail without the fix.** Reverting the rung in place failed *only* `blend centre follows
the configured location, not the coordinate on the rows`; the fallback test kept passing. The guard is
specific to what broke.

## The surprise

`CurrentTempUnificationIntegrationTest` uses `mockk<WidgetStateManager>(relaxed = true)`, and a
relaxed mock **fabricates** a `Pair<Object, Object>` for the newly-called `getWidgetLocation` — the
`Double` cast then threw `ClassCastException` *inside the resolver*. Both pre-existing tests now stub
the location explicitly, which is honest: the test does need to say where the widget is.

Worth carrying forward: a future test that mocks `WidgetStateManager` relaxed and calls `resolve` will
hit a cast error deep in the resolver rather than an obviously-missing stub. The other three `resolve`
call sites in tests build a real `WidgetStateManager(context)`, which returns null with no prefs and
falls through to the data rung, so they were unaffected.

## Verified on device

SM-F936U1, debug build installed 14:39:

```
DOMINANT_STATION  ... readingAgeMin=25 newestObsAgeMin=5 obsRows=8171
                      locSource=configured obsCentre=37.41682,-122.08902
                      text=knuq 68° @ 2:15 pm
headerState       ... configuredLoc=37.41682,-122.08902 dataLoc=37.41682,-122.08902
```

`dataLoc` now prints the **raw** configured coordinate rather than a row's 3 dp quantized one — the
clearest single tell that the centre no longer comes from the data. Before the fix that field read
`dataLoc=37.41700,-122.08900` against `configuredLoc=37.40643,-122.02061`.

**Caveat:** the device had already returned to the original site before the build installed, so this
confirms the mechanism and the diagnostics, **not** the moved-device case on real hardware. The
shared integration test covers that offline against the measured fragments; on-device confirmation
waits for the next move.

Also worth noting: the user observed *"it updated just now"* mid-investigation. That was the device
moving back — `configuredLoc` and `dataLoc` agreeing again — not a repair. A bug that hides itself
when the input returns to normal is worth naming as such rather than accepting as resolved.

## Not done

- **Why the hourly list sometimes held only far-site rows.** `unifyToNearestSite` runs against the
  configured location, and 314 NWS rows existed at the new site, so they should have won — which
  points at the *loader* having run with the old centre, not at the unify step. Unresolved because
  `HourlyForecastLoader`'s `center=` line is `Log.i` (logcat-only) and the evidence was already gone.
  §5 of the plan says settle this **before** deciding whether `LocationMatch.selectNearestSite` needs
  a distance ceiling — it has none today, so a frozen fragment 6 km away wins whenever it is the only
  one in the list, and that function governs every forecast read in the app.
- **Orphaned per-widget coordinates.** `weather_widget_prefs` still holds `widget_lat_346` and
  `widget_lat_353` at `37.416836` for widgets that no longer exist; `syncCompatibilityCopies` only
  updates ids currently returned by `AppWidgetManager`. Harmless today, but it is a second
  stale-coordinate source in the same file, and `LegacyDefaultLocationMigration` does scan
  `widget_lat_*` by prefix.

## Files

| File | |
|---|---|
| `app/.../handlers/BlendCentre.kt` | new — rung order as a pure object |
| `app/.../handlers/TemperatureStateResolver.kt` | centre from prefs; `locSource`/`obsCentre`; divergence warning |
| `app/src/test/.../BlendCentreLocationTest.kt` | new — 9 unit tests |
| `shared/src/test/.../BlendCentreExcludesFreshRowsTest.kt` | new — 4 integration tests |
| `app/src/test/.../CurrentTempUnificationIntegrationTest.kt` | +2 tests, +2 location stubs |
