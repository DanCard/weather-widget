# Configurable Narrow-Zoom Hour Span (4–8h) + New-Install Default 5h

Date: 2026-08-09
Status: **Awaiting approval**
Scope: Android `:app` (primary). Shared `:shared` geometry resolver. Desktop noted (no code change needed).

## 1. Summary

Make the span of the NARROW ("zoomed-in") hourly graph configurable from **4–8 total hours**
instead of the current fixed 4h (`NARROW` = 2h back + 2h forward). Add a settings-screen control
placed **above the weather data sources** section. New installs default to **5 hours**.

Navigation jump rule (per user):
- NARROW span 4–5h → left/right scrolls **1h** per tap.
- NARROW span 6, 7, 8h → left/right scrolls **2h** per tap.
- WIDE (jump 6h) and THREE_DAY (jump 12h) are unchanged.

## 2. Structural design: decouple zoom *identity* from zoom *geometry*

The current model bakes the narrow span into the shared `ZoomStage` enum
(`shared/.../graph/ZoomStage.kt`), which is persisted per-widget by **ordinal/name** (declaration
order is load-bearing) and consumed read-only everywhere. Hardcoding "5h" or mutating the enum
would be the *simplest* fix but is the wrong structure: the enum is identity, not config.

**Sound approach:** split the two concerns.

1. **`ZoomStage` stays the persisted identity** (WIDE / NARROW / THREE_DAY). Declaration order and
   `.next()` cycle untouched; all `zoom == ZoomLevel.NARROW` style branches keep working.
2. **New resolved value type `ZoomGeometry`** in `:shared` carrying the *effective* window that the
   pipeline renders and navigates by: `backHours`, `forwardHours`, `navJump`, `labelInterval`,
   `smoothIterations`, `stage`, `totalSpanHours`, plus `isNarrow`/`isWide`/`isThreeDay` helpers.
3. **One pure resolution function** — the single source of truth for geometry — in shared:
   `ZoomStage.resolveGeometry(narrowSpanHours: Int): ZoomGeometry`. WIDE/THREE_DAY return their
   fixed geometry; NARROW derives from the configured span. Android passes the app-wide span pref in;
   desktop can adopt the same function later without drift.
4. **The narrow span is an app-wide display preference** (consistent with `useCelsius`,
   `personalStationDiscountPercent`, the today-overlay toggles — all in `WeatherDisplayPreferences`),
   not a per-widget value.
5. **Thread `ZoomGeometry` through the render/navigation pipeline.** Helpers that consume the window
   (query sizing, hour-data building, touch-zone mapping, center-time resolution, repaint gating)
   take `ZoomGeometry` instead of reading hardcoded enum fields. Identity comparisons read
   `geometry.stage` / the `isNarrow` helpers.

Rationale for why this is structural rather than minimal:
- A single resolver keeps Android and desktop provably consistent (the enum is shared).
- No hidden mutable global read inside `ZoomStage` (which would break shared purity / desktop).
- The settings value and the geometry rule are each defined in exactly one place.
- Existing per-widget persistence (stage name) is untouched, so no migration of saved widget state.

## 3. Geometry derivation rules (in `ZoomStage.resolveGeometry`)

Given configured narrow span `S` (coerced to 4..8):

- `forwardHours = S / 2` (integer floor)
- `backHours = S - forwardHours`  → odd spans lean one hour backward (more history, less future)
  - 4 → 2/2, 5 → 3/2, 6 → 3/3, 7 → 4/3, 8 → 4/4
- `navJump = if (S <= 5) 1 else 2`
- `labelInterval = 1`, `smoothIterations = 1` (unchanged from current NARROW)
- `stage = NARROW`, `totalSpanHours = S`

New shared constants in `ZoomStage`:
`NARROW_MIN_SPAN_HOURS = 4`, `NARROW_MAX_SPAN_HOURS = 8`, `NARROW_DEFAULT_SPAN_HOURS = 5`.

The enum's own `NARROW(back=2, fwd=2, navJump=1)` values remain as the legacy/backward-compat
default geometry (used by desktop's `zoomFactorForStage`, unit tests, and anything not yet resolved).
`resolveGeometry` is the canonical production geometry.

## 4. File-by-file changes

### Shared (`:shared`)
- `shared/.../graph/ZoomStage.kt`
  - Add `ZoomGeometry` data class (same file or new `ZoomGeometry.kt`).
  - Add `ZoomStage.resolveGeometry(narrowSpanHours: Int = NARROW_DEFAULT_SPAN_HOURS): ZoomGeometry`.
  - Add span constants + nav-jump rule.
  - Keep enum declaration order and all existing fields.

### Android app preference layer
- `app/.../widget/WeatherDisplayPreferences.kt`
  - `narrowZoomSpanHours()` → `prefs.getInt(KEY_NARROW_ZOOM_SPAN_HOURS, NARROW_DEFAULT_SPAN_HOURS).coerceIn(4,8)`
  - `setNarrowZoomSpanHours(hours)` (coerced 4..8)
  - New key `"narrow_zoom_span_hours"`. Default 5 → applies to new installs (and to existing
    installs that never set the pref, which is the desired "default").
- `app/.../widget/WidgetStateManager.kt`
  - Expose `getNarrowZoomSpanHours()` / `setNarrowZoomSpanHours()`.
  - Add `getZoomGeometry(widgetId): ZoomGeometry = getZoomLevel(widgetId).resolveGeometry(narrowZoomSpanHours())`.
  - `getNavJump(widgetId)` → `getZoomGeometry(widgetId).navJump`.
  - `resolveHourlyCenterTime(...)` and `navigateHourlyLeft/Right(...)`: resolve geometry and pass the
    effective values down (see store change).

### Persistence / navigation store
- `app/.../widget/WidgetPresentationStateStore.kt`
  - `navigateHourly(widgetId, direction, navJump)` — the store is a pure prefs store with no access
    to app-wide display prefs, so the resolved jump is passed in from `WidgetStateManager`.
  - `resolveHourlyCenterTime(widgetId, now, backHours, forwardHours)` — the `includesNow` window check
    (`offset in -forward..back`) uses the resolved geometry instead of enum fields.

### Render pipeline — consume `ZoomGeometry`
These currently read `zoom.backHours/forwardHours/totalSpanHours/labelInterval/smoothIterations` or
pass a bare `ZoomLevel` into window math. Change param types to `ZoomGeometry` (or resolve via
`stateManager.getZoomGeometry`) and keep identity branches via `geometry.isNarrow` etc.

- `app/.../widget/WidgetRenderer.kt` — center-time resolution + logging (lines ~160–169).
- `app/.../widget/handlers/TemperatureViewHandler.kt` — now-in-window check, repaint-gate span (87–126).
- `app/.../widget/handlers/TemperatureStateResolver.kt` — resolve geometry once (line ~109), pass to
  `loadGraphHours` / `buildHourDataResult`; `TemperatureWidgetState.zoom` becomes `ZoomGeometry`
  (fields built at lines ~367, 669, 704).
- `app/.../widget/handlers/TemperatureHourDataBuilder.kt` — `buildHourDataResult` takes `ZoomGeometry`
  (back/forward, label cadence, `dateMode = zoom.isThreeDay`).
- `app/.../widget/handlers/GraphDataLoader.kt` — `buildGraphQueryWindow` / `loadGraphWindowHourlyForecasts`
  take `ZoomGeometry`.
- `app/.../widget/handlers/PrecipViewHandler.kt` — gating span, `computePrecipGraphWindow`, label
  cadence, smoothing (85–88, 400–444, 509–593).
- `app/.../widget/handlers/CloudCoverViewHandler.kt` — gating span, window keys, label spacing,
  smoothing (51–89, 432, 441, 546–570).
- `app/.../widget/HourlyTouchZoneMapper.kt` — `zoneIndexToOffset(zoneIndex, offset, geometry)`.
- `app/.../widget/handlers/TemperatureTouchTargets.kt` — `setupZoomTapZones` takes `ZoomGeometry`.
- `app/.../widget/handlers/HourlyBottomZoneHelper.kt` — `resolveZoneAction` / `setup` take `ZoomGeometry`.
- `app/.../widget/handlers/TemperatureViewBinder.kt` — passes `state.zoom` (now `ZoomGeometry`).
- `app/.../widget/handlers/TemperatureWidgetState.kt` — `zoom: ZoomLevel` → `ZoomGeometry`.
- `app/.../widget/handlers/GraphInteractionRenderer.kt` — zoom used for logging/cycle only; adapt if it
  reads geometry fields (otherwise unchanged).

### Settings UI (place above weather data sources)
- `app/.../res/layout/activity_settings.xml`
  - Insert a new "Hourly Zoom" section between the Daily View – Today Column section (ends ~line 197)
    and the `api_sources_title` header (~line 199): a title, description, a **SeekBar 4–8**, a live
    value label ("5 hours"), and a hint line for the scroll rule ("Scrolls 1h per tap" for 4–5h,
    "Scrolls 2h per tap" for 6–8h) that updates with the value.
- `app/.../ui/SettingsActivity.kt`
  - `setupNarrowZoomSpan()`: initial value from `widgetStateManager.getNarrowZoomSpanHours()`, on
    change `setNarrowZoomSpanHours(...)` + update hint + `repaintWidgets()` (existing broadcast helper).
- `app/.../res/values/strings.xml` — new strings for the section title/description/hint.

### Desktop parity (note)
Per AGENTS.md, desktop should stay in sync where a feature is shared. Desktop already has a
**continuous** zoom wheel covering any 4–8h span, and its NARROW stage maps to `zoomFactor 0.0`
(4h) via `DesktopGraphUtils.zoomFactorForStage`. Because the resolver lives in `:shared`, desktop can
adopt `resolveGeometry` for its stage-snap later; **no desktop code change is required** for this
feature. This plan does not add a desktop settings UI (the request is Android's settings screen).

## 5. Tests

- **New shared unit tests** for `ZoomStage.resolveGeometry`:
  - 4→2/2/jump1, 5→3/2/jump1, 6→3/3/jump2, 7→4/3/jump2, 8→4/4/jump2.
  - Clamping (<4 and >8).
  - WIDE/THREE_DAY unaffected by the span.
- **New app unit tests**:
  - `WeatherDisplayPreferences.narrowZoomSpanHours()` default is 5; setter clamps 4..8.
  - `WidgetStateManager.getZoomGeometry` reflects the configured span; `getNavJump` returns 1 at
    span ≤5 and 2 at span ≥6.
  - `WidgetPresentationStateStore.navigateHourly` applies the passed-in resolved jump.
- **Update existing tests** that assert the NARROW window/hours or read enum geometry directly:
  - `TemperatureViewHandlerActualsTest` (`NARROW should cover exactly 4 hours` → reworked to resolve
    via default span 5, or assert on the legacy enum default explicitly).
  - `GraphQueryWindowCoversBlendContextTest`, `HourlyBottomZoneHelperTest`,
    `TemperatureTouchRoutingRoboTest`, `HourlyZoomCenteringRoboTest`, `PrecipViewHandlerTest` — pass a
    `ZoomGeometry` where signatures change (mostly `zoom = ZoomLevel.WIDE` → `ZoomLevel.WIDE.resolveGeometry()`).
  - Keep default-span behavior covered so the 5h default is exercised end-to-end.
- Run `./gradlew :app:testDebugUnitTest` and `./gradlew :shared:test` (and the duration-bucket
  validation tasks) before declaring done. Instrumented/emulator verification is optional for this
  change (pure geometry + settings wiring), but a manual emulator pass to confirm the seekbar, hint
  text, and repaint is recommended.

## 6. Verification steps

1. `./gradlew :app:testDebugUnitTest :shared:test`.
2. Install on emulator; confirm the new section appears **above** the API sources list.
3. Default shows **5 hours**; seekbar spans 4–8; hint toggles 1h↔2h at the 5/6 boundary.
4. In NARROW zoom, confirm the visible window matches the chosen span and nav arrows jump 1h (4–5)
   or 2h (6–8).
5. Confirm WIDE/THREE_DAY behavior and persistence are unchanged; per-widget zoom state still cycles.

## 7. Risks / notes

- **Blast radius:** the render-pipeline signature changes are mechanical but touch ~12 files; the
  `TemperatureWidgetState.zoom` type change ripples into the binders. Mitigated by keeping
  `ZoomGeometry` field names identical to the enum's (`.backHours`, `.navJump`, ...) so most diffs are
  type-only.
- **Default 5 vs legacy enum 4:** production resolves through the pref (5), while raw `ZoomStage.NARROW`
  still reads 4 in tests/desktop. This is intentional (see §3) and documented in the enum.
- **Odd spans lean backward** (5→3/2, 7→4/3) so more history is visible; deliberate choice per user.
- No DB schema change; no WorkManager policy change; no per-widget migration needed.
