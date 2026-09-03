# Desktop `Main.kt` structural refactor

**Date:** 2026-09-03

**Status:** Completed

## Evidence and root cause

`desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt` is 2,033 lines. Its size is a symptom,
not the root problem. Three independently changing responsibilities currently share one file:

1. `runApp` spans lines 231-1092 and combines dependency construction, config persistence,
   daemon/UI notification, cache reloads, resume/file-watch effects, refresh jobs, application
   shutdown, and every desktop window host.
2. `WidgetPopup` spans lines 1104-1572 and combines popup interaction state, daily/hourly routing,
   history loading, keyboard navigation, status/error overlays, and graph selection.
3. `WidgetHeader` and its adjacent rendering helpers span lines 1575-2033 and combine header-model
   resolution with Compose layout and header actions.

The current architecture document already identifies `Main.kt` as a god file, but its recorded
1,825-line count has drifted below the live 2,033 lines. The code remains functional and tested;
the problem is ownership and auditability. Changes to one window or one header decision require
editing the same compilation unit as process lifecycle and refresh orchestration.

## Scope and ownership

### 1. Extract desktop navigation policy

Move `dayClickRoutingPrecip` and `dayClickConfig` into a focused desktop navigation file. They stay
thin adapters over shared `DayClickResolver` and `ZoomStage`; no Android-equivalent policy is
duplicated.

### 2. Consolidate desktop config-save policy

Move `flushSettingsDraft` and `resnapNarrowZoomAfterSpanChange` next to the existing
`mergeNonSettingsSave` config policy. Extract the source-aware save/rebase decision from the
`runApp` composable into a pure result-producing policy so tests can cover which fields are accepted,
merged away, and resnapped without Compose or disk I/O. Keep actual persistence, logging, and daemon
notification at the application boundary.

### 3. Extract popup composition

Move `WidgetPopup` into its own file. It owns popup-local state, graph switching, navigation,
day-click behavior, and status overlays. It must not construct repositories, own long-lived refresh
jobs, or persist config directly; those remain callback dependencies from the application root.

### 4. Extract header composition and state resolution

Move `WidgetHeader`, its navigation arrow, and its small render helpers into a header-focused file.
Keep platform-neutral precipitation, source, navigation, and scaling decisions in `:shared` where
they already exist. Keep Compose layout and desktop base sizes in `:desktop`. Resolve derived header
data before rendering wherever that removes repeated inline policy.

### 5. Extract window hosts from application orchestration

Move popup/settings/location window wrappers into focused host composables that own geometry,
keyboard handling, and window-specific callbacks. `runApp` remains the composition root and owner
of application-lifetime resources, but should read as ordered wiring rather than contain each
window's implementation.

### 6. Refresh architecture documentation

Update `arch/ARCHITECTURE.md` with current ownership and live file sizes after extraction. Avoid a
static claim that `Main.kt` is the largest god file once that is no longer true.

## Cross-platform boundary

Android already separates application/widget lifecycle, daily/hourly handlers, header resolution,
and rendering into dedicated files. This refactor brings desktop structure closer to that shape.
Only framework-neutral calculations belong in `:shared`; Android `RemoteViews`, Compose window
state, AWT lifecycle, and platform persistence remain platform-specific adapters.

## Behavior invariants

1. The daemon/UI two-process split and socket/file notification directions do not change.
2. Closing the weather window hides/exits according to the existing ephemeral UI-process rules;
   tray `Quit` still closes long-lived clients before exit.
3. Config saves retain the existing stale-draft merge exceptions for popup weather-source changes,
   location selection, and observations actual-provider changes.
4. Settings close still flushes only settings-owned fields onto the latest persisted config.
5. Changing the narrow zoom span still resnaps an already-open NARROW graph and leaves other zoom
   stages unchanged.
6. Popup keyboard, day-click, source-toggle, history, error-banner, and window-geometry behavior do
   not change.
7. Android production code is unchanged unless inspection finds a genuinely duplicated
   platform-neutral rule that belongs in `:shared`.

## Verification

1. Preserve and run existing focused tests for day-click routing, settings-draft merging, narrow
   zoom resnapping, popup startup, header sizing, and no-wrap rendering.
2. Add pure tests for the extracted source-aware config-save decision and architecture checks that
   keep `Main.kt` free of popup/header implementations.
3. Compile after each extraction with `:desktop:compileKotlin` and run focused desktop tests.
4. Run `./gradlew :desktop:test`, `./gradlew test`, `ktlintCheck`, `assembleDebug`, and
   `:desktop:createDistributable`.
5. Launch the desktop UI against the existing config, verify popup/header rendering, source and
   view controls, resize/close behavior, and inspect logs for uncaught exceptions. Do not alter the
   Android widget state for this desktop-only structural change.
6. Run `git diff --check` and audit that the final changes are structural except for explicit
   dead-code/compiler-warning cleanup covered by tests.

## Out of scope

This pass does not redesign the established daemon/UI IPC, split persistent config files by owner,
or decompose `DaemonProcess`. Those are separate behavioral migrations identified in
`plans/260813-code-review-desktop-architecture.md`; mixing them into a UI ownership refactor would
make lifecycle regressions harder to isolate.
