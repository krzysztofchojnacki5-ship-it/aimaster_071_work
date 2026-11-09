# AiFORTeca Android build baseline

Baseline selected on 2026-11-09. Versions are pinned; no dynamic version selectors.

| Component | Pinned version | Reason |
|---|---:|---|
| Gradle Wrapper distribution | 9.9.0 | Latest stable Gradle release available on 2026-11-09 |
| Android Gradle Plugin | 9.4.0 | Current stable AGP; minimum required Gradle is 9.6.0 |
| Kotlin Gradle Plugin | 2.4.10 | Version shown in the official AGP 9.4 setup guidance |
| JDK | 17 | AGP 9.4 supported/default JDK |
| Android SDK platform | 36 | Existing project compileSdk |
| Android Build Tools | 36.0.0 | AGP 9.4 default |

## Wrapper integrity

- `gradle/wrapper/gradle-wrapper.jar` is sourced from the official `gradle/gradle` repository tag `v9.9.0`.
- `gradle/wrapper/gradle-wrapper.jar.sha256` records the official published SHA-256:
  `621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8`.
- `gradle/wrapper/gradle-wrapper.properties` pins `gradle-9.9.0-bin.zip`, enables URL validation, uses a 120-second timeout, and retries transient download failures three times.
- GitHub Actions runs the official Gradle Wrapper Validation action on pushes and pull requests.

## Build verification policy

The Android CI workflow runs:
1. JDK 17 setup and Android SDK provisioning.
2. `./gradlew --version`.
3. `./gradlew --no-daemon testDebugUnitTest`.
4. `./gradlew --no-daemon assembleDebug`.
5. Uploads the debug APK only if all preceding steps pass.

A source commit, a wrapper checksum, or a workflow definition is not proof of a successful build. Until a workflow run completes successfully, build and test status must remain NOT RUN / UNVERIFIED.

## Upgrade policy

- Keep Gradle, AGP, Kotlin, JDK, compile SDK and Android Build Tools in a documented compatible set.
- Do not upgrade Gradle independently of AGP compatibility.
- Do not use dynamic versions such as `9.4.+`.
- Keep the wrapper JAR and its checksum in source control.
- Any future toolchain upgrade must run wrapper validation, unit tests and debug APK assembly before being marked PASS.
- The experimental `org.gradle.tooling.parallel` property is intentionally absent; it was previously associated with unstable IDE/Gradle behavior in this project history.
