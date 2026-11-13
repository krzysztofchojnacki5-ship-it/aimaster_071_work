package pl.aimasterpro.app.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Mirrors MainActivity's lock contract: chat identity is volatile and never takes its monitor. */
private class ChatState {
    @Volatile var currentChatId: String = "chat-A"
    fun isCurrentChat(chatId: String): Boolean = chatId.isNotBlank() && currentChatId == chatId
}

private fun await(latch: CountDownLatch, label: String) {
    check(latch.await(3, TimeUnit.SECONDS)) { "Timed out: $label" }
}

fun main() {
    run {
        val chat = ChatState()
        val workspaceHeld = CountDownLatch(1)
        val activationMonitorHeld = CountDownLatch(1)
        val operationCheckedChat = CountDownLatch(1)
        val releaseWorkspace = CountDownLatch(1)
        val activationFinished = AtomicBoolean(false)
        val operationFinished = AtomicBoolean(false)

        val workspaceOperation = Thread {
            ProjectWorkspaceOperationLock.withLock {
                workspaceHeld.countDown()
                await(activationMonitorHeld, "activation owns chat monitor")
                check(chat.isCurrentChat("chat-A"))
                operationFinished.set(true)
                operationCheckedChat.countDown()
                check(releaseWorkspace.await(3, TimeUnit.SECONDS))
            }
        }
        val activation = Thread {
            synchronized(chat) {
                activationMonitorHeld.countDown()
                ProjectWorkspaceOperationLock.withLock { activationFinished.set(true) }
            }
        }
        workspaceOperation.start()
        await(workspaceHeld, "workspace lock acquired first")
        activation.start()
        await(activationMonitorHeld, "activation monitor acquired while waiting for workspace")
        await(operationCheckedChat, "workspace owner completes lock-free chat check")
        check(operationFinished.get()) { "lock-free chat check did not complete under workspace lock" }
        releaseWorkspace.countDown()
        activation.join(3000); workspaceOperation.join(3000)
        check(!activation.isAlive && !workspaceOperation.isAlive)
        check(activationFinished.get())
        println("PASS chat identity check does not invert chat-monitor/workspace-lock order")
    }

    run {
        val chat = ChatState()
        val reconciled = CountDownLatch(1)
        val allowActivation = CountDownLatch(1)
        val activationSucceeded = AtomicBoolean(false)
        val worker = Thread {
            ProjectWorkspaceOperationLock.withLock {
                reconciled.countDown()
                check(allowActivation.await(3, TimeUnit.SECONDS))
                activationSucceeded.set(chat.isCurrentChat("chat-A"))
            }
        }
        worker.start()
        await(reconciled, "receipt reconciliation completed")
        chat.currentChatId = "chat-B"
        allowActivation.countDown()
        worker.join(3000)
        check(!worker.isAlive && !activationSucceeded.get())
        println("PASS chat switch after reconcile blocks activation")
    }

    run {
        val pending = AtomicBoolean(true)
        val reconcileCount = java.util.concurrent.atomic.AtomicInteger(0)
        val acknowledged = java.util.concurrent.atomic.AtomicInteger(0)
        val start = CountDownLatch(1)
        val workers = (1..2).map {
            Thread {
                start.await()
                ProjectWorkspaceOperationLock.withLock {
                    if (pending.get()) {
                        reconcileCount.incrementAndGet()
                        // The first holder durably acknowledges before releasing the same lock.
                        if (pending.compareAndSet(true, false)) acknowledged.incrementAndGet()
                    }
                }
            }
        }
        workers.forEach(Thread::start)
        start.countDown()
        workers.forEach { it.join(3000); check(!it.isAlive) }
        check(reconcileCount.get() == 1 && acknowledged.get() == 1)
        println("PASS concurrent recovery callers reconcile and acknowledge one pending receipt once")
    }

    println("PATCH_RECOVERY_CONCURRENCY: 3/3 PASS")
}
