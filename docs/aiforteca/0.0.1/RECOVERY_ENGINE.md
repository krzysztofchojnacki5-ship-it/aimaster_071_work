# Recovery Engine
Version: 0.0.1

## Persisted task state
Each task checkpoint stores:
- task_id, task_version, objective, scope, owner
- repository, branch, starting HEAD, latest known HEAD
- state and completed step IDs
- pending step IDs and dependencies
- active agent IDs and cancellation state
- files touched and expected/current hashes where available
- last verified result and evidence references
- retry count, last failure signature, next safe action
- timestamp and checkpoint schema version

## Resume procedure
1. Load the last durable checkpoint.
2. Re-read the actual repository branch, HEAD, and working tree.
3. Compare actual state with recorded expected state.
4. Inspect any in-flight operation for partial effects.
5. Reconcile ledger entries and agent task status.
6. Resume only the first safe incomplete step.

## Idempotency and retries
- Assign an idempotency key to every external or write operation.
- Before retrying, check whether the previous operation already took effect.
- Do not blindly repeat purchases, messages, commits, deployments, or other external side effects.
- Retry transient failures only with bounded exponential backoff and a maximum attempt count.
- A repeated identical failure requires new evidence or a changed plan.

## Recovery outcomes
RESUMED, ROLLED_BACK, NEEDS_USER_APPROVAL, BLOCKED, ABANDONED.
Record the reason and all reconciled state changes.
