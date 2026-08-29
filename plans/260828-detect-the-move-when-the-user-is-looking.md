# Detect the move when the user is looking, not on a battery timer

**Status:** 📋 Planned 2026-08-28
**Follows:** [`260828-remove-the-location-handoff-policy.md`](260828-remove-the-location-handoff-policy.md)
**Goal:** close the gap that plan exposed. Promotion is now immediate, but *detection* is not — and
detection is the binding constraint.

---

## 1. The gap, measured

Removing the handoff policy fixed a **30-minute display gate**. Behind it sits a detection gap of
**4 to 24 hours**, and the change made that gap the whole latency.

Arriving somewhere new, on battery, without opening the app:

| Battery | Time before the app notices you moved |
|---|---|
| >70% | **240 min** (`BatteryTier.INTERVAL_HIGH_MINUTES`) |
| 50–70% | **480 min** (`INTERVAL_MEDIUM_MINUTES`) |
| <50% | **1440 min** (`computeFetchInterval` returns null → `OFF_CHARGER_LOW_BATTERY_TICK_MINUTES`) |

Nothing else fires. Verified against the code, not assumed:

- **`ACTION_SCREEN_ON` is not registered anywhere.** `grep registerReceiver` across the app returns
  no screen/power/user-present registration, and a manifest receiver cannot receive it by design.
- **`USER_PRESENT` is registered and never delivered.** `ScreenOnReceiver` declares it in the
  manifest; its own KDoc records three days of `app_logs` across a Pixel 7 Pro and a Samsung fold
  holding **zero** `UNLOCK_REFRESH_POLICY` rows.
- **The opportunistic job runs off-charger but never resamples** — `FullSyncPipeline`'s gate
  excludes `input.currentTempOnly`, which is what an opportunistic run is.
- **`PowerConnectedJobService` fires only on the plug-in edge**, so it does nothing for an arrival
  you don't plug in for.

So on the journey this plan is about — arrive in San Francisco, pick up the phone, look at the
widget — **nothing checks the location**. The widget confidently draws Mountain View for up to four
hours.

### 1a. The stated principle is not yet delivered

> "If the user is looking at the phone they should have accurate info." — Danny, 2026-08-28

That is the premise the previous plan was accepted on. It is not true today, because nothing tells
the app the user is looking.

---

## 2. The signal that does exist

The app cannot hear about unlock. It *can* observe the moment it paints a widget for an interactive
screen — and `WidgetPaintCoordinator` already treats that as meaningful:

```kotlin
if (!isScreenInteractive()) {
    // Nothing repaints on unlock — ACTION_USER_PRESENT is manifest-declared
    // and undeliverable at targetSdk 26+ (see ScreenOnReceiver) — so without this …
    widgetStateManager.setPaintOwed(true)
```

**A paint for an interactive screen is evidence the user is looking**, arriving through a channel
that works, and the codebase already reasons about it for exactly this blind spot. Resampling there
is a passive `lastLocation` read — no GPS power-up, so the Samsung precise-location rule is
untouched.

There is a second working channel, currently unused: **`ACTION_SCREEN_ON`, registered at runtime**.
It fires the moment the display lights up — earlier than any paint, and earlier than `USER_PRESENT`,
which needs an unlock. Neither channel is sufficient alone (§3.2), but together they cover the
"picked the phone up" case from both sides.

---

## 3. The change

### 3.1 Resample is throttled by time, not by which sync happens to be running

Today resampling is coupled to sync *kind*: it happens on full syncs and nowhere else, because the
gate lists the kinds it must not run in. That coupling is accidental — the reason not to resample on
every opportunistic tick is *cost*, and cost is a rate question.

Extract `maybeResample(context, trigger)` with its own cooldown, and call it from the places that
have a reason to ask:

| Caller | Trigger | Why |
|---|---|---|
| `FullSyncPipeline` | `worker` | as today |
| `OpportunisticUpdateJobService` | `opportunistic` | the background floor, ~one cycle instead of 4–24 h |
| `WidgetPaintCoordinator` (interactive paints only) | `paint` | the user is looking |
| Runtime `ACTION_SCREEN_ON` receiver (§3.2) | `screen_on` | the display just lit up |

Suggested cooldown: **60 s**. Sized against what it protects — `awaitLastLocation` is a cached read,
and the expensive part (reverse geocoding) only runs on an actual move, which is rare. The throttle
exists to stop a burst of taps issuing a Play services call each, not to ration detection.

`candidateLocationRefresh` stays in the gate: it is the loop-breaker for the refresh a move enqueues,
and is now misnamed (there are no candidates). Rename to `locationChangeRefresh` in the same pass.

### 3.2 Register `ACTION_SCREEN_ON` at runtime

Add a fourth caller: `maybeResample(trigger = "screen_on")`, from a receiver registered in
`WeatherWidgetApp.onCreate` beside `PowerConnectedJobService.ensureScheduled`.

**It must be runtime-registered — a manifest entry would silently do nothing.** Verified in the
platform source shipped with this SDK (`android-36/android/content/Intent.java:2552`):

> You *cannot* receive this through components declared in manifests, only by explicitly registering
> for it with `Context.registerReceiver()`.

The system sends `SCREEN_ON`/`SCREEN_OFF` with `FLAG_RECEIVER_REGISTERED_ONLY`, so manifest
components are skipped by the dispatcher. No install warning, no runtime error — it just never
fires. That is a different mechanism from the API 26+ implicit-broadcast restrictions that killed
`USER_PRESENT` here, and conflating the two is how someone "fixes" the dead unlock path by adding
`SCREEN_ON` to the manifest and changes nothing.

**Coverage is partial by construction:** a runtime receiver lives and dies with the process, and this
app's process is not persistently alive. So it fires only when the process already happens to be up.
That is the reason it is an addition rather than the mechanism:

| Trigger | Covers | Silent when |
|---|---|---|
| `screen_on` (runtime) | user wakes the phone | process not alive |
| `paint` (interactive) | widget actually drawn | screen off |
| `opportunistic` | background floor | — |
| `power_connected` | plug-in edge | already charging |
| `worker` | periodic | 4–24 h (§1) |

None is sufficient; together they make "user is looking at four-hour-old data" hard to reach. The
layering is the design, not redundancy to be trimmed later.

**Frequency is the whole risk.** `SCREEN_ON` fires dozens of times a day, so this caller is only safe
behind §3.1's cooldown — it should not be added before that lands.

### 3.3 Rename `ScreenOnReceiver`, and say why in its KDoc

The class handles `ACTION_POWER_CONNECTED`, `ACTION_POWER_DISCONNECTED` and `USER_PRESENT`. It has
never handled screen-on and, as a manifest component, never could. Rename to `PowerAndUnlockReceiver`
(or fold what survives into `PowerConnectedJobService`'s orbit) and record both facts in the KDoc:
`SCREEN_ON` is runtime-only, and `USER_PRESENT` is measured-undelivered on this app's devices.

Worth keeping the distinction honest in the note: the `SCREEN_ON` rule is **verified in platform
source**; the `USER_PRESENT` behaviour is **measured** (three days of `app_logs` across a Pixel 7 Pro
and a Samsung fold, zero `UNLOCK_REFRESH_POLICY` rows) rather than traced to a documented allowlist.
Different grades of evidence, and the KDoc should not flatten them.

### 3.4 Ordering, and what the user sees

Resample *before* the paint's data is used, so a move applies within the same paint where possible.
Where it cannot (bundle already loaded), the move enqueues a refresh and the following paint is
correct — one repaint later, not one cadence later.

**Accepted:** the first paint after arriving may still show the old location for a second or two.
That is the honest floor and is orders of magnitude better than four hours.

### 3.5 Correct the interval table in CLAUDE.md

The "Data Fetch Intervals" table reads 60 / 120 / 240 / 480. `BatteryTier` actually gives
**60 charging, 240 above 70%, 480 for 50–70%, and 1440 below 50%**. The documented table is wrong in
the flattering direction, which is how the 4-hour gap stayed invisible. Fix the numbers and name the
constants so the next reader can check.

---

## 4. Testing

### 4.1 `PowerConnectedJobService` — the plug-in chain

Never had a test. It has a `resampleLocation` seam already (mirroring `ScreenOnReceiver`'s), so:

- `onStartJob` calls resample with `trigger="power_connected"`, and finishes the job afterwards.
- `ensureScheduled` arms while discharging; no-ops when already armed; no-ops when already charging.
  That last one is what made the arming invisible during the 2026-08-28 emulator run — worth pinning
  so it is understood rather than rediscovered.

Not testable in a unit test: that JobScheduler *delivers* on the charging edge. Only a real plug-in
shows that, and the note in `notes/260807-gps_resample_seam_breadcrumb.md` should record that the
console command `adb emu power ac on` (**not** `dumpsys battery set ac 1`) is what satisfies the
constraint on an emulator.

### 4.2 The throttle

- Two resample requests inside the cooldown produce one `lastLocation` read.
- A request after the cooldown produces a second.
- The cooldown does **not** suppress the *application* of a move that has already been read — it
  gates the read, never the result.

### 4.3 Interactive paint

- Screen interactive → paint requests a resample.
- Screen off → no resample, and the existing `paintOwed` debt still recorded.
- A move detected during paint enqueues a refresh (assert the work request, as
  `WeatherWidgetProviderEnqueuePolicyTest` does).

### 4.4 The screen-on receiver

- Registered on process start; unregistered cleanly (no leak) if the app ever tears down.
- Receiving `ACTION_SCREEN_ON` requests a resample with `trigger="screen_on"`.
- **A guard test that it is NOT in the manifest.** Assert no `<receiver>` filter declares
  `android.intent.action.SCREEN_ON` — the failure this prevents is silent, and a manifest entry looks
  correct in review. `HourlyProximityQueryAllowlistTest` is the precedent for an architecture test of
  this shape.

### 4.5 The opportunistic path

An opportunistic run resamples. This is the assertion that closes the 4-hour gap, so it should name
that in its failure message.

---

## 5. Risks

- **Paint frequency.** Paints are far more common than syncs — every tap, zoom and day-click. The
  cooldown is the only thing standing between that and a Play services call per interaction; §4.2
  exists because of it.
- **Screen-interactive is not the same as looking at the widget.** A phone awake in a pocket paints
  too. Acceptable: the cost of a wasted cached read is negligible, and the alternative is missing the
  case that matters.
- **More frequent detection means more frequent site changes**, which is the input to three bugs
  found on 2026-08-28 (blend centre, unbounded stitcher fallback, unfiltered history rows). Two are
  fixed; `LocationMatch.selectNearestSite` still has no distance ceiling. **Watch that first** if
  anything odd appears after this ships.

---

## 6. Sequence

1. `PowerConnectedJobService` test (§4.1) — independent, and documents the arming rule before
   anything changes.
2. CLAUDE.md interval table (§3.3) — pure documentation, no risk.
3. Extract `maybeResample` with the cooldown, keeping today's single caller (§3.1) — a refactor with
   no behaviour change, tested by §4.2.
4. Add the opportunistic caller (§4.4). Measurable on its own: the background floor drops from hours
   to one cycle.
5. Rename `ScreenOnReceiver` and correct its KDoc (§3.3) — no behaviour change, and it stops the
   next reader from "fixing" the dead path the wrong way.
6. Add the runtime `SCREEN_ON` receiver (§3.2) plus its manifest guard test (§4.4).
7. Add the interactive-paint caller (§3.4/§4.3). Last, because it is the highest-frequency caller and
   benefits from the throttle being proven by everything above it.
