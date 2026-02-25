# Emulator Startup Notes

If the emulator fails to start or the window doesn't appear:

1.  **Use the Absolute Path**:
    Always use the emulator binary from the Sdk folder:
    `/home/dcar/.Android/Sdk/emulator/emulator -avd <avd_name>`

2.  **Available AVDs**:
    - `Generic_Foldable_API36`
    - `Medium_Phone_API_36`

3.  **Launch Command**:
    ```bash
    /home/dcar/.Android/Sdk/emulator/emulator @Generic_Foldable_API36 &
    ```
    (Note the `@` prefix is often required or preferred).
