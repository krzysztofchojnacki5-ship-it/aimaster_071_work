# Decision Ledger
Version: 0.0.1

Append-only decision history. Corrections are new entries referencing the superseded entry; do not silently rewrite historical rationale.

## Required entry
- decision_id
- task_id
- timestamp_utc
- actor (MASTER, agent ID, or user)
- context and objective
- options_considered
- selected_option
- rationale and evidence_refs
- assumptions and confidence
- risk/impact
- approval_required / approval_reference
- outcome: PROPOSED | APPROVED | REJECTED | IMPLEMENTED | SUPERSEDED
- review_trigger

## Rules
1. Separate evidence from interpretation.
2. Record material rejected alternatives and why they were rejected.
3. Link implementation decisions to commit SHA and changed paths.
4. Record reversals as new decisions with the reason.
5. A model's confidence score never substitutes for evidence or user approval.
