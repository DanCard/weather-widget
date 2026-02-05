# Task Plan: Fix Hang and Stuck Loading Screen

## Goal
Fix the issue where the widget stays stuck on "Loading..." and the app process is frozen by the system after installation.

## Phases
1. **Phase 1: Diagnosis & Information Gathering** (Complete)
    - Identified "Stuck Loading" bug in `onUpdate`.
    - Identified job bursts and redundant coroutines.
2. **Phase 2: Analysis** (Complete)
    - Determined that `onUpdate` must always refresh UI from cache.
    - Determined that background tasks should be consolidated to avoid system freezing.
3. **Phase 3: Implementation of Fix** (Complete)
    - Refactored `WeatherWidgetProvider.onUpdate` to load from cache and fix "stuck loading" state.
    - Reduced startup resource storm by consolidating updates and removing redundant triggers.
    - Cleaned up unmanaged coroutines in `updateWidgetWithData`.
4. **Phase 4: Verification** (In Progress)
    - User to run `./gradlew installDebug`.
    - Verify all widgets load data correctly without hanging.
    - Check `dumpsys jobscheduler` for sanity.

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| app hangs after reinstall | 1 | Fixed job loop, but still hangs. |
| process frozen by system | 2 | Refactored provider to consolidate work and fix UI state logic. |