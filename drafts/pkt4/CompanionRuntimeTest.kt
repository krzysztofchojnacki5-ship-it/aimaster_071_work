package pl.aimasterpro.app.core

import org.junit.Test

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Local JVM tests for process-owner identity and lifecycle. */
class CompanionRuntimeTest {
    @Test
    fun slotReturnsOneRuntimeAndOneRegistryForRepeatedRequests() {
        val source = FakeSource()
        val slot = CompanionRuntimeSlot()
        val first = slot.getOrCreate(source)
        val second = slot.getOrCreate(source)
        check(first === second)
        check(first.registry === second.registry)
        slot.close()
    }

    @Test
    fun closingSlotAllowsFreshRuntimeWithoutLeavingOldFenceRegistered() {
        val source = FakeSource()
        val slot = CompanionRuntimeSlot()
        val first = slot.getOrCreate(source)
        slot.close()
        val second = slot.getOrCreate(source)
        check(first !== second)
        val handle = CountingHandle()
        check(second.registry.register(second.registry.ticket("peer-c"), handle))
        source.revoke("peer-c")
        check(handle.closeCount == 1)
        slot.close()
    }

    @Test
    fun concurrentRuntimeCloseCallersWaitForBindingCleanup() {
        val source = FakeSource()
        val slot = CompanionRuntimeSlot()
        val runtime = slot.getOrCreate(source)
        val handle = BlockingHandle()
        check(runtime.registry.register(runtime.registry.ticket("peer-runtime"), handle))
        val batch = source.begin("peer-runtime")
        val cleanup = thread { batch.close() }
        check(handle.closeEntered.await(2, TimeUnit.SECONDS))

        val firstDone = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val first = thread { runtime.close(); firstDone.countDown() }
        Thread.sleep(50)
        val second = thread { runtime.close(); secondDone.countDown() }
        check(!firstDone.await(100, TimeUnit.MILLISECONDS))
        check(!secondDone.await(100, TimeUnit.MILLISECONDS))
        handle.allowClose.countDown()
        cleanup.join(2_000)
        first.join(2_000)
        second.join(2_000)
        check(!cleanup.isAlive && !first.isAlive && !second.isAlive)
        check(firstDone.count == 0L && secondDone.count == 0L)
        slot.close()
    }

    @Test
    fun closedRuntimeRejectsFurtherUse() {
        val slot = CompanionRuntimeSlot()
        val runtime = slot.getOrCreate(FakeSource())
        slot.close()
        check(runCatching { runtime.ensureOpen() }.isFailure)
    }

    private class FakeSource : CompanionTrustRevocationSource {
        private val fences = LinkedHashSet<CompanionTrustRevocationFenceListener>()
        override fun addTrustRevocationListener(listener: CompanionTrustRevocationListener) = true
        override fun removeTrustRevocationListener(listener: CompanionTrustRevocationListener) = true
        override fun addTrustRevocationFenceListener(listener: CompanionTrustRevocationFenceListener): Boolean =
            synchronized(fences) { fences.add(listener) }
        override fun removeTrustRevocationFenceListener(listener: CompanionTrustRevocationFenceListener): Boolean =
            synchronized(fences) { fences.remove(listener) }
        fun begin(peerId: String): Closeable = synchronized(fences) { fences.toList() }.map {
            it.begin(CompanionTrustRevocationEvent(peerId))
        }.let { batches -> Closeable { batches.forEach(Closeable::close) } }
        fun revoke(peerId: String) {
            val batches = synchronized(fences) { fences.toList() }.map {
                it.begin(CompanionTrustRevocationEvent(peerId))
            }
            batches.forEach(Closeable::close)
        }
    }

    private class BlockingHandle : Closeable {
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        override fun close() {
            closeEntered.countDown()
            check(allowClose.await(2, TimeUnit.SECONDS))
        }
    }

    private class CountingHandle : Closeable {
        var closeCount = 0
        override fun close() { closeCount++ }
    }
}
