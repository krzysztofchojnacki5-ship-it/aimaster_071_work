AiFORTeca 0.2 full Android build requested after package 21.
Source PR: #3
Source branch: work/aiforteca-0.2-master-agent-foundation
Source HEAD before CI trigger: 527ca21cf17c977c068b767e9e01a2613a2d5f80
Required outputs: debug APK; unsigned release APK unless signing secrets are configured and verified.
Verification: run unit tests, Gradle wrapper validation, assembleDebug and assembleRelease. Do not treat reports or source ZIP as APK.
Package 21: bounded conversation history, provider payload mapping and chat-scoped MASTER context.
Package 22: live filtered diagnostics feed; source-filtered QUEUE/HANDOFF events, visible filters, 5-second in-place refresh and lifecycle fencing.
CI trigger requested after the Foojay resolver update to 1.0.0 and Kotlin diagnostics snapshot fix.
