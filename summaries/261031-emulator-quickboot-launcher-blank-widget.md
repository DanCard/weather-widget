# Emulator widget blank after Quick Boot restore

Date: 2026-10-31

## Outcome

The blank emulator widget is a launcher or emulator problem, not an app or data bug. No app code
changed. After a new emulator process restored the Quick Boot snapshot, the launcher sometimes stopped
drawing widget content. Backgrounding and foregrounding the launcher healed it with no app push.

## User prompts

1. Emulator: Widget failed to display
2. Can We debug this quickboot issue?
3. Is it possible to fix the launcher?
4. How to report the issue to google? Can you do it?
5. save session summary to summaries/

## Environment

- AVD: `Medium_Phone_API_36`, launched with `-avd Medium_Phone_API_36 -no-audio`
- Emulator 36.3.10.0 (build 14472402), gfxstream, host GPU mode
- Image: `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.D1/13818094`
- Host: Debian forky/sid, kernel 7.2.12, X11, AMD Radeon 8060S, Mesa 26.1.6

## Original failure timeline

1. 13:46:42: widget 63 got a full PRECIPITATION push. The renderer logged its labels and pills.
2. 13:47:27: the `default_boot` snapshot was saved.
3. About 16:59:50: a new emulator process restored it. The guest clock stepped +3h12m and Android
   PIDs from before the snapshot carried on.
4. 16:59:56: the worker logged `state=skipped reason=header_only_live`.
5. 17:03:05: the debug REFRESH hook sent a partial push, which restored the widget.

## Experiments

| Test | Result |
|---|---|
| `avd pause` / `avd resume` | Intact |
| Snapshot save and load in the same process | Intact |
| New emulator process, restore 1 | Blank |
| New emulator process, restore 2 | Blank |
| New emulator process, restore 3 | Intact |

Evidence:

1. No widget push was logged between restore 1 and the heal; the only worker paint logged
   `skipped_ui_only`.
2. Settings then BACK healed both blank restores with no widget push logged. App and launcher PIDs were
   unchanged in the run where they were checked.
3. The graph-area grey standard deviation was about 0.078 blank and 0.131 healed.
4. The launcher texture cache fell from 94 to 37 entries while backgrounded.
5. Launcher app icons are `AHardwareBuffer`-backed and survived restores.

## Conclusions

1. Only a new emulator process restoring a snapshot triggered the failure, in 2 of 3 attempts.
2. The failure is launcher-side: a redraw healed it without any new app content.
3. The stale GPU cache mechanism is inferred, not proven. Why restores vary is unknown.
4. Bitmaps are not the only casualties. On the repro, temperature, source and gear text or icons went too.
5. `GraphRepaintGate` uses `elapsedRealtime`, which resumes from the snapshot, so three hours of wall
   time read as seconds. In DAILY, `skipped_ui_only` means the worker pushes nothing at all.

## Decisions

1. No app change: phones do not restore snapshots, and an app repaint would work around an emulator
   quirk.
2. Launcher cannot be patched: it is a signed, closed-source Google app.
3. Recommended mitigation: `fastboot.forceColdBoot=yes` or `-no-snapshot-load`. This is untested and
   was not applied. Settings then BACK remains the tested fix.

## Other findings

1. The emulator stores future-dated fetch metadata and `hourly_forecasts.fetchedAt` values around
   18:05, in rows that appear with test runs. `CURR_FETCH_FRESH_SKIP` skips current-temperature fetches
   until then. The source was not traced and this is not fixed.
2. `SYNC_SKIP_TEST_ENQUEUED` was a false lead: it is the guard dropping work from an exited test process.

## State left behind

1. `Medium_Phone_API_36` is on `emulator-5556`, and `default_boot` was overwritten by the restart tests.
2. The original foldable process was replaced by a new one at 17:13:57. This session did not launch it.
3. Launcher capture: `captures/launcher-state-20261031_170133/` (gitignored).
4. Project memory: `emulator_quickboot_restore_blank_bitmaps.md`, indexed in `index_widget_rendering.md`.

## Google report

Not filed. The session drafted a report for the emulator's Extended controls > Bug Report flow. Check
Issue Tracker for duplicates and the emulator version first. Review logs before attaching them, because
they may contain location and app details.

claude --resume 247b334a-9754-415f-8e00-f61533a6a93e

