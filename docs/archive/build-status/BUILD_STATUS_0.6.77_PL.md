# Build status — 0.6.77-SOURCE

- Parent: `AI_MASTER_PRO_0_6_76_COMPANION_SESSION_PROVISION_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`.
- `CompanionPinnedTlsProfile` now implements `Closeable` and has a permanent OPEN→CLOSED lifecycle. Close fences new listener/connection creation, closes owned server listeners, handshaking sockets, receipt-pending sockets and admitted sockets, clears pending receipts/session-ID copies, and cancels receipt-expiry tasks.
- Added profile-close callbacks. A gate-owned connection registers a callback that closes its session owner and socket and releases its registry handle when the TLS profile closes. The gate handles the profile-close-vs-wrapper-registration race and cleans up if wrapper construction fails.
- `CompanionProvisionedSession.close()` closes the exact session owner and then its paired TLS profile in `finally`; repeated close is safe.
- `CompanionProvisioningStore.createSessionProvision()` and deprecated `createSessionOwner()` now close the newly created profile if owner construction fails.
- `consume()` rejects sockets already closed before receipt consumption.

## Executed checks
- `kotlinc -Werror` for all production `app/src/main/java/pl/aimasterpro/app/core/Companion*.kt`: PASS.
- `kotlinc -Werror` for all Companion core sources plus the gate/runtime/binding regression tests: PASS.
- `kotlinc -Werror` for all Companion core sources and `CompanionProvisioningStore.kt` against lightweight Android/JSON API stubs: PASS. This verifies Kotlin/API seams only, not Android runtime or Keystore behavior.
- Local shim-JUnit runner: gate/profile/provision tests 6/6, runtime 6/6, trust-binding 4/4; total 16/16 PASS.
- Real loopback mutual-TLS tests: PASS for pending-receipt cleanup, listener closure, admitted owner/socket cleanup, registry-handle release, revocation and profile/owner identity binding.
- Manifest XML and static source/call-site checks: PASS.
- ZIP integrity: PASS.

## Not executed
- Gradle 8.13 / `testDebugUnitTest`: NOT RUN.
- Real Gradle JUnit, Robolectric, `assembleDebug`, APK, Android Keystore and physical-device tests: NOT RUN.
- No APK was created. Companion listener/transport is not started automatically.

The local shim-JUnit runner and Android/JSON stubs are not substitutes for Gradle/JUnit or Android execution. Source version remains 0.6.54 / code 80.
