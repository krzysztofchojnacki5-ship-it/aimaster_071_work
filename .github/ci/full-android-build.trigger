AiFORTeca 0.2 full Android build requested after package 25.
Source PR: #3
Source branch: work/aiforteca-0.2-master-agent-foundation
Package 25 source HEAD: 247addab560223eab2c5307057420a77d9819221
Scope: safe task recovery with a new fenced execution epoch, preservation of completed agent reports, stale callback rejection, and refusal to resume archived reports with unreconciled API usage/checkpoints.
Required outputs: installable debug APK, release APK (signed only if signing secrets are configured and validated), and source project archive.
Verification: run unit tests, Gradle wrapper validation, assembleDebug and assembleRelease against this source revision. Do not mark build or package complete until workflow and artifacts are verified.
