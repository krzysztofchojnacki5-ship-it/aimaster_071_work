AiFORTeca 0.2 full Android build requested after package 25.
Package 25 branch: work/aiforteca-0.2-package-25-provider-assignment-governance
Package 25 source HEAD before full build trigger: 65cee36d21f473c21b9a1f2a53e616df0a7d231a
Package 25 scope: require explicit USER governance and append-only audit for agent-role provider/model assignment changes; bind approval identity to the intended provider; recover interrupted assignment audit records only after startup verifies the durable role-assignment snapshot.
Four logical fixes:
1. Add SET_AGENT_PROVIDER to central governance and audit replay authorization.
2. Require a scoped MemoryMutationPermit for every explicit single-role provider assignment write.
3. Route MainActivity assignment changes through MemoryMutationGate; publish runtime state only after durable mutation and terminal audit both succeed.
4. On startup, reconcile unresolved assignment intents against the verified persisted assignment snapshot; never replay the requested write blindly.
Required outputs: testDebugUnitTest; assembleDebug; assembleRelease; source project; debug APK; release APK (signed only if signing secrets are configured and verified, otherwise unsigned).
Safety: no paid/unknown-price policy changes, no automatic approvals, no provider-key changes, no writes during recovery other than closing the audit record after the durable assignment snapshot has been read and reconciled.