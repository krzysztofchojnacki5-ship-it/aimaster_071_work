package pl.aimasterpro.app.core

import org.junit.Test

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** JUnit coverage for store-to-registry revocation binding lifecycle. */
class CompanionTrustRevocationBindingTest {
    @Test
    fun fenceInvalidatesTicketBeforeDeferredSocketCleanup() {
        val source = FakeSource()
        val registry = CompanionConnectionRegistry()
        val oldHandle = BlockingHandle()
        check(registry.register(registry.ticket("peer-a"), oldHandle))
        val staleTicket = registry.ticket("peer-a")
        val binding = CompanionTrustRevocationBinding(source, registry)

        val batch = source.begin(CompanionTrustRevocationEvent("peer-a"))
        check(registry.liveCount() == 0)
        check(!registry.register(staleTicket, CountingHandle()))
        check(oldHandle.closeCount == 0)

        val cleanup = thread { batch.close() }
        check(oldHandle.closeEntered.await(2, TimeUnit.SECONDS))
        oldHandle.allowClose.countDown()
        cleanup.join(2_000)
        check(!cleanup.isAlive)
        check(oldHandle.closeCount == 1)
        binding.close()
    }

    @Test
    fun closeWaitsForAnInFlightFenceCleanup() {
        val source = FakeSource()
        val registry = CompanionConnectionRegistry()
        val handle = BlockingHandle()
        check(registry.register(registry.ticket("peer-a"), handle))
        val binding = CompanionTrustRevocationBinding(source, registry)
        val batch = source.begin(CompanionTrustRevocationEvent("peer-a"))
        val cleanup = thread { batch.close() }
        check(handle.closeEntered.await(2, TimeUnit.SECONDS))
        val closeFinished = CountDownLatch(1)
        val closer = thread { binding.close(); closeFinished.countDown() }
        check(!closeFinished.await(100, TimeUnit.MILLISECONDS))
        handle.allowClose.countDown()
        cleanup.join(2_000)
        closer.join(2_000)
        check(!cleanup.isAlive && !closer.isAlive)
        check(closeFinished.count == 0L)
        check(handle.closeCount == 1)
    }

    @Test
    fun concurrentCloseCallersAllWaitForInFlightCleanup() {
        val source = FakeSource()
        val registry = CompanionConnectionRegistry()
        val handle = BlockingHandle()
        check(registry.register(registry.ticket("peer-close"), handle))
        val binding = CompanionTrustRevocationBinding(source, registry)
        val batch = source.begin(CompanionTrustRevocationEvent("peer-close"))
        val cleanup = thread { batch.close() }
        check(handle.closeEntered.await(2, TimeUnit.SECONDS))

        val firstDone = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val first = thread { binding.close(); firstDone.countDown() }
        Thread.sleep(50)
        val second = thread { binding.close(); secondDone.countDown() }
        check(!firstDone.await(100, TimeUnit.MILLISECONDS))
        check(!secondDone.await(100, TimeUnit.MILLISECONDS))
        handle.allowClose.countDown()
        cleanup.join(2_000)
        first.join(2_000)
        second.join(2_000)
        check(!cleanup.isAlive && !first.isAlive && !second.isAlive)
        check(firstDone.count == 0L && secondDone.count == 0L)
    }

    @Test
    fun noFenceCanBeginAfterBindingCloseReturns() {
        val source = FakeSource()
        val registry = CompanionConnectionRegistry()
        val binding = CompanionTrustRevocationBinding(source, registry)
        binding.close()
        val handle = CountingHandle()
        check(registry.register(registry.ticket("peer-b"), handle))
        val batch = source.beginOrNull(CompanionTrustRevocationEvent("peer-b"))
        batch?.close()
        check(handle.closeCount == 0)
        check(registry.liveCount() == 1)
    }

    private class FakeSource : CompanionTrustRevocationSource {
        private val listeners = ArrayList<CompanionTrustRevocationListener>()
        private val fences = ArrayList<CompanionTrustRevocationFenceListener>()
        override fun addTrustRevocationListener(listener: CompanionTrustRevocationListener): Boolean =
            synchronized(listeners) { if (listeners.contains(listener)) false else listeners.add(listener) }
        override fun removeTrustRevocationListener(listener: CompanionTrustRevocationListener): Boolean =
            synchronized(listeners) { listeners.remove(listener) }
        override fun addTrustRevocationFenceListener(listener: CompanionTrustRevocationFenceListener): Boolean =
            synchronized(fences) { if (fences.contains(listener)) false else fences.add(listener) }
        override fun removeTrustRevocationFenceListener(listener: CompanionTrustRevocationFenceListener): Boolean =
            synchronized(fences) { fences.remove(listener) }
        fun begin(event: CompanionTrustRevocationEvent): Closeable =
            beginOrNull(event) ?: error("No registered fence")
        fun beginOrNull(event: CompanionTrustRevocationEvent): Closeable? {
            val snapshot = synchronized(fences) { fences.toList() }
            if (snapshot.isEmpty()) return null
            val batches = snapshot.map { it.begin(event) }
            return Closeable { batches.forEach(Closeable::close) }
        }
    }

    private class CountingHandle : Closeable {
        @Volatile var closeCount = 0
        override fun close() { closeCount++ }
    }

    private class BlockingHandle : Closeable {
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        @Volatile var closeCount = 0
        override fun close() {
            closeCount++
            closeEntered.countDown()
            check(allowClose.await(2, TimeUnit.SECONDS))
        }
    }
}
