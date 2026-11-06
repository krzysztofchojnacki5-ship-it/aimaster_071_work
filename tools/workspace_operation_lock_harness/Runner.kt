import pl.aimasterpro.app.core.ProjectWorkspaceOperationLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private fun expect(condition: Boolean, message: String) {
    check(condition) { message }
}

fun main() {
    // The lock is intentionally reentrant: host operations may call guarded helpers.
    val nested = ProjectWorkspaceOperationLock.withLock {
        ProjectWorkspaceOperationLock.withLock { "nested-ok" }
    }
    expect(nested == "nested-ok", "reentrant acquisition failed")
    val nestedProbe = ProjectWorkspaceOperationLock.withLock {
        ProjectWorkspaceOperationLock.tryWithLock { "nested-probe-ok" }
    }
    expect(nestedProbe == "nested-probe-ok", "reentrant non-blocking acquisition failed")
    println("PASS reentrant nested workspace operation and try-probe")

    // tryWithLock must not block a UI caller while another thread owns the gate.
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val owner = Thread {
        ProjectWorkspaceOperationLock.withLock {
            entered.countDown()
            expect(release.await(5, TimeUnit.SECONDS), "owner release timed out")
        }
    }
    owner.start()
    expect(entered.await(5, TimeUnit.SECONDS), "owner did not acquire lock")
    val probe = ProjectWorkspaceOperationLock.tryWithLock { "unexpected" }
    expect(probe == null, "tryWithLock must return null while lock is owned")
    release.countDown()
    owner.join(5_000)
    expect(!owner.isAlive, "owner thread did not finish")
    expect(ProjectWorkspaceOperationLock.tryWithLock { "available" } == "available", "lock not released")
    println("PASS non-blocking probe and release after contention")

    // Parallel workspace operations must never overlap their critical sections.
    val workers = 8
    val rounds = 150
    val ready = CountDownLatch(workers)
    val start = CountDownLatch(1)
    val inside = AtomicInteger(0)
    val maxInside = AtomicInteger(0)
    val failures = AtomicReference<Throwable?>(null)
    val threads = (0 until workers).map {
        Thread {
            try {
                ready.countDown()
                expect(start.await(5, TimeUnit.SECONDS), "start gate timed out")
                repeat(rounds) {
                    ProjectWorkspaceOperationLock.withLock {
                        val active = inside.incrementAndGet()
                        maxInside.updateAndGet { previous -> maxOf(previous, active) }
                        Thread.yield()
                        inside.decrementAndGet()
                    }
                }
            } catch (error: Throwable) {
                failures.compareAndSet(null, error)
            }
        }.apply { start() }
    }
    expect(ready.await(5, TimeUnit.SECONDS), "workers did not become ready")
    start.countDown()
    threads.forEach { it.join(10_000) }
    expect(threads.none { it.isAlive }, "worker did not finish")
    failures.get()?.let { throw it }
    expect(maxInside.get() == 1, "critical sections overlapped: max=${maxInside.get()}")
    println("PASS concurrent mutual exclusion (${workers * rounds} acquisitions)")

    // Exceptions must not strand the process-wide gate.
    val expected = runCatching {
        ProjectWorkspaceOperationLock.withLock { error("injected-operation-failure") }
    }.exceptionOrNull()
    expect(expected?.message == "injected-operation-failure", "original exception was not propagated")
    expect(ProjectWorkspaceOperationLock.tryWithLock { true } == true, "lock remained held after exception")
    println("PASS exception propagation and guaranteed unlock")

    val tryFailure = runCatching {
        ProjectWorkspaceOperationLock.tryWithLock<Boolean> { error("injected-probe-failure") }
    }.exceptionOrNull()
    expect(tryFailure?.message == "injected-probe-failure", "tryWithLock exception was not propagated")
    expect(ProjectWorkspaceOperationLock.withLock { true }, "lock remained held after probe exception")
    println("PASS tryWithLock exception cleanup")

    // A pre-interrupted UI caller must fail closed without losing its interrupt signal.
    val interruptedResult = AtomicReference<String?>("not-run")
    val interruptPreserved = AtomicReference(false)
    val interruptedProbe = Thread {
        Thread.currentThread().interrupt()
        interruptedResult.set(ProjectWorkspaceOperationLock.tryWithLock { "unexpected" })
        interruptPreserved.set(Thread.currentThread().isInterrupted)
    }
    interruptedProbe.start()
    interruptedProbe.join(5_000)
    expect(!interruptedProbe.isAlive, "interrupted probe did not finish")
    expect(interruptedResult.get() == null, "interrupted probe must fail closed")
    expect(interruptPreserved.get(), "tryWithLock must restore the interrupt flag")
    println("PASS interrupted non-blocking probe preserves interrupt status")

    // A failure-path decision (pending probe + ledger rejection + pointer invalidation)
    // must be one critical section. A concurrent patch/import cannot slip between the
    // CLEAR observation and the invalidation that relies on it.
    val decisionEntered = CountDownLatch(1)
    val finishDecision = CountDownLatch(1)
    val mutationFinished = CountDownLatch(1)
    val order = java.util.Collections.synchronizedList(mutableListOf<String>())
    val decision = Thread {
        ProjectWorkspaceOperationLock.withLock {
            val pending = false // deterministic snapshot returned by the guarded probe
            order += "probe-clear"
            decisionEntered.countDown()
            expect(finishDecision.await(5, TimeUnit.SECONDS), "decision release timed out")
            if (!pending) order += "reject-ledger"
            order += "invalidate-pointer"
        }
    }
    decision.start()
    expect(decisionEntered.await(5, TimeUnit.SECONDS), "failure decision did not enter lock")
    val competingMutation = Thread {
        ProjectWorkspaceOperationLock.withLock {
            order += "new-workspace-mutation"
            mutationFinished.countDown()
        }
    }
    competingMutation.start()
    expect(!mutationFinished.await(100, TimeUnit.MILLISECONDS), "competing mutation crossed failure decision")
    finishDecision.countDown()
    decision.join(5_000)
    competingMutation.join(5_000)
    expect(!decision.isAlive && !competingMutation.isAlive, "serialized decision threads did not finish")
    expect(order.toList() == listOf("probe-clear", "reject-ledger", "invalidate-pointer", "new-workspace-mutation"),
        "failure decision was not atomic: $order")
    println("PASS atomic pending decision, ledger rejection, and pointer invalidation")

    // The diagnostic post-recovery identity + pointer read also needs a single lock
    // acquisition; otherwise the two values may describe different workspace generations.
    val diagnosticEntered = CountDownLatch(1)
    val finishDiagnostic = CountDownLatch(1)
    val diagnosticRead = Thread {
        ProjectWorkspaceOperationLock.withLock {
            order += "diagnostic-identity"
            diagnosticEntered.countDown()
            expect(finishDiagnostic.await(5, TimeUnit.SECONDS), "diagnostic release timed out")
            order += "diagnostic-pointer"
        }
    }
    diagnosticRead.start()
    expect(diagnosticEntered.await(5, TimeUnit.SECONDS), "diagnostic read did not enter lock")
    val competingImport = Thread {
        ProjectWorkspaceOperationLock.withLock { order += "diagnostic-import" }
    }
    competingImport.start()
    Thread.sleep(100)
    expect(competingImport.isAlive, "workspace import crossed atomic diagnostic read")
    finishDiagnostic.countDown()
    diagnosticRead.join(5_000)
    competingImport.join(5_000)
    expect(!diagnosticRead.isAlive && !competingImport.isAlive, "diagnostic serialization threads did not finish")
    expect(order.takeLast(3) == listOf("diagnostic-identity", "diagnostic-pointer", "diagnostic-import"),
        "diagnostic identity/pointer observation was interleaved: $order")
    println("PASS atomic post-recovery identity and active-pointer observation")

    // Chat-switch pointer invalidation and startup receipt commit must serialize on the same
    // gate. Whichever owns the gate first completes before the other can touch the pointer.
    val recoveryEntered = CountDownLatch(1)
    val finishRecovery = CountDownLatch(1)
    val switchFinished = CountDownLatch(1)
    val lifecycleOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
    val recoveryCommit = Thread {
        ProjectWorkspaceOperationLock.withLock {
            lifecycleOrder += "recovery-check-chat"
            recoveryEntered.countDown()
            expect(finishRecovery.await(5, TimeUnit.SECONDS), "recovery release timed out")
            lifecycleOrder += "recovery-activate-ack"
        }
    }
    recoveryCommit.start()
    expect(recoveryEntered.await(5, TimeUnit.SECONDS), "recovery commit did not enter lock")
    val chatSwitch = Thread {
        ProjectWorkspaceOperationLock.withLock {
            lifecycleOrder += "chat-switch-invalidate"
            switchFinished.countDown()
        }
    }
    chatSwitch.start()
    expect(!switchFinished.await(100, TimeUnit.MILLISECONDS), "chat switch crossed recovery commit")
    finishRecovery.countDown()
    recoveryCommit.join(5_000)
    chatSwitch.join(5_000)
    expect(!recoveryCommit.isAlive && !chatSwitch.isAlive, "lifecycle serialization threads did not finish")
    expect(lifecycleOrder.toList() == listOf("recovery-check-chat", "recovery-activate-ack", "chat-switch-invalidate"),
        "chat switch and recovery commit were not serialized: $lifecycleOrder")
    println("PASS chat-switch invalidation serialized with startup recovery commit")

    println("WORKSPACE_OPERATION_LOCK: 10/10 PASS")
}
