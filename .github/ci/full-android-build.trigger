AiFORTeca 0.2 full Android build requested after package 24.
Package 24 branch: work/aiforteca-0.2-package-24-storage-health-diagnostics
Package 24 source HEAD before full build trigger: 3f42fd7d3250cd0043e73d84b0ee84efc0993e66
Package 24 scope: unified read-only diagnostics for durable storage health across MASTER queue, agent handoffs, chat history, MASTER response archive, agent result archive and task checkpoints; fail-closed health projection; regression tests for healthy/degraded/unavailable states.
Required outputs: testDebugUnitTest; assembleDebug; assembleRelease; source project; debug APK; release APK (signed only if signing secrets are configured and verified, otherwise unsigned).
Safety: diagnostics must not mutate stores, interpret unavailable as empty, expose conversation/report contents, or clear/rewrite user data.
