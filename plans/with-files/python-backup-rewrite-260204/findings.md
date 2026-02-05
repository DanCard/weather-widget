# Findings - Python Backup Script Rewrite

## AVD Mapping
- `emulator-5556` -> `Medium_Phone_API_36`
- `emulator-5554` -> `Generic_Foldable_API36`
- Property to check: `ro.boot.qemu.avd_name`

## Load Detection
- Command: `uptime`
- Threshold: TBD (Suggestion: > 10.0 for 1-minute load).
- Action: If it's an emulator, `adb -s <id> emu kill`, then restart with `/home/dcar/.Android/Sdk/emulator/emulator @<avd_name> &`.

## Script Improvements
- Parallel execution using `concurrent.futures`.
- Better error handling for file copies.
- JSON metadata.
- Integrated database analysis (using `sqlite3` module).