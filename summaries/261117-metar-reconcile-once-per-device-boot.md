# NWS station rows reconciled with their own METARs once per device boot

Plan: `plans/261117-metar-reconcile-once-per-device-boot.md` (approved with the 2-hour addition).
It follows `plans/261117-metar-reconcile-cadence-options.md` and
`plans/261117-nws-station-rows-reconciled-with-own-metars.md`.

## User prompts

1. "Only phone start, emulator start, or desktop start."
2. "I meant phone start, not app start."
3. "approve with the 2 hour addition, implement"
4. "write above recap to summaries/ dir. Don't append filename with \"-recap\""

## What changed

Shared code, so phone, emulator and desktop follow one rule.

- **Once per device boot.**
  - The METAR repair (`NwsGapFillRun.reconcileOncePerBoot` → `reconcileMetars`) runs on the first
    catch-ups after each boot, once per location (`LocationMatch` key).
  - Each device remembers which boot it last repaired:
    - Android: `Settings.Global.BOOT_COUNT`, kept in the `nws_metar_reconcile_boot` preferences
      (`AndroidMetarReconcileBootStore`).
    - Desktop: the Linux `boot_id`, kept in `<database>.metar-reconcile-boot` beside the database
      (`DesktopMetarReconcileBootStore`).
  - No database schema change.
- **The 2-hour addition.**
  - Every catch-up repairs until one succeeds at least 2 hours after boot (`RECONCILE_SETTLE_MS`).
    Only then is the boot marked done.
  - The reason is that the NWS feed adds reports 60–80 min late.
  - If a request fails (for example, no network yet), the boot stays pending and the next catch-up
    tries again.
- **A race found and fixed during verification.**
  - On the emulator, the widget Refresh and a full sync both ran the repair 5 s apart in the same
    boot.
  - The full sync stored its rows, then spent 5 s recomputing 4 days before logging. The Refresh
    read the rows in between and logged `added=0`.
  - A process-wide `Mutex` now runs one repair at a time, so a second one waits and then sees the
    marker. Its new test fails without the lock and passes with it.
- **Station lookup failure.** If NWS's station list doesn't answer, the repair now counts as
  unanswered (`stations=? unreachable=true`), so the boot isn't marked.
- **Docs.** `arch/forecast-and-observation-cadence.md` covers the boot rule, the 2 hours and the lock.

## Tests

Two end-of-task gates, `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`,
both passed. The second, after the race fix, ran 7,121 unit tests and 127 emulator tests, then
installed on all 3 devices and restarted desktop.

New coverage:

- **`NwsGapFillRunTest`** (the shared rule):
  - the same boot repairs once, and a new boot repairs again;
  - before and after the 2-hour point;
  - a failed request is retried;
  - each location is separate;
  - with no boot ID, every catch-up repairs;
  - two catch-ups at once send one request.
- **`NwsGapFillHostContract`:** the boot marker is saved and read back through Room (Robolectric)
  and desktop's real file.
- **`AndroidMetarReconcileBootStoreTest` and `DesktopMetarReconcileBootStoreTest`:** reading the boot
  on Android and on Linux.
- **`DesktopMetarReconcileIntegrationTest`:** a real database and marker file give one METAR request
  in one boot, and a second after a new boot.
- **`DeferredObservationWindowTest`:** station lookups go from 1 to 2 (the boot's repair plus the
  first wake's fill). The second wake still makes no request.

## On the devices

- **Desktop:** the first catch-up logged `NWS_METAR_RECONCILE … added=28 byStation={KNUQ=16, KSJC=11,
  KPAO=1}`, then `boot_done` once. The marker file holds this boot's ID.
- **Emulator:**
  - **Repair:** it ran on boot 126 and added 10 KSJC reports that had only ever been stored as METAR
    and Synoptic rows, never NWS.
  - **KNUQ's hole:** its 06:15–08:35 hole had already been filled before this build arrived, by the
    earlier stale-history fix (11:39) and by the NWS feed's own late copies (12:11).
  - **Today's fog:** it now comes from KNUQ itself. Its 03:35 and 03:55 reports read `9SM CLR 06/06`:
    9 miles visibility, temperature equal to dewpoint, calm wind. An existing rule
    (`ObservedMorningFog.saturatedWithCondensation`) counts that as a fog **hint** (level 1, top 42.8°).
  - **Screenshot:** nothing visible on Today. KSJC's dense fog from 16 km away is gone.
- **Not checked:** the "already done this boot" skip on the device.
  - The Refresh broadcast landed while the app was restarting after the install.
  - That skip only writes to logcat at VERBOSE level, not to `app_logs`.
  - The unit tests cover it.

## What to expect

- **Desktop** repairs again after its next reboot.
- **Pixel 7 Pro** has been up about 2 days, so it has a while to go.
- **Samsung SM-F936U1** has been up about 24 days, so a new hole there could stay for weeks until it
  restarts.

## Open items

- Nothing is committed.
- `plans/261117-metar-reconcile-once-per-process-start.md` is superseded and left in place, pending
  the user's word on deleting it.
