AiFORTeca 0.2 full Android build requested after package 40.
Source PR: #3
Source branch: work/aiforteca-0.2-master-agent-foundation
Source HEAD before build trigger: a66015f8ef1eb07dc53d027b9ae690437e598df2
Scope: four package 40 hardening fixes: report missing agent provider configuration before cost-policy fallback rejection; reject blank and nested model-profile identities in provider-screen resolution; validate imported agent-provider assignments against catalog and identifier bounds; cap full encrypted provider imports at 500 profiles before mutation.
Required outputs: installable debug APK, release APK (signed only if signing secrets are configured and validated), and source project archive.
Verification: run the complete unit-test suite, Gradle wrapper validation, assembleDebug and assembleRelease against the exact package 40 source revision. Do not mark the package complete until workflow results and artifacts are verified.
