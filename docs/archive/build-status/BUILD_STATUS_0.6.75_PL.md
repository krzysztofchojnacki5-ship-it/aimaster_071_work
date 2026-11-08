# Build status — 0.6.75-SOURCE

- Parent: `AI_MASTER_PRO_0_6_74_COMPANION_RUNTIME_PRODUCTION_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`.
- Promoted `CompanionTlsHandshakeGate` into production `app/src/main/java/pl/aimasterpro/app/core/`.
- `CompanionRuntime` owns exactly one gate tied to its own registry; `CompanionApplication` exposes that gate through `companionTlsHandshakeGate()`.
- Added permanent registry shutdown and runtime close ordering: admission is fenced, tickets invalidated, active handles closed, then trust listener detached.

## Executed checks
- `kotlinc -Werror` for selected TLS/profile/owner/registry/gate/revocation/binding/runtime production classes: PASS.
- Local JVM shim runner: new gate/runtime suite 8/8 PASS.
- Existing registry/binding/runtime suite 35/35 PASS against the updated core.
- Real loopback mTLS test covers success, peer revocation, and deterministic revoke between owner activation and registry registration.
- 40 failed connect attempts do not leak admission tickets.
- Runtime shutdown closes active handles, rejects new tickets, and rejects registration using a pre-shutdown ticket.
- `CompanionApplication.kt` seam `kotlinc -Werror` with Android/data stubs: PASS.
- AndroidManifest XML parse: PASS.

## Not executed
- Gradle 8.13 / `testDebugUnitTest`: NOT RUN (environmental Gradle distribution/network limitation remains; user plans to run Gradle in Android Studio).
- Real Gradle JUnit and Robolectric: NOT RUN.
- `assembleDebug`, APK, Android Keystore and device tests: NOT RUN.

The local shim runner is not a substitute for Gradle/JUnit or Android execution. No APK was created. Companion transport/listener is not started automatically.
