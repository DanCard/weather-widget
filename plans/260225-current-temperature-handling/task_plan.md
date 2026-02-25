# Task Plan

## Goal
Implement design improvements for current temperature handling and refresh behavior:
1) make interpolation source selection explicit/consistent,
2) separate displayed estimated temperature from API-provided current observation in code paths,
3) centralize refresh decision policy,
4) show stale-aware current temperature formatting,
5) avoid ad-hoc `TemperatureInterpolator()` instantiation in handlers.

## Phases
- [completed] Phase 1: Add central policy object for refresh/update decisions.
- [completed] Phase 2: Add reusable current temperature resolver with explicit source behavior.
- [completed] Phase 3: Integrate resolver into daily/temperature/precip handlers and add stale-aware display formatting.
- [completed] Phase 4: Wire shared usage for interpolation in handlers (remove direct constructor usage).
- [completed] Phase 5: Add/update tests and run targeted test suite.

## Constraints
- Preserve existing widget behavior where possible.
- Keep battery-aware and UI-only update architecture intact.
- Keep code style and import ordering per AGENTS.md.

## Errors Encountered
| Error | Attempt | Resolution |
|---|---:|---|
| `./gradlew test --tests ...` unsupported for this project task wiring | 1 | Switched to `:app:testDebugUnitTest --tests ...` |
| Kotlin `assertEquals` overload mismatch on nullable Float? | 1 | Used non-null assertions in targeted test assertions |
