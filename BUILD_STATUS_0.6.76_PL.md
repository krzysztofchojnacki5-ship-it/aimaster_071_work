# Build status — 0.6.76-SOURCE

- Parent: `AI_MASTER_PRO_0_6_75_COMPANION_TLS_GATE_PRODUCTION_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`.
- Added `CompanionProvisionedSession` capability binding one exact pinned TLS profile to the owner created from it.
- Added preferred `CompanionProvisioningStore.createSessionProvision(...)` factory.
- Added gate overloads accepting the provision capability; retained legacy profile+owner overloads with an identity guard before ticket allocation.
- Deprecated `createSessionOwner(...)` in favor of the bound provisioning factory.

## Executed checks
- `kotlinc -Werror` for production `Companion*.kt` core sources: PASS.
- Local shim-JUnit runner: `CompanionTlsHandshakeGateTest` 3/3 PASS, `CompanionRuntimeTest` 6/6 PASS; total 9/9.
- Real loopback mutual-TLS path through the provision capability: PASS.
- Cross-wired TLS profile/owner rejected before admission ticket allocation: PASS.

## Not executed
- `CompanionProvisioningStore.kt` Android-backed compile: NOT RUN (no Android SDK `android.jar` available in this environment).
- Gradle 8.13 / `testDebugUnitTest`: NOT RUN.
- Real Gradle JUnit, Robolectric, `assembleDebug`, APK, Android Keystore and device tests: NOT RUN.
- Companion listener/transport is not started. No APK created.

The local JVM runner uses a lightweight JUnit shim and is not a substitute for Gradle/JUnit or Android execution.
