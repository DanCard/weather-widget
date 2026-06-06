# Desktop Hourly (Temperature) Graph Parity with Android

**Date:** 2026-06-06
**Follows:** `260606-desktop-daily-view-header-parity.md` (daily done, incl. scaling).
**Goal:** Bring the desktop hourly/temperature view to visual + interaction parity with the Android
widget's zoomed temperature graph, reusing the daily view's `uiScale` pattern.

---

## Side-by-side findings (desktop `TemperatureGraph.kt` vs Android `TemperatureGraphRenderer.kt`)

Screenshots captured 2026-06-06: desktop `/tmp/desktop_temp.jpg`, emulator `/tmp/emu_temp.jpg`,
and **Samsung Z Fold3 (real device) `/tmp/samsung_temp.jpg` — use this as the canonical reference**
(the emulator shot is glass-blurred; the Samsung one is crisp). Same location/data on all.

The Samsung header (hourly view) shows the exact target: `☀ 55.5° +1.4` then a **compact view-switch
icon row `☁ 🌡 🏠 📈`** then `NWS ⚙` — no location text, no `W/H/D` chips. The graph shows a bold
orange **NOW** line + label, `77°` max / `55.4°` pink current / `54°` min / `67°` end labels, and
hour ticks with day-night icons every 4h (`6p☀ 10p🌙 2a🌙 6a☀ …`).

| Aspect | Android | Desktop today | Gap |
|---|---|---|---|
| Element scaling | `bitmapScale`/`labelScale` applied to every paint, dot, spacing (`TemperatureGraphRenderer.kt:161,1080,1203`) | **All sizes fixed** — `fontSize 9–11.sp`, `iconSize 18.dp`, stroke `3f`, dot radius `4.5f/2.5f`, bottom strip at `h-38f`/`h-14f` | **Tiny on large windows** (same bug daily had) |
| NOW indicator | Bold dashed line, 60% height, **"NOW" text label** (`GraphRenderUtils.drawNowIndicator`, `:386`) | Faint white line (alpha 0.36), **no label** (`TemperatureGraph.kt:286-296`) | Weak, unlabeled |
| "Last updated" | **Fetch dot + age label** "(12m ago)" at observation end (`drawFetchDot`, `:1120`,`staleness.ageLabel`) | Plain white now-dot, no age label | **Missing** (user explicitly wants this) |
| Current temp label | Pink **actual** value at now-dot (`55.4°`) | Forecast temp `forecastTemps[nowIdx]`, white, only if not hi/lo (`:304`) | Wrong source/prominence |
| Hour axis | Hour text + **day/night sun/moon tinted icons**, even spacing (`HOUR_LABEL_SPACING_DP`) | Condition icons tinted + hour text, interval logic differs (`:374-411`) | Icons faint; spacing irregular |
| End value label | Right-edge forecast value (`67°`) | Not shown (only hi/lo/now) | Missing |
| Header (hourly mode) | Compact: source + gear + view-switch icons | **Still the "mess"**: `Phone GPS (…)` label + 🌡 + ☁ + `W H D` chips (`Main.kt:844-892`) | Header parity not yet applied to hourly |

**Already shared / correct:** `ActualTemperatureSeriesBuilder` (actual vs forecast series), temp→color
model, Catmull-Rom smoothing, gradient fill, ghost line, cloud/precip overlays. Curve fidelity is
largely fine — this plan is about **scale, NOW, fetch-dot, hour axis, and the header**.

---

## Implementation Steps

### 1. Scale every element with the window (highest impact)
**File:** `desktop/.../TemperatureGraph.kt`

- Add a `scale: Float = 1f` param (pass the shared `uiScale` from `WidgetPopup`, exactly like
  `DailyForecastGraph` — `Main.kt` already computes it in the top-level `BoxWithConstraints`).
- Multiply through all fixed sizes: line strokes (`3f`), now-dot radii (`4.5f/2.5f`), bottom-strip
  icon (`18.dp`), all `fontSize` (hour `9.sp`, peak `11.sp`, day `10.sp`), dash intervals, and the
  fixed bottom offsets (`h-44f`, `h-38f`, `h-14f`) → scaled reserves.
- Mirror the daily view: small top reserve, a **scaled bottom band** for the hour icons + hour
  labels so they don't collide with the curve at 2–3×.
- Wire `scale = uiScale` at all three call sites in `Main.kt` (`TemperatureGraph`,
  `CloudCoverGraph`, `PrecipitationGraph` — do the same for those two for consistency, or scope to
  TemperatureGraph first and follow up).

### 2. NOW indicator parity
**File:** `desktop/.../TemperatureGraph.kt:286-296`

- Make the line prominent: brighter/dashed, ~60% of graph height centered (mirror
  `GraphRenderUtils.drawNowIndicator`: `lineHeight = graphHeight*0.6f`, centered).
- Add a **"NOW" text label** at the top of the line (scaled font), with the same collision-avoid
  placement Android uses (`computeNowLabelBounds`) — keep it from colliding with day/peak labels.
- Keep the colored target dot at the curve.

### 3. Fetch-dot "last updated" age label (the deferred staleness feature)
**File:** `desktop/.../TemperatureGraph.kt` (around the now/actual-end handling, `:276-296`)

- At the end of the **actual** (pink) line — anchored at `currentObservedAt` / `transitionMs`
  (already computed, `:169`) — draw a fetch dot and, below/beside it, an age label
  "(Nm ago)" / "(Nh ago)". Port Android's `formatAgeLabel` + `resolveFetchDotLayout`
  (`TemperatureGraphRenderer.kt:1075-1106`) — the inputs (`observedAt`, last observed temp) are all
  present desktop-side.
- **Gate the age label to stale-only.** On the Samsung shot (fresh data) the fetch dot is present
  at `55.4°` but there is **no "(Xm ago)" text** — Android only draws the age label once the
  observation is meaningfully old (`formatAgeLabel` returns null when fresh, `:1094-1095`). So:
  always draw the fetch dot, draw the age label only when stale. This is the in-graph "last updated"
  the user asked for — it lives here in hourly, **not** on the daily view.

### 4. Current-temp label at the now-dot
**File:** `desktop/.../TemperatureGraph.kt:298-306`

- Show the **actual** current temp (pink, `currentTemp`) at the now-dot prominently, like Android's
  `55.4°`, instead of (or in addition to) the forecast value. Reconcile with the fetch-dot label so
  they don't double up when the now-dot and fetch-dot coincide (Android handles this via
  `anchoredToFetchDot`, `:290`).

### 5. Hour-axis labels + day/night icons + regular spacing
**File:** `desktop/.../TemperatureGraph.kt:374-411`

- Match Android's even spacing (`HOUR_LABEL_SPACING_DP`, scaled) and the sun/moon **day-night**
  tinting (`SunPositionUtils` already used at `:387`). Verify the WIDE/NARROW intervals
  (`WIDE_LABEL_INTERVAL=4`, `:84`) produce the regular 4-hour ticks Android shows, not the current
  irregular `12p,1p,5p,9p…`.
- Add the right-edge end value label (`67°` equivalent) if straightforward.

### 6. Hourly header parity (apply the daily header treatment to hourly mode)
**File:** `desktop/.../Main.kt:844-892` (the `if (isHourly)` secondary row)

- This is the deferred half of the daily header work. Remove the `Phone GPS (…)` location label
  and the desktop-only `W H D` / zoom `ViewModeChip`s from the header; relocate per the daily plan
  (location → Settings; daily↔hourly via temp-tap, already wired).
- Replace the `W H D` chips with Android's **compact view-switch icon row** seen on the Samsung
  device: `☁` cloud-cover, `🌡` temperature, `🏠` home/daily, `📈` precipitation. Desktop already
  has the underlying view modes (`CLOUD_COVER`, `HOURLY`/temperature, `DAILY`, `PRECIPITATION`) and
  partial icons (🌡/☁) — formalize them into the 4-icon row matching the device. Zoom (Wide/Narrow)
  has no Android header equivalent — drop it from the header (move to Settings or a tap region).
- Scale these header elements with `uiScale` (the daily change already scaled the shared primary
  row; finish the secondary row here).

---

## Sequencing / risk notes
- **Do step 1 (scaling) first** — it's the dominant visible problem and de-risks the rest (every
  later element gets positioned in scaled space).
- Steps 2–5 are pure `TemperatureGraph.kt` Canvas work; step 6 touches `Main.kt` header and Settings
  (coordinate with the daily header relocations so location/observations aren't stranded).
- **Reuse vs reimplement:** Android's `GraphRenderUtils`/`TemperatureGraphRenderer` are
  `android.graphics`-based and live in `:app` — not shareable with Compose desktop. Port the
  *logic* (NOW label bounds, `formatAgeLabel`, fetch-dot layout, hour spacing) into the Compose
  `DrawScope`; keep using the already-shared `ActualTemperatureSeriesBuilder`.
- Multiple adb devices attached — always `-s emulator-5554`; never `connectedDebugAndroidTest`.
- After each compiling change: `scripts/build-exe-and-restart.sh`, screenshot via
  `import -window <id>`, compare to emulator (`adb -s emulator-5554 exec-out screencap`, PNG→JPG).

## Out of scope
- Cloud-cover / precipitation graph parity (separate; but step 1 scaling should extend to them).
- Android-side changes — Android is the reference.

## Minor / optional (noted, not required)
- **Emulator header-row top clipping:** the Android widget's top header row is slightly clipped at
  the very top edge. User: "can be reduced or left as is." Low priority; if addressed, it's an
  Android-side top-padding/inset tweak in the widget header layout, independent of this desktop
  work.
