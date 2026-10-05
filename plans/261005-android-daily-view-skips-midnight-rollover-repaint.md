# Android daily view ignores the midnight rollover repaint

## Symptom

At midnight, with the screen on, the Android daily widget keeps yesterday as "Today" (Pixel and
both emulators). Desktop shifts the dates at 00:00.

## Evidence (emulator-5556, read-only copy of app_logs, night of 10-04 → 10-05, plugged, interactive)

```
23:59:18 WIDGET_PAINT widget=7/2 caller=DAILY origin=UI_ONLY state=skipped_ui_only
00:01:03 WIDGET_PAINT widget=2/7 caller=DAILY origin=UI_ONLY state=skipped_ui_only   ← the rollover alarm
00:03:01 WIDGET_PAINT ... origin=PROVIDER_ON_UPDATE state=data push=full            ← my app install
```

## Root cause

Two features conflict:

- `bfb57a6f` (2026-10-02): `UIUpdateIntervalStrategy` caps the UI alarm at
  `millisUntilNextMidnight`. The alarm fires on time and enqueues a **UI-only** repaint.
- `WidgetRenderer.shouldSkipDailyUiOnlyRepaint(uiOnly, alreadyPaintedThisProcess)` skips
  **every** UI-only daily repaint once the widget has a graph painted this process. It's a
  performance guard: a daily rebuild is expensive and nothing in the daily view moves minute to
  minute. Its state is `fullyPaintedDailyWidgetIds: Set<Int>`, which carries no date.

So the rollover repaint is discarded and the dates shift only at the next fetch, tap or
provider update.

Desktop needs no trigger: its UI clock ticks every 120 s on epoch-aligned boundaries (local
midnight is one), and each tick rebuilds the daily model with `LocalDateTime.now()`.

## Fix (Android only; desktop already right)

- Record *which day* each widget was last fully painted for:
  `fullyPaintedDailyWidgetIds: Set<Int>` → `dailyPaintedForDate: Map<Int, LocalDate>`.
- `shouldSkipDailyUiOnlyRepaint(uiOnly, paintedForDate, today)` skips only when
  `uiOnly && paintedForDate == today`.
  - A date mismatch rebuilds, logged so it can be audited (`state=data`, with
    `reason=date_rollover` on the paint line).
  - A missing entry rebuilds, as before (the "Loading…" guard).
- The rule keys on the date, not on the alarm. If the screen was off at midnight (the receiver
  skips then), the first UI-only repaint after the screen comes on still rolls the window. It
  self-heals at the next UI tick instead of waiting for a fetch.

## Tests

- Pure: same day → skip; next day → rebuild; no entry → rebuild; not UI-only → never skip.
- Existing `shouldSkipDailyUiOnlyRepaint` callers and tests updated.
- Live: emulator, set the clock to 23:58 (emulator only; `adb shell date` / auto-time off), and
  watch `WIDGET_PAINT … origin=UI_ONLY` after 00:00 change from `skipped_ui_only` to a data
  paint. Screenshot before and after.

## Outcome (implemented 2026-10-05)

- `WidgetRenderer.dailyPaintedForDate: Map<Int, LocalDate>` replaces
  `fullyPaintedDailyWidgetIds: Set<Int>`.
- `shouldSkipDailyUiOnlyRepaint(uiOnly, paintedForDate, today)` = `uiOnly && paintedForDate == today`.
- `WidgetViewModeDispatcher` passes the same `now` to `DailyViewHandler`, records `today` after
  the paint, and logs `WIDGET_PAINT … state=date_rollover paintedFor=… today=…` before a
  rollover repaint.
- Tests:
  - `WidgetRendererDailyUiOnlyRepaintTest`: 2 new cases (pure rule + Robolectric render
    path). Reverting the rule to ignore the date fails exactly those 2.
  - Full suite: 4594 passed.
- Live (emulator-5556):
  - Both emulators are `user` builds, so the clock can't be set. Instead the **timezone** went to
    Pacific/Pitcairn (UTC−8) at 23:57 local, then back to America/Los_Angeles, auto time on.
  - 23:59:25: UI-only → `skipped_ui_only`.
  - 00:01:10: UI-only → `state=date_rollover paintedFor=2026-10-04 today=2026-10-05` → data
    paint. Screenshots: Sat·Today(Sun)·Mon → Sun·Today(Mon)·Tue.
  - The rollover lands up to ~1 min after 00:00: `MINIMUM_DELAY_MS` (60 s) floors an alarm
    scheduled in the last minute, plus delivery slack.
- Installed on the phone and both emulators.
