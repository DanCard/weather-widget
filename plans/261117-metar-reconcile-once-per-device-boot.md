# Reconcile NWS station rows with their own METARs once per device boot

User decision (2026-11-17), on `plans/261117-metar-reconcile-cadence-options.md`: "Only phone start,
emulator start, or desktop start" → "I meant phone start, not app start." This replaces
`plans/261117-metar-reconcile-once-per-process-start.md` (not implemented).

## How often each device boots (checked 2026-11-17 ~10:45)

| Device | Up since | Boot id |
|---|---|---|
| Pixel 7 Pro | ~2.0 days | `Settings.Global.BOOT_COUNT` = 69 |
| Samsung SM-F936U1 | ~24.5 days | `BOOT_COUNT` = 17 |
| emulator-5554 | ~2.3 h (booted ~08:10 today) | `BOOT_COUNT` = 126 |
| Desktop (Linux) | 11-13 06:32; before that 11-07, 10-26 | `/proc/sys/kernel/random/boot_id` |

So the Samsung would repair about once a month, the Pixel every few days, and desktop about once a
week. A hole that opens between boots stays until the next boot or a manual fix. (Today's emulator
hole, 05:55–08:55, ended after its 08:10 boot, so a boot repair then would not have caught all of it;
see "After a boot" below.)

## Proposed fix (`:shared` rule, platform supplies the boot id and the marker)

1. **Host** gains:
   - `bootId(): String?`. Android: `Settings.Global.BOOT_COUNT`; desktop: `/proc/sys/kernel/random/boot_id`.
     Null if unreadable, which means "reconcile" (safe).
   - `reconciledBootId(siteKey): String?` and `markReconciled(siteKey, bootId)`. Android: a small
     `SharedPreferences` file; desktop: a state file under `~/.local/state/weather-widget/`. No schema change.
2. **`NwsGapFillRun.dailyForSources`** calls `reconcileMetars` only when the stored boot id for this
   site (`LocationMatch` quantization) differs from the current one. The rule lives in `:shared`.
3. **Mark only on success.** An `unreachable` reconcile (no network right after boot) leaves the
   marker alone, so the next pass tries again until one answers.
4. **After a boot.** Rows the NWS API lacks are often still missing 1–2 h later (it lags 60–80 min),
   and a boot can land inside a hole. So the boot's reconcile stays pending until the first pass at
   least 2 h after boot, and then marks. Passes before that reconcile too. Cost: a few extra
   Aviation Weather requests in the first 2 h after each boot. Claude's proposal, not a user decision:
   drop it if a single request per boot is preferred.
5. A skipped pass logs `NWS_METAR_RECONCILE skipped=boot_done` at `VERBOSE` (logcat only).
6. Unchanged: `reconcileMetars` itself, the need check, the per-station fill and the stale-history leg.

## Tests

1. `NwsGapFillRunTest` (fake host):
   - the same boot reconciles once (after the 2 h point);
   - a new boot id reconciles again;
   - a failed request is retried;
   - each site is separate;
   - a null boot id reconciles.
2. Host contract tests for the marker: `RoomNwsGapFillHostContractTest` (real prefs via Robolectric)
   and `DesktopNwsGapFillHostContractTest` (real file).
3. `DeferredObservationWindowTest`: its first wake reconciles (station lookups 1 → 2), and its second
   wake makes no request, as now.
4. Desktop integration on real SQLite: `refreshObservationWindow` twice in one boot gives one
   Aviation Weather request, and a changed boot id gives a second.
5. Gate: `./scripts/staggered-tests.sh --install && ./scripts/buildStart-desktop.sh`. No device has a
   marker yet, so each one's first pass reconciles. Check on the emulator: an
   `NWS_METAR_RECONCILE … added>0` line, KNUQ NWS rows for 06:15–08:35, no
   `DAILY_FOG_DRAWN date=2026-11-17`, and a screenshot. Then the same on desktop.

## Docs

`arch/forecast-and-observation-cadence.md`, gap-fill section: the reconcile runs once per device boot
and site (after the 2 h point), and a complete week otherwise makes no request.
