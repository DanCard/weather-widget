# Plan: Stay on Daily View with Message When No Hourly Data

## Context

Clicking a far-future day column (e.g. Tuesday next week, 7 days out) in the desktop daily view switches to the hourly temperature graph, which renders as a black screen with only a header. The cause is two-fold:

1. `dayClickConfig()` in Main.kt switches to hourly view unconditionally — no check for whether data exists for the clicked date.
2. `HourlyGraphInput.kt`'s `rememberHourlyGraphSetup` has an `ifEmpty` fallback: if there are zero hourly points in the view window, it falls back to ALL hourly data from any day. Those off-day points are mapped off-screen by `xAtTime`, so the visible Tuesday window is entirely black.

The first layer (`handleDayClick` guard using `NoHourlyChecker`) is already implemented and works on the first click. The user is still seeing black because **the app hasn't been rebuilt yet** (the fix was committed after the last `buildStart-desktop.sh` run) AND because a second-layer defense is needed in case the guard is bypassed.

## Implementation

### Already done (needs rebuild to deploy)
- `shared/…/NoHourlyChecker.kt` — pure `hasHourlyForDay`, `formatDayLabel`, `formatEndLabel`, `lastHourlyEndLabel`, `buildMessage`
- `desktop/…/Main.kt` — `handleDayClick` checks `NoHourlyChecker.hasHourlyForDay` before switching to hourly; shows `noHourlyMessage` overlay on daily view; kicks `onNeedForecastExtension` refresh

### New: second-layer defense in the hourly Box

In the `isHourly` Box in `Main.kt` (around line 769), add a guard that catches the `ifEmpty` fallback case:

**Where:** Inside the `Box(modifier = Modifier.fillMaxWidth().weight(1f).testTag("hourly_temperature_surface"))`, before the graphs are rendered.

**Logic:**
```
val hourMs = 3_600_000L
val nowMs = System.currentTimeMillis()
val backH = DesktopGraphUtils.backHoursFor(config.zoomFactor)
val fwdH = DesktopGraphUtils.forwardHoursFor(config.zoomFactor)
// Mirror rememberHourlyGraphSetup's window (including its 1h left-pad)
val winStart = nowMs + (config.hourlyOffset - backH) * hourMs - hourMs
val winEnd   = nowMs + (config.hourlyOffset + fwdH)  * hourMs
val srcIds   = config.visibleSources.toSet()
val hasDataInWindow = snapshot.hourly.count {
    it.dateTime in winStart..winEnd &&
    (it.source == null || it.source in srcIds || it.source == NoHourlyChecker.GENERIC_GAP_SOURCE)
} >= 2

if (!hasDataInWindow) {
    SideEffect {
        val centerDate = Instant.ofEpochMilli(nowMs + config.hourlyOffset * hourMs)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        noHourlyMessage = NoHourlyChecker.buildMessage(
            NoHourlyChecker.formatDayLabel(centerDate),
            NoHourlyChecker.lastHourlyEndLabel(snapshot.hourly, srcIds),
        )
        onUpdateConfig(config.copy(viewMode = ViewMode.DAILY))
    }
    return@Box   // skip rendering graphs
}
```

`SideEffect` fires synchronously after the composition frame, so the user sees at most one blank frame before returning to daily view. `noHourlyMessage` is already in scope (declared before `val isHourly`).

## Files to modify

- `desktop/src/main/kotlin/com/weatherwidget/desktop/Main.kt` — add second-layer guard inside the `isHourly` Box (single insertion point)

## Verification

1. Run `scripts/buildStart-desktop.sh`
2. Click Tuesday next week → should stay on daily view with message "No hourly forecast for Tue Jul 7 — data ends Mon Jul 6 at 4 PM"
3. Click Tuesday again (no data yet) → same message reappears (no black screen)
4. Wait for refresh / or manually wait for Open-Meteo to be fetched → click Tuesday → hourly view renders correctly
