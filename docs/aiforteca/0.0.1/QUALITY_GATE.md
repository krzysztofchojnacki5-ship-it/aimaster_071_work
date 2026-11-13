# Quality Gate
Version: 0.0.1

A task cannot be marked COMPLETE until every applicable gate has a recorded outcome.

## Gates
1. SCOPE: changed paths and behavior stay within approved scope.
2. SAFETY: no secrets, unsafe permission escalation, destructive unapproved actions, or weakened controls.
3. CORRECTNESS: acceptance criteria are met; edge cases are considered.
4. REGRESSION: focused tests cover the reported defect and relevant neighboring behavior.
5. BUILD: required compile/build command ran successfully in a recorded environment.
6. INTEGRATION: required device/API/provider integration checks ran where applicable.
7. PERSISTENCE: task, decision, error, package, and evidence records are consistent.
8. REVIEW: independent review for high-impact or security-sensitive changes.
9. REPORT: status and residual risks are accurately disclosed.

## Gate outcomes
PASS, FAIL, BLOCKED, NOT RUN, INCONCLUSIVE, WAIVED.
Overall status is the least favorable applicable gate. WAIVED requires explicit user approval and a recorded residual risk. NOT RUN never counts as PASS.
