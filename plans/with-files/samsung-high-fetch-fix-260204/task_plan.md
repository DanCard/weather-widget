# Task Plan: Diagnose and Fix High Frequency Fetches on Samsung Devices

## Goal
Diagnose why Samsung devices are performing an excessive number of fetches compared to other devices and implement a fix (possibly rate throttling or logic correction).

## Phases
1. **Investigation (Codebase)**: Identify fetch logic, device identification, and any existing Samsung-specific logic. `complete`
2. **Investigation (Database)**: Query the backup DB to analyze fetch patterns and identify which data is being fetched excessively. `complete`
3. **Logging (Optional)**: Add logging to understand the "why" if investigation doesn't reveal the cause. `complete` (Added enhanced logging for rate limits and observations)
4. **Implementation**: Fix the root cause or implement rate fetch throttling. `complete`
   - Use `enqueueUniqueWork` to prevent parallel fetches.
   - Add staleness check to `onUpdate`.
   - Remove redundant fetch trigger in `ScreenOnReceiver`.
   - Implement minimum fetch interval in `WeatherRepository`.
5. **Verification**: Verify the fix (if possible with logs/tests). `complete`
   - Performed manual hourly deduplication on device and verified row counts.
   - Verified that code changes prevent future bursts.

## Decisions
- Use `ExistingWorkPolicy.KEEP` for background fetches to avoid parallel network calls.
- Use `ExistingWorkPolicy.REPLACE` for UI-only updates to ensure the latest toggles are reflected.
- Add 30-minute cache enforcement and 1-minute network rate limit in `WeatherRepository`.
- Reverted programmatic auto-cleaner in favor of manual ADB cleanup to maintain user control.

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| Replace failed | 1 | Corrected `old_string` context in `WeatherWidgetWorker.kt` |
| Replace failed | 1 | Corrected `old_string` context in `ScreenOnReceiver.kt` |
| sqlite3 not on device | 1 | Used pull -> local sqlite3 -> push strategy for cleanup |