# Workflow - Weather Widget

## Development Process
1.  **Requirement Analysis:** Define features or fixes in `conductor/tracks/`.
2.  **Implementation:**
    - Develop Kotlin code in `app/src/main/java/`.
    - Update resources in `app/src/main/res/`.
    - Ensure database migrations are handled if schema changes (Room).
3.  **Verification:**
    - **Local Build:** `./gradlew assembleDebug`
    - **Linting/Static Analysis:** `./gradlew lint` (if configured)
    - **Unit Tests:** `./gradlew test`
    - **Instrumented Tests:** `./gradlew connectedDebugAndroidTest` (EMULATOR ONLY).
4.  **Manual Testing:**
    - Install to emulator: `JAVA_HOME=... ./gradlew installDebug`
    - Add/Resize widgets on the home screen.
    - Verify data fetching and UI updates in logs.

## Safety Guidelines
- **CRITICAL:** NEVER run `connectedDebugAndroidTest` or any command that uninstalls/reinstalls the app on PHYSICAL DEVICES. This deletes all home screen widgets. Always use the emulator for such tasks.
- **Git:** Do not automatically commit changes unless instructed. Present a plan first.

## Release Process
(To be defined)
