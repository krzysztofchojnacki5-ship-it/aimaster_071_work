AiFORTeca 0.2 full Android build requested after package 26.
Source PR: #3
Source branch: work/aiforteca-0.2-master-agent-foundation
Package 26 final source HEAD: d0b78cd0c720fd0da4b10a7298b6011c18f0f7fb
Scope: crash-safe staging of every previously completed agent report into the next execution epoch before the durable MASTER queue advances its run ID; retry-safe staging; fail-closed handling of missing or conflicting archive entries; regression coverage for partial staging and interrupted recovery.
Required outputs: installable debug APK, release APK (signed only if signing secrets are configured and validated), and source project archive.
Verification: run unit tests, Gradle wrapper validation, assembleDebug and assembleRelease against this exact source revision. Do not mark build or package complete until workflow and artifacts are verified.
