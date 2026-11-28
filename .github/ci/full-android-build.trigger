AiFORTeca 0.2 full Android build requested after package 41.
Source PR: #3
Source branch: work/aiforteca-0.2-master-agent-foundation
Final package 41 source and regression-test HEAD: e2860753a7d0f464fd39378d9dd949b5e17bba63
Scope: four package 41 hardening fixes: isolate diagnostic queue events by task run; isolate agent handoffs by task run; include only assigned-agent reports from the selected run and bound report count/output with explicit incomplete status when truncated; redact structured credentials from provider HTTP error bodies using the shared sanitizer.
Required outputs: installable debug APK, release APK (signed only if signing secrets are configured and validated), and source project archive.
Verification: run the complete unit-test suite, Gradle wrapper validation, assembleDebug and assembleRelease against the exact package 41 source revision. Do not mark the package complete until workflow results and artifacts are verified.
