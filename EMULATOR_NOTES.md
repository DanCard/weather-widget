# Emulator Startup Notes

If the emulator fails to start or the window doesn't appear:

1.  **Check for Stale Locks**:
    ```bash
    find ~/.android/avd -name "*.lock" -delete
    ```

2.  **Use the Absolute Path**:
    Always use the emulator binary from the Sdk folder:
    `/home/dcar/.Android/Sdk/emulator/emulator -avd <avd_name>`

3.  **Try Software Rendering**:
    If graphics are an issue:
    `-gpu swiftshader_indirect`

4.  **Wipe Data**:
    If the AVD is corrupted:
    `-wipe-data`

5.  **Available AVDs**:
    - `Generic_Foldable_API36`
    - `Medium_Phone_API_36`

6.  **Launch Command**:
    ```bash
    /home/dcar/.Android/Sdk/emulator/emulator @Generic_Foldable_API36 &
    ```
    (Note the `@` prefix is often required or preferred).
