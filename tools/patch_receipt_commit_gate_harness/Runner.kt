package pl.aimasterpro.app.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

fun main() {
    run {
        val activationEntered = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val importFinished = AtomicBoolean(false)
        val events = mutableListOf<String>()
        val worker = Thread {
            val result = PatchReceiptRecoveryCommitGate.commit(
                true, "workspace-A", { true }, { "workspace-A" },
                { activationEntered.countDown(); check(releaseActivation.await(5, TimeUnit.SECONDS)); events += "activate"; true },
                { events += "invalidate" }, { events += "ack"; true }
            )
            check(result == PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGED)
        }
        worker.start()
        check(activationEntered.await(5, TimeUnit.SECONDS))
        val importer = Thread {
            ProjectWorkspaceOperationLock.withLock { importFinished.set(true) }
        }
        importer.start()
        Thread.sleep(80)
        check(!importFinished.get()) { "import entered between activation and receipt acknowledgement" }
        releaseActivation.countDown()
        worker.join(5000); importer.join(5000)
        check(!worker.isAlive && !importer.isAlive && importFinished.get())
        check(events == listOf("activate", "ack"))
        println("PASS workspace import cannot interleave between active-pointer activation and receipt acknowledgement")
    }
    run {
        val chat = AtomicBoolean(true)
        val events = mutableListOf<String>()
        val result = PatchReceiptRecoveryCommitGate.commit(
            true, "workspace-A", { chat.get() }, { "workspace-A" },
            { events += "activate"; chat.set(false); true },
            { events += "invalidate" }, { events += "ack"; true }
        )
        check(result == PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGED)
        check(events == listOf("activate", "invalidate", "ack"))
        println("PASS chat switch during activation invalidates pointer before receipt acknowledgement")
    }
    run {
        var acknowledged = false
        val result = PatchReceiptRecoveryCommitGate.commit(
            true, "workspace-A", { true }, { "workspace-A" },
            { false }, {}, { acknowledged = true; true }
        )
        check(result == PatchReceiptRecoveryCommitGate.Result.ACTIVATION_FAILED && !acknowledged)
        println("PASS activation failure with unchanged scope preserves pending receipt for retry")
    }
    run {
        val activations = AtomicInteger()
        val invalidations = AtomicInteger()
        var acknowledged = false
        val result = PatchReceiptRecoveryCommitGate.commit(
            true, "workspace-A", { true }, { "workspace-B" },
            { activations.incrementAndGet(); true }, { invalidations.incrementAndGet() }, { acknowledged = true; true }
        )
        check(result == PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGED)
        check(activations.get() == 0 && invalidations.get() == 1 && acknowledged)
        println("PASS stale workspace identity invalidates old active scope before receipt acknowledgement")
    }
    run {
        var acknowledged = false
        val result = PatchReceiptRecoveryCommitGate.commit(
            true, "workspace-A", { true }, { "workspace-B" },
            { error("must not activate stale scope") },
            { error("simulated stale-pointer invalidation failure") },
            { acknowledged = true; true }
        )
        check(result == PatchReceiptRecoveryCommitGate.Result.INVALIDATION_FAILED && !acknowledged)
        println("PASS stale workspace invalidation failure preserves pending receipt")
    }
    run {
        val activations = AtomicInteger()
        val invalidations = AtomicInteger()
        val events = mutableListOf<String>()
        val result = PatchReceiptRecoveryCommitGate.commit(
            true, "workspace-A", { false }, { "workspace-A" },
            { activations.incrementAndGet(); true },
            { invalidations.incrementAndGet(); events += "invalidate" },
            { events += "ack"; true }
        )
        check(result == PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGED)
        check(activations.get() == 0 && invalidations.get() == 1 && events == listOf("invalidate", "ack"))
        println("PASS chat already switched before commit invalidates stale scope before receipt acknowledgement")
    }
    run {
        val result = PatchReceiptRecoveryCommitGate.commit(
            false, "workspace-A", { false }, { "workspace-B" }, { error("must not activate") }, {}, { false }
        )
        check(result == PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGE_FAILED)
        println("PASS acknowledgement failure is surfaced without a false success")
    }
    run {
        var pointerPersisted = false
        var acknowledgeSucceeds = false
        var activationWrites = 0
        fun commit() = PatchReceiptRecoveryCommitGate.commit(
            true, "workspace-A", { true }, { "workspace-A" },
            { activationWrites++; pointerPersisted = true; true },
            { pointerPersisted = false }, { acknowledgeSucceeds }
        )
        check(commit() == PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGE_FAILED)
        check(pointerPersisted && activationWrites == 1)
        acknowledgeSucceeds = true
        check(commit() == PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGED)
        check(pointerPersisted && activationWrites == 2)
        println("PASS durable active pointer survives ack failure and retry is idempotent")
    }
    run {
        var chat = true
        var acknowledged = false
        val result = PatchReceiptRecoveryCommitGate.commit(
            true, "workspace-A", { chat }, { "workspace-A" },
            { chat = false; true },
            { error("simulated durable invalidation failure") },
            { acknowledged = true; true }
        )
        check(result == PatchReceiptRecoveryCommitGate.Result.INVALIDATION_FAILED && !acknowledged)
        println("PASS failed invalidation after chat switch blocks receipt acknowledgement")
    }
    println("PATCH_RECEIPT_COMMIT_GATE: 9/9 PASS")
}
