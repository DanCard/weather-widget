# Fix: repair the interrupt-driven UI update implementation

**Date:** 2026-07-13
**Plan:** [plans/260713-fix-interrupt-driven-ui-updates.md](../plans/260713-fix-interrupt-driven-ui-updates.md)
**Status:** Implemented, tested, deployed to the daily build & verified live

Repairs four issues in the Gemini-authored interrupt-driven UI update commits
(`e534715c`…`e8c54ab0`) while keeping their architecture (trigger files + existing
`WatchService`s), which correctly fixes the resume-from-suspend popup lag.

## What changed

1. **Triggers are now one-way** (`DesktopProcess.kt`):
   - `.data-updated` = daemon → UI only ("cache changed — reload; outcome via CURRENT_TEMP_STATUS").
   - `.refresh-requested` (new) = UI → daemon only ("I ran a successful refresh() — pick it up");
     written by the popup's manual-refresh action, handled by a new daemon watcher branch
     (reload cache + `deriveDataStatus(refreshFailed = false)`; stale file consumed at startup).
   - The daemon **no longer watches `.data-updated`**. Its old self-echo handler ran a redundant
     `loadCached()` after every fetch and — the actual bug — unconditionally set
     `DataStatus.Live`, erasing the `Stale(OFFLINE/SOURCE_ERROR)` status the failure paths had
     set milliseconds earlier. Offline/stale indication now survives failed refreshes.
2. **UI trigger handler no longer writes `dataStatus`** (`Main.kt`): a bare trigger carries no
   fetch outcome (failures touch it too); assuming Live clobbered the popup's stale indication.
   Outcome still reaches the banner through the `CURRENT_TEMP_STATUS` contract, re-read when
   `dataUpdateCount` bumps.
3. **Safety-net poll restored** (`Main.kt` + `UI_FALLBACK_RELOAD_MS = 10 min`): the interrupt
   stays the fast path; a missed watch event or dead watcher loop now degrades to ≤10 min
   staleness instead of stale-forever. Unused `CURRENT_TEMP_UI_INTERVAL_MS` removed.
4. **`CURR_TEMP_RESULT` demotion is now caller-scoped** instead of global:
   `CurrentTemperatureResolver.resolve(resultLogLevel = "DEBUG")` — Android widget paths and
   desktop sparse fetch-cycle resolves persist DEBUG rows again (the resolver's own comment
   calls this "the one summary worth querying"); only desktop
   `resolveCurrentTempInMemory` (per genmon connect / per-minute UI ticker) passes VERBOSE.

## Tests

- `DesktopStartupTest`: migrated Gemini's watcher test — spawns the real daemon, touches
  `.data-updated` **then** `.refresh-requested`, asserts the daemon handles the second and
  never reacts to the first (ordering guarantees the negative check is meaningful).
- `:desktop:test`, `:shared:test` green; `:app` unit tests compile (resolver change is a
  default parameter — Android call sites untouched).

## Live verification (after `scripts/buildStart-desktop.sh`)

- Startup fetch at 12:06:41 → UI-process reload row at 12:06:43: the daemon→UI interrupt path
  works end-to-end (this also confirms the fix for the original ">15 s to update after resume"
  complaint — the popup now reloads seconds after the daemon's catch-up fetch).
- Manual `touch .refresh-requested` → daemon consumed the file and reloaded (12:07:15).
- >60 s idle windows show **zero** `CURR_TEMP_RESULT` rows (ticker + genmon polls stay out of
  app_logs), while sparse paths write DEBUG rows again.

## Notes

- Trigger handlers can fire twice per touch (CREATE + MODIFY); harmless (idempotent), noted
  in case it shows up in logs.
- Out of scope (unchanged from Gemini's version): 60 s panel trigger loop, per-connect markup
  interpolation, UI-side Stale/Live derivation from CURRENT_TEMP_STATUS rows.
- Changes are uncommitted (user decides on commit).
