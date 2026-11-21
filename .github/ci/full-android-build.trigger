AiFORTeca 0.2 full Android build requested after package 23.
Source PR: #3
Source branch: work/aiforteca-0.2-master-agent-foundation
Source HEAD before CI trigger: fdc13ac9682ed007a955aba214a32c3f01654531
Required outputs: debug APK; unsigned release APK unless signing secrets are configured and verified.
Verification: run unit tests, Gradle wrapper validation, assembleDebug and assembleRelease. Do not treat reports or source ZIP as APK.
Package 23: safe model-profile deletion, provider assignment reconciliation, deletion UI safeguards, and model catalog selection fallback.
Build request: produce installable debug APK and release APK from the exact package 23 source revision.
