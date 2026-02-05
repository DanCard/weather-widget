# Task Plan - Python Backup Script Rewrite

## Goal
Rewrite `scripts/backup_databases.sh` in Python to improve robustness, add high-load detection, and implement automatic emulator restarts.

## Phases
- [ ] Phase 1: Design & Architecture `status: todo`
    - Define Python script structure.
    - Map ADB commands to Python functions.
    - Design load detection and restart logic.
- [ ] Phase 2: Implementation `status: todo`
    - Implement core ADB wrapper.
    - Implement device discovery and metadata extraction.
    - Implement file backup logic (run-as/su support).
    - Implement load monitoring and emulator restart mechanism.
- [ ] Phase 3: Testing & Refinement `status: todo`
    - Test with physical devices and emulators.
    - Verify restart logic on high-load emulators.
- [ ] Phase 4: Finalization `status: todo`
    - Replace/Deprecate bash script.
    - Update documentation if needed.

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
| | | |