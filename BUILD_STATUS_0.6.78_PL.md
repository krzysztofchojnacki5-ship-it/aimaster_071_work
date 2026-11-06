# Build status — 0.6.78-SOURCE

- Parent: `AI_MASTER_PRO_0_6_77_COMPANION_TLS_PROFILE_LIFECYCLE_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`.
- `CompanionProvisioningStore.promotePeerRotation(peerId)` now performs a peer-scoped pre-commit revocation fence before persisting the staged pin promotion. Detached live sessions are closed after leaving the store monitor.
- If pin persistence or fence preparation fails, the operation fails closed: revocation cleanup/dispatch is still attempted and no success is reported.
- `stagePeerRotation()` explicitly documents overlap semantics: both old and staged pins are accepted, and existing sessions are not disconnected during staging. Promotion removes the old pin and invalidates all existing sessions for that peer.
- Added `CompanionPeerPinSetRotationTest`: staging overlap, promotion rejection of old pin, and cancellation semantics.

## Executed checks
- `kotlinc -Werror` for all production `app/src/main/java/pl/aimasterpro/app/core/Companion*.kt`: PASS.
- Local shim-JUnit runner for new pin rotation tests: 3/3 PASS.
- ZIP integrity: PASS.

## Not executed
- Android `CompanionProvisioningStore.kt` compile against real Android APIs: NOT RUN.
- Gradle/JUnit/Robolectric/`testDebugUnitTest`: NOT RUN.
- `assembleDebug`, APK, Android Keystore, physical-device tests: NOT RUN.
- No APK created; no listener/transport started.

The 3/3 local tests validate immutable pin-set semantics only; they do not execute the Android-backed store promotion path. The store integration is reviewed statically and requires Gradle/Robolectric/Keystore validation in Android Studio.
