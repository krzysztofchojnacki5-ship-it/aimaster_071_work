package pl.aimasterpro.app.core

import org.junit.Test

class CompanionTrustRevocationPublisherTest {
    @Test fun everyListenerIsAttemptedEvenWhenAnEarlierListenerFails() {
        val publisher = CompanionTrustRevocationPublisher()
        val calls = mutableListOf<String>()
        publisher.add(CompanionTrustRevocationListener {
            calls += "first"
            throw IllegalStateException("simulated listener failure")
        })
        publisher.add(CompanionTrustRevocationListener { calls += "second:${it.peerId}" })

        val failures = publisher.publish(CompanionTrustRevocationEvent("desktop"))
        check(calls == listOf("first", "second:desktop"))
        check(failures.size == 1)
        check(failures.single().message == "simulated listener failure")
    }

    @Test fun fatalListenerErrorIsRethrownOnlyAfterEveryListenerWasAttempted() {
        val publisher = CompanionTrustRevocationPublisher()
        val calls = mutableListOf<String>()
        publisher.add(CompanionTrustRevocationListener {
            calls += "fatal"
            throw AssertionError("fatal callback")
        })
        publisher.add(CompanionTrustRevocationListener { calls += "last:${it.peerId}" })

        try {
            publisher.publish(CompanionTrustRevocationEvent("desktop"))
            error("Expected fatal callback error")
        } catch (expected: AssertionError) {
            check(expected.message == "fatal callback")
        }
        check(calls == listOf("fatal", "last:desktop"))
    }

    @Test fun globalWipeEventIsExplicitAndListenerCanBeRemoved() {
        val publisher = CompanionTrustRevocationPublisher()
        var seen: CompanionTrustRevocationEvent? = null
        val listener = CompanionTrustRevocationListener { seen = it }
        check(publisher.add(listener))
        check(!publisher.add(listener))
        check(publisher.publish(CompanionTrustRevocationEvent(null)).isEmpty())
        check(seen == CompanionTrustRevocationEvent(null))
        check(publisher.remove(listener))
        check(!publisher.remove(listener))
        seen = null
        publisher.publish(CompanionTrustRevocationEvent(null))
        check(seen == null)
    }

    @Test fun everyFenceBeginsBeforeCleanupAndPreparationFailureIsRetained() {
        val publisher = CompanionTrustRevocationPublisher()
        val calls = mutableListOf<String>()
        publisher.addFence(CompanionTrustRevocationFenceListener {
            calls += "fence-1:${it.peerId}"
            java.io.Closeable { calls += "cleanup-1" }
        })
        publisher.addFence(CompanionTrustRevocationFenceListener {
            calls += "fence-2"
            throw IllegalStateException("fence preparation failed")
        })
        val batch = publisher.beginRevocation(CompanionTrustRevocationEvent("desktop"))
        check(calls == listOf("fence-1:desktop", "fence-2"))
        check(batch.preparationFailures.size == 1)
        try {
            batch.close()
            error("Expected preparation failure")
        } catch (expected: IllegalStateException) {
            check(expected.message == "Nie udało się w pełni wykonać cleanup revocation fence.")
            check(expected.cause?.message == "fence preparation failed")
        }
        check(calls.last() == "cleanup-1")
    }

    @Test fun fatalFenceFailureDoesNotPreventOtherFenceCleanup() {
        val publisher = CompanionTrustRevocationPublisher()
        val calls = mutableListOf<String>()
        publisher.addFence(CompanionTrustRevocationFenceListener {
            calls += "fatal-fence"
            throw AssertionError("fatal fence")
        })
        publisher.addFence(CompanionTrustRevocationFenceListener {
            calls += "later-fence"
            java.io.Closeable { calls += "later-cleanup" }
        })
        val batch = publisher.beginRevocation(CompanionTrustRevocationEvent(null))
        check(calls == listOf("fatal-fence", "later-fence"))
        try {
            batch.close()
            error("Expected fatal fence failure")
        } catch (expected: AssertionError) {
            check(expected.message == "fatal fence")
        }
        check(calls.last() == "later-cleanup")
    }


    @Test fun concurrentFenceBatchCloseWaitsUntilCleanupFinishes() {
        val publisher = CompanionTrustRevocationPublisher()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        publisher.addFence(CompanionTrustRevocationFenceListener {
            java.io.Closeable {
                entered.countDown()
                check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
        })
        val batch = publisher.beginRevocation(CompanionTrustRevocationEvent("peer"))
        val first = Thread { batch.close() }.apply { start() }
        check(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val secondFinished = java.util.concurrent.CountDownLatch(1)
        val second = Thread { batch.close(); secondFinished.countDown() }.apply { start() }
        check(!secondFinished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        release.countDown()
        first.join(2_000); second.join(2_000)
        check(!first.isAlive && !second.isAlive)
        check(secondFinished.count == 0L)
    }

}
