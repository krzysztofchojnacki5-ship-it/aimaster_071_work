# Error Knowledge Base
Version: 0.0.1

## Error case fields
- error_id, first_seen_at, last_seen_at
- project/repository, branch, commit, OS, JDK, Gradle, AGP, Kotlin, SDK
- operation and exact command
- exact error output (redacted)
- reproduction steps and reproducibility
- observed facts, hypotheses, confirmed root cause
- attempted fixes and outcomes
- linked task IDs, commits, tests, and evidence
- status: OPEN | INVESTIGATING | ROOT_CAUSE_CONFIRMED | FIXED_UNVERIFIED | VERIFIED | WONT_FIX

## Fix promotion
A proposed fix is not a known-good fix until:
1. the original failure is reproduced or a defensible reproduction is documented;
2. the change is linked to an exact commit;
3. relevant regression tests are run;
4. the required build/integration checks pass;
5. the result is recorded with environment and evidence.

If the original failure cannot be reproduced, mark the limitation and do not overstate confidence.
