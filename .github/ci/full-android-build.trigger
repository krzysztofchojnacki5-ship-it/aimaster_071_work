AiFORTeca 0.2 full Android build requested after package 44.
Source PR: #3
Source branch: work/aiforteca-0.2-master-agent-foundation
Package 44 implementation and regression-test changes are recorded in docs/AIFORTECA_0.2_FOUNDATION_PLAN.md.
Scope: four package 44 fixes: redact Hugging Face hf_ tokens; redact Replicate r8_ tokens; redact Google OAuth ya29. access tokens; redact signed URL credential parameters while preserving non-secret query parameters.
Regression coverage: Hugging Face tokens, Replicate tokens, Google OAuth access tokens, signed URL query credentials.
Required outputs: installable debug APK, release APK (signed only if signing secrets are configured and validated), and source project archive.
Verification: run the complete unit-test suite, Gradle wrapper validation, assembleDebug and assembleRelease against the exact package 44 source revision. Do not mark the package complete until workflow results and artifacts are verified.

Package 44 CI correction: preserve provider-specific redaction placeholders when generic key/value sanitization runs; execute one consolidated CI verification after this correction.
