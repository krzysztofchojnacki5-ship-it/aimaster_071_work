package pl.aimasterpro.app.core

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Local JVM harness tests; kept in drafts until the complete Android source-set promotion. */
class CompanionApplicationGraphTest {
    fun storeFactoryRunsOnceAcrossConcurrentConsumers() {
        val creations = AtomicInteger()
        val graph = CompanionApplicationGraph {
            creations.incrementAndGet()
            FakeSource()
        }
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val stores = java.util.Collections.synchronizedList(mutableListOf<FakeSource>())
        repeat(16) {
            thread {
                start.await()
                stores += graph.provisioningStore()
                done.countDown()
            }
        }
        start.countDown()
        check(done.await(3, TimeUnit.SECONDS))
        check(creations.get() == 1)
        check(stores.size == 16 && stores.all { it === stores.first() })
        graph.close()
    }

    fun runtimeIsSharedAndRecreatedOnlyAfterGraphClose() {
        val graph = CompanionApplicationGraph { FakeSource() }
        val first = graph.runtime()
        check(graph.runtime() === first)
        graph.close()
        val second = graph.runtime()
        check(second !== first)
        check(graph.provisioningStore() === graph.provisioningStore())
        graph.close()
    }

    fun failedStoreFactoryCanBeRetriedWithoutPoisoningGraph() {
        val attempts = AtomicInteger()
        val source = FakeSource()
        val graph = CompanionApplicationGraph {
            if (attempts.incrementAndGet() == 1) error("synthetic store failure")
            source
        }
        check(runCatching { graph.provisioningStore() }.isFailure)
        check(graph.provisioningStore() === source)
        check(attempts.get() == 2)
        graph.close()
    }

    private class FakeSource : CompanionTrustRevocationSource {
        private val fences = java.util.concurrent.CopyOnWriteArrayList<CompanionTrustRevocationFenceListener>()
        override fun addTrustRevocationListener(listener: CompanionTrustRevocationListener) = true
        override fun removeTrustRevocationListener(listener: CompanionTrustRevocationListener) = true
        override fun addTrustRevocationFenceListener(listener: CompanionTrustRevocationFenceListener): Boolean = fences.addIfAbsent(listener)
        override fun removeTrustRevocationFenceListener(listener: CompanionTrustRevocationFenceListener): Boolean = fences.remove(listener)
    }
}
