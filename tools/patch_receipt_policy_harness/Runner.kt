import pl.aimasterpro.app.core.PatchReceiptRecoveryPolicy

private fun checkCase(name: String, expected: PatchReceiptRecoveryPolicy.Decision, input: PatchReceiptRecoveryPolicy.Input) {
    val actual = PatchReceiptRecoveryPolicy.decide(input)
    check(actual == expected) { "$name: expected=$expected actual=$actual" }
    println("PASS $name")
}

fun main() {
    val committed = "workspace-after"
    val base = "workspace-before"
    checkCase("approved committed patch repairs snapshot and status",
        PatchReceiptRecoveryPolicy.Decision(false, true, "APPLIED_UNVERIFIED", false, true),
        PatchReceiptRecoveryPolicy.Input(false, committed, base, "APPROVED", ""))
    checkCase("snapshot already written before crash is not rewritten",
        PatchReceiptRecoveryPolicy.Decision(false, false, "APPLIED_UNVERIFIED", false, true),
        PatchReceiptRecoveryPolicy.Input(false, committed, base, "APPROVED", "snapshot-1"))
    checkCase("status already persisted is idempotent",
        PatchReceiptRecoveryPolicy.Decision(false, false, null, false, true),
        PatchReceiptRecoveryPolicy.Input(false, committed, base, "APPLIED_UNVERIFIED", "snapshot-1"))
    checkCase("active pointer persisted before crash remains eligible",
        PatchReceiptRecoveryPolicy.Decision(false, false, null, false, true),
        PatchReceiptRecoveryPolicy.Input(false, committed, base, "APPLIED_UNVERIFIED", "snapshot-1"))
    checkCase("receipt acknowledged after reconciliation needs no transition",
        PatchReceiptRecoveryPolicy.Decision(false, false, null, false, true),
        PatchReceiptRecoveryPolicy.Input(false, committed, base, "APPLIED", "snapshot-1"))
    checkCase("rollback intent plus restored base becomes rejected",
        PatchReceiptRecoveryPolicy.Decision(true, true, "REJECTED", true, false),
        PatchReceiptRecoveryPolicy.Input(true, base, base, "APPROVED", ""))
    checkCase("rollback after applied patch becomes rolled back",
        PatchReceiptRecoveryPolicy.Decision(true, false, "ROLLED_BACK", true, false),
        PatchReceiptRecoveryPolicy.Input(true, base, base, "APPLIED_UNVERIFIED", "snapshot-1"))
    checkCase("ledger rollback is authoritative even if receipt flag lagged",
        PatchReceiptRecoveryPolicy.Decision(true, false, null, true, false),
        PatchReceiptRecoveryPolicy.Input(false, committed, base, "ROLLED_BACK", "snapshot-1"))
    checkCase("rejected ledger is never reactivated",
        PatchReceiptRecoveryPolicy.Decision(true, false, null, true, false),
        PatchReceiptRecoveryPolicy.Input(false, committed, base, "REJECTED", ""))
    checkCase("rollback decision dominates an applied status",
        PatchReceiptRecoveryPolicy.Decision(true, false, "ROLLED_BACK", true, false),
        PatchReceiptRecoveryPolicy.Input(true, committed, base, "APPLIED", "snapshot-1"))

    val invalidInputs = listOf(
        PatchReceiptRecoveryPolicy.Input(false, "", base, "APPROVED", ""),
        PatchReceiptRecoveryPolicy.Input(false, committed, "", "APPROVED", ""),
        PatchReceiptRecoveryPolicy.Input(false, committed, base, "UNKNOWN", "")
    )
    invalidInputs.forEachIndexed { index, input ->
        check(runCatching { PatchReceiptRecoveryPolicy.decide(input) }.isFailure) { "Invalid input $index must fail closed" }
    }
    println("PASS fail-closed invalid recovery inputs (3/3)")
    println("PATCH_RECEIPT_POLICY: 11/11 PASS")
}
