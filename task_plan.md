# Play Store Readiness Plan

## Goal
Get the weather widget app ready for Play Store submission by addressing all identified gaps.

## Phases

### Phase 1: Update targetSdk to 35 [pending]
- Change `targetSdk = 34` → `targetSdk = 35` in `app/build.gradle.kts`
- Check for any API 35 behavioral changes that affect the app
- Build and verify

### Phase 2: Privacy policy finalization [pending]
- Fill in contact info placeholder in `plans/privacy-policy-draft.md`
- Note: actual hosting is a user action (needs URL)

### Phase 3: App icon verification [pending]
- Icon exists: adaptive icon with sun foreground on color background
- Verify it looks reasonable; no placeholder art detected
- Document: user may want to customize before launch

### Phase 4: AppLogsActivity - production visibility [pending]
- Decide: keep accessible (useful for support) or hide in release
- Current: `android:exported="false"` - only accessible from within app
- This is fine for production - users can share logs for support

### Phase 5: FeatureTourActivity [pending]
- AGENTS.md references it but file doesn't exist
- Decide if needed or remove from docs

### Phase 6: API key behavior when blank [pending]
- Verify app handles empty API keys gracefully
- NWS and Open-Meteo need no keys - these work
- Paid APIs (Silurian, WeatherAPI, etc.) - verify fallback behavior

### Phase 7: Release build test [pending]
- Run `./gradlew assembleRelease` to verify R8 + signing works
- Note: needs RELEASE_STORE_PASSWORD and RELEASE_KEY_PASSWORD set

### Phase 8: Play Store assets checklist [pending]
- Document what's needed: 512x512 icon, feature graphic, screenshots
- These are user-created assets

### Phase 9: Background location justification [pending]
- Document the justification for Play Console form
- Widget needs background location to update weather when not in foreground

### Phase 10: Data Safety form prep [pending]
- Document what data the app collects/sends for the Play Console form

## Errors Encountered
| Error | Attempt | Resolution |
|-------|---------|------------|
