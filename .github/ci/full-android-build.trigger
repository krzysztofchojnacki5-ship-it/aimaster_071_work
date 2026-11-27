AiFORTeca 0.2 full Android build requested after package 40.
Source PR: #3
Source branch: work/aiforteca-0.2-master-agent-foundation
Final package 40 source and regression-test HEAD: 0a28f01838229d8a293d7d7e5c85e79d10357eb5
Scope: four package 40 hardening fixes plus a CI-discovered correction restricting CONFIG_MISSING to genuinely empty provider identity/endpoint/model configuration: report missing agent provider configuration before cost-policy fallback rejection; reject blank and nested model-profile identities in provider-screen resolution; validate imported agent-provider assignments against catalog and identifier bounds; cap full encrypted provider imports at 500 profiles before mutation.
Required outputs: installable debug APK, release APK (signed only if signing secrets are configured and validated), and source project archive.
Verification: run the complete unit-test suite, Gradle wrapper validation, assembleDebug and assembleRelease against the exact package 40 source revision. Do not mark the package complete until workflow results and artifacts are verified.
