AiFORTeca 0.2 full Android build requested after package 27.
Source PR: #3
Source branch: work/aiforteca-0.2-master-agent-foundation
Package 27 final source HEAD: 79eea4084105dd1591965a87397856baa3919157
Scope: add validated source provenance to governed memory mutations; persist the source reference in the append-only memory audit journal and include it in the hash chain; preserve verification and append compatibility for legacy rows; propagate source references through the mutation gate and UI call sites.
Required outputs: installable debug APK, release APK (signed only if signing secrets are configured and validated), and source project archive.
Verification: run unit tests, Gradle wrapper validation, assembleDebug and assembleRelease against this exact source revision. Do not mark build or package complete until workflow and artifacts are verified.
