# MASTER Memory — schema and lifecycle
Version: 0.0.1

Memory is a structured evidence store, not an unfiltered transcript dump.

## Record types
- USER_PREFERENCE: explicit, user-approved durable preference.
- PROJECT_FACT: observed project/environment fact with source and observed-at time.
- DECISION: selected option, alternatives, rationale, owner, and review condition.
- ERROR_CASE: reproducible symptom, environment, root-cause status, and evidence.
- FIX_RECORD: change linked to an error case, commit, tests, and verification status.
- TASK_CHECKPOINT: resumable task state and completed/remaining steps.

## Required fields
Every record includes: id, type, schema_version, created_at, updated_at, source, author, status, confidence, and content.
Project/error/fix records additionally include repository, branch, commit SHA where applicable, environment, and evidence references.

## Lifecycle
PROPOSED -> APPROVED -> ACTIVE -> SUPERSEDED or ARCHIVED.
Only explicitly approved user preferences and project decisions become authoritative memory. Automatically collected observations remain attributed observations, not user preferences.

## Integrity and privacy
- Keep secrets, API keys, passwords, tokens, and private credentials out of memory.
- Minimize personal data and retain only what is necessary for the project.
- Preserve provenance and support correction/export/deletion.
- Never treat model-generated content as a user-approved fact.
- Memory retrieval must include confidence and freshness; stale facts must be revalidated before risky actions.
